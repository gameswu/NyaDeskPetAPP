package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.agent.provider.ChatMessage
import com.gameswu.nyadeskpet.agent.provider.ToolCallInfo
import com.gameswu.nyadeskpet.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * 对话管理器 — 对齐原项目 SessionManager (context.ts)
 *
 * 原项目使用 SQLite 持久化，KMP 版本暂使用 JSON + ConversationStorage 方案，
 * 后续如有性能需求可迁移到 SQLDelight。
 */
class ConversationManager(private val storage: ConversationStorage) {

    @Serializable
    data class Conversation(
        val id: String,
        var title: String = "",
        val createdAt: Long = currentTimeMillis(),
        var updatedAt: Long = currentTimeMillis(),
    )

    @Serializable
    data class StoredMessage(
        val role: String,
        val content: String,
        val type: String = "text", // text, command, tool_call, tool_result, image, file, system
        val extra: String = "{}",  // JSON extra data (toolCalls, toolCallId, etc.)
        val timestamp: Long = currentTimeMillis(),
    )

    @Serializable
    private data class PersistedData(
        val conversations: List<Conversation> = emptyList(),
        val messages: Map<String, List<StoredMessage>> = emptyMap(),
        val currentConversationId: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var data: PersistedData = load()

    // 先确保有默认对话（不引用任何 StateFlow）
    private val initialConversationId: String = run {
        if (data.conversations.isEmpty()) {
            val conv = Conversation(id = generateId(), title = "")
            data = data.copy(
                conversations = listOf(conv),
                currentConversationId = conv.id,
            )
            save()
            conv.id
        } else {
            data.currentConversationId.ifBlank { data.conversations.last().id }
        }
    }

    // 当前对话 ID
    private val _currentConversationId = MutableStateFlow(initialConversationId)
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    // 对话列表
    private val _conversations = MutableStateFlow(data.conversations)
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /** 是否从 LLM 历史中过滤指令消息 */
    var filterCommandFromHistory: Boolean = true

    /** 获取当前对话的历史消息（转换为 ChatMessage） */
    fun getHistory(maxHistory: Int = 50): List<ChatMessage> {
        val messages = data.messages[_currentConversationId.value] ?: return emptyList()
        val filtered = if (filterCommandFromHistory) {
            messages.filter { it.type != "command" }
        } else {
            messages
        }
        return filtered.takeLast(maxHistory).map { storedToChatMessage(it) }
    }

    /** 获取指定对话的所有消息 */
    fun getMessages(conversationId: String): List<StoredMessage> {
        return data.messages[conversationId] ?: emptyList()
    }

    /** 追加消息到当前对话 */
    fun addMessage(message: ChatMessage) {
        val convId = _currentConversationId.value
        val stored = chatMessageToStored(message)
        val currentMessages = data.messages[convId]?.toMutableList() ?: mutableListOf()
        currentMessages.add(stored)
        data = data.copy(messages = data.messages + (convId to currentMessages))

        // 自动设置对话标题（第一条用户消息）
        if (message.role == "user" && !message.isCommand) {
            val conv = data.conversations.find { it.id == convId }
            if (conv != null && conv.title.isBlank()) {
                val title = message.content.take(50) + if (message.content.length > 50) "..." else ""
                updateConversationTitle(convId, title)
            }
        }

        // 更新对话时间戳
        data = data.copy(
            conversations = data.conversations.map {
                if (it.id == convId) it.copy(updatedAt = currentTimeMillis()) else it
            }
        )
        _conversations.value = data.conversations

        save()
    }

    /** 新建对话 */
    fun newConversation(): String {
        val conv = Conversation(
            id = generateId(),
            title = "",
        )
        data = data.copy(
            conversations = data.conversations + conv,
            currentConversationId = conv.id,
        )
        _currentConversationId.value = conv.id
        _conversations.value = data.conversations
        save()
        return conv.id
    }

    /** 切换到指定对话 */
    fun switchConversation(conversationId: String): Boolean {
        val exists = data.conversations.any { it.id == conversationId }
        if (!exists) return false
        data = data.copy(currentConversationId = conversationId)
        _currentConversationId.value = conversationId
        save()
        return true
    }

    /** 删除对话 */
    fun deleteConversation(conversationId: String) {
        data = data.copy(
            conversations = data.conversations.filter { it.id != conversationId },
            messages = data.messages - conversationId,
        )

        // 如果删了当前对话，切换到新对话
        if (_currentConversationId.value == conversationId) {
            val nextId = data.conversations.lastOrNull()?.id ?: newConversation()
            data = data.copy(currentConversationId = nextId)
            _currentConversationId.value = nextId
        }

        _conversations.value = data.conversations
        save()
    }

    /** 清除当前对话历史 */
    fun clearCurrentHistory() {
        val convId = _currentConversationId.value
        data = data.copy(messages = data.messages + (convId to emptyList()))
        save()
    }

    // ==================== 内部方法 ====================

    private fun updateConversationTitle(convId: String, title: String) {
        data = data.copy(
            conversations = data.conversations.map {
                if (it.id == convId) it.copy(title = title) else it
            }
        )
        _conversations.value = data.conversations
    }

    private fun ensureDefaultConversation(): String {
        if (data.conversations.isEmpty()) {
            val conv = Conversation(id = generateId(), title = "")
            data = data.copy(
                conversations = listOf(conv),
                currentConversationId = conv.id,
            )
            save()
            _currentConversationId.value = conv.id
            _conversations.value = data.conversations
            return conv.id
        }
        val id = data.conversations.last().id
        return id
    }

    private fun load(): PersistedData {
        return try {
            val raw = storage.loadConversations()
            if (raw.isNullOrBlank()) PersistedData() else json.decodeFromString(raw)
        } catch (_: Exception) {
            PersistedData()
        }
    }

    private fun save() {
        try {
            storage.saveConversations(json.encodeToString(data))
        } catch (_: Exception) {
            // 静默失败
        }
    }

    private fun storedToChatMessage(stored: StoredMessage): ChatMessage {
        val msg = ChatMessage(role = stored.role, content = stored.content)
        if (stored.type == "command") return msg.copy(isCommand = true)
        try {
            val extra = json.decodeFromString<Map<String, JsonElement>>(stored.extra)
            // 恢复 toolCalls (assistant 消息)
            val toolCallsJson = extra["toolCalls"]
            if (toolCallsJson != null) {
                return msg.copy(
                    toolCalls = json.decodeFromJsonElement(
                        ListSerializer(ToolCallInfo.serializer()),
                        toolCallsJson
                    )
                )
            }
            // 恢复 toolCallId (tool 消息)
            val toolCallId = extra["toolCallId"]
            if (toolCallId != null) {
                return msg.copy(
                    toolCallId = toolCallId.toString().trim('"'),
                    toolName = extra["toolName"]?.toString()?.trim('"'),
                )
            }
        } catch (_: Exception) {}
        return msg
    }

    private fun chatMessageToStored(msg: ChatMessage): StoredMessage {
        var type = "text"
        val extraMap = mutableMapOf<String, JsonElement>()

        if (msg.isCommand) {
            type = "command"
        } else if (msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
            type = "tool_call"
            extraMap["toolCalls"] = json.encodeToJsonElement(
                ListSerializer(ToolCallInfo.serializer()),
                msg.toolCalls
            )
        } else if (msg.toolCallId != null) {
            type = "tool_result"
            extraMap["toolCallId"] = JsonPrimitive(msg.toolCallId)
            msg.toolName?.let { extraMap["toolName"] = JsonPrimitive(it) }
        } else if (msg.role == "system") {
            type = "system"
        }

        return StoredMessage(
            role = msg.role,
            content = msg.content,
            type = type,
            extra = if (extraMap.isEmpty()) "{}" else json.encodeToString(
                MapSerializer(String.serializer(), JsonElement.serializer()),
                extraMap
            ),
        )
    }

    companion object {
        private fun generateId(): String = "conv_${currentTimeMillis()}_${Random.nextInt(1000, 10000)}"
    }
}
