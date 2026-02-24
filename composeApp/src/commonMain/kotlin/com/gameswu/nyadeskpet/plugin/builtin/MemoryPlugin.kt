package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.agent.provider.ChatMessage
import com.gameswu.nyadeskpet.agent.provider.LLMRequest
import com.gameswu.nyadeskpet.plugin.*
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolProvider
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import kotlinx.serialization.json.*

/**
 * 记忆管理插件 — 对齐原项目 agent-plugins/memory
 *
 * 提供会话分离的上下文管理和自动压缩汇总功能：
 * 1. 上下文窗口管理 — 跟踪每个会话的消息数量和 token 估算
 * 2. 自动压缩汇总 — 当上下文超过阈值时，调用 LLM 将早期历史压缩为摘要
 * 3. 摘要持久化 — 压缩后的摘要作为系统消息注入上下文
 *
 * 注册工具：
 * - clear_memory: 清除当前会话的记忆摘要
 * - view_memory_stats: 查看记忆统计信息
 *
 * 暴露服务 API：
 * - buildContextMessages(sessionId, history): 构建含摘要的上下文消息列表
 */
class MemoryPlugin : Plugin, ToolProvider {

    override val manifest = PluginManifest(
        id = "builtin.memory",
        name = "记忆管理",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "管理对话记忆，提供自动压缩汇总和上下文窗口管理",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.TOOL),
        dependencies = listOf("builtin.personality"),
        autoActivate = true,
    )
    override var enabled: Boolean = true

    override val providerId: String = "builtin.memory"
    override val providerName: String = "记忆管理"

    // ==================== 配置 Schema ====================

    override val configSchema = PluginConfigSchema(
        fields = listOf(
            ConfigFieldDef(
                key = "recentMessageCount",
                type = ConfigFieldType.INT,
                description = "保留的近期消息数量。数字越大，AI 记住的近期对话越多，但消耗的 token 也越多。",
                default = JsonPrimitive(10),
            ),
            ConfigFieldDef(
                key = "compressionThreshold",
                type = ConfigFieldType.INT,
                description = "触发自动压缩的消息总数阈值。当会话消息数超过此值时，会自动将早期历史压缩为摘要。",
                default = JsonPrimitive(20),
            ),
            ConfigFieldDef(
                key = "maxTokenEstimate",
                type = ConfigFieldType.INT,
                description = "最大 token 估算值。当上下文预估 token 数超过此值时，也会触发压缩。",
                default = JsonPrimitive(4000),
            ),
            ConfigFieldDef(
                key = "compressionMaxTokens",
                type = ConfigFieldType.INT,
                description = "压缩摘要时 LLM 回复的最大 token 数。控制摘要的详细程度。",
                default = JsonPrimitive(500),
            ),
        ),
    )

    // ==================== 配置状态 ====================

    private var ctx: PluginContext? = null

    private var recentMessageCount: Int = 10
    private var compressionThreshold: Int = 20
    private var maxTokenEstimate: Int = 4000
    private var compressionMaxTokens: Int = 500

    private val compressionPrompt = """请将以下对话历史压缩为一段简洁的摘要，保留关键信息（用户偏好、重要事实、对话主题等），忽略闲聊和重复内容。摘要应使用第三人称描述，字数控制在 300 字以内。

对话历史：
{history}

请输出摘要："""

    private val compressionSystemPrompt = "你是一个对话摘要助手，请简洁准确地总结对话内容。"

    // ==================== 会话记忆 ====================

    /** 会话记忆状态 */
    data class SessionMemory(
        var summary: String? = null,
        var lastCompressionAt: Int = 0,
        var compressionCount: Int = 0,
    )

    private val sessionMemories = mutableMapOf<String, SessionMemory>()

    // ==================== 生命周期 ====================

    override fun onLoad(context: PluginContext) {
        ctx = context
        loadConfig(context.getConfig())
        context.logInfo("记忆管理插件已初始化")
    }

    override fun onUnload() {
        sessionMemories.clear()
        ctx = null
    }

    override fun onConfigChanged(config: Map<String, JsonElement>) {
        loadConfig(config)
    }

    private fun loadConfig(config: Map<String, JsonElement>) {
        config["recentMessageCount"]?.jsonPrimitive?.intOrNull?.let { recentMessageCount = it }
        config["compressionThreshold"]?.jsonPrimitive?.intOrNull?.let { compressionThreshold = it }
        config["maxTokenEstimate"]?.jsonPrimitive?.intOrNull?.let { maxTokenEstimate = it }
        config["compressionMaxTokens"]?.jsonPrimitive?.intOrNull?.let { compressionMaxTokens = it }
    }

    // ==================== 工具 ====================

    override fun getTools(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "clear_memory",
                description = "清除当前会话的历史摘要记忆。清除后，AI 将忘记之前的对话总结。",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("sessionId") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("要清除的会话 ID（可选，默认当前会话）"))
                        }
                    }
                },
            ),
            ToolDefinition(
                name = "view_memory_stats",
                description = "查看记忆管理的统计信息，包括活跃会话数和压缩次数。",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {}
                },
            ),
        )
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        return when (name) {
            "clear_memory" -> {
                val sid = arguments["sessionId"]?.jsonPrimitive?.contentOrNull ?: "default"
                clearSessionMemory(sid)
                ToolResult(
                    success = true,
                    result = JsonPrimitive("会话 $sid 的记忆摘要已清除"),
                )
            }
            "view_memory_stats" -> {
                val stats = getStats()
                ToolResult(
                    success = true,
                    result = JsonPrimitive("记忆统计: ${stats.first} 个活跃会话, 共 ${stats.second} 次压缩"),
                )
            }
            else -> ToolResult(success = false, error = "Unknown tool: $name")
        }
    }

    // ==================== 服务 API ====================

    /**
     * 构建发送给 LLM 的消息列表。
     *
     * 自动检测是否需要压缩，如需则调用 LLM 将早期历史压缩为摘要。
     * 返回的消息列表包含：[历史摘要（如有）] + [近期消息]
     *
     * @param sessionId 会话 ID
     * @param fullHistory 完整的对话历史
     * @return 带摘要的消息列表（role + content 对）
     */
    suspend fun buildContextMessages(
        sessionId: String,
        fullHistory: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        val memory = getSessionMemory(sessionId)
        val totalMessages = fullHistory.size

        // 检查是否需要压缩
        if (shouldCompress(totalMessages, fullHistory, memory)) {
            compressHistory(sessionId, fullHistory, memory)
        }

        val messages = mutableListOf<Pair<String, String>>()

        // 注入历史摘要
        memory.summary?.let {
            messages.add("system" to "[对话历史摘要]\n$it")
        }

        // 取近期消息
        if (totalMessages <= recentMessageCount) {
            messages.addAll(fullHistory)
        } else {
            messages.addAll(fullHistory.takeLast(recentMessageCount))
        }

        return messages
    }

    /** 清除会话记忆 */
    fun clearSessionMemory(sessionId: String) {
        sessionMemories.remove(sessionId)
    }

    /** 获取会话摘要 */
    fun getSessionSummary(sessionId: String): String? {
        return sessionMemories[sessionId]?.summary
    }

    /** 手动设置会话摘要 */
    fun setSessionSummary(sessionId: String, summary: String) {
        getSessionMemory(sessionId).summary = summary
    }

    /** 获取记忆统计 (sessions, totalCompressions) */
    fun getStats(): Pair<Int, Int> {
        val totalCompressions = sessionMemories.values.sumOf { it.compressionCount }
        return sessionMemories.size to totalCompressions
    }

    // ==================== 内部方法 ====================

    private fun getSessionMemory(sessionId: String): SessionMemory {
        return sessionMemories.getOrPut(sessionId) { SessionMemory() }
    }

    private fun shouldCompress(
        totalMessages: Int,
        history: List<Pair<String, String>>,
        memory: SessionMemory,
    ): Boolean {
        // 条件 1: 总消息数超过阈值，且自上次压缩后有足够新消息
        if (totalMessages > compressionThreshold) {
            val newMessagesSinceCompression = totalMessages - memory.lastCompressionAt
            if (newMessagesSinceCompression >= recentMessageCount) return true
        }
        // 条件 2: 预估 token 超过上限
        val estimatedTokens = estimateTokens(history)
        if (estimatedTokens > maxTokenEstimate) return true

        return false
    }

    private fun estimateTokens(messages: List<Pair<String, String>>): Int {
        val totalChars = messages.sumOf { it.second.length }
        return (totalChars * 1.5).toInt()
    }

    private suspend fun compressHistory(
        sessionId: String,
        fullHistory: List<Pair<String, String>>,
        memory: SessionMemory,
    ) {
        val context = ctx ?: return
        try {
            // 取需要压缩的早期消息（排除近期消息）
            val messagesToCompress = if (fullHistory.size > recentMessageCount) {
                fullHistory.dropLast(recentMessageCount)
            } else return

            if (messagesToCompress.isEmpty()) return

            val historyText = messagesToCompress.joinToString("\n") { "[${it.first}]: ${it.second}" }

            val compressionInput = if (memory.summary != null) {
                "[之前的对话摘要]: ${memory.summary}\n\n[新增的对话内容]:\n$historyText"
            } else historyText

            val prompt = compressionPrompt.replace("{history}", compressionInput)

            val request = LLMRequest(
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                systemPrompt = compressionSystemPrompt,
                maxTokens = compressionMaxTokens,
            )

            val response = context.callProvider("primary", request)

            if (response.text.isNotBlank()) {
                memory.summary = response.text
                memory.lastCompressionAt = fullHistory.size
                memory.compressionCount++
                context.logInfo("会话 $sessionId 完成第 ${memory.compressionCount} 次压缩，摘要 ${response.text.length} 字")
            }
        } catch (e: Exception) {
            context.logWarn("压缩失败: ${e.message}")
        }
    }
}
