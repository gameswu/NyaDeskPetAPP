package com.gameswu.nyadeskpet.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gameswu.nyadeskpet.agent.*
import com.gameswu.nyadeskpet.data.ConversationManager
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.dialogue.DialogueManager
import com.gameswu.nyadeskpet.live2d.Live2DController
import com.gameswu.nyadeskpet.audio.AudioStreamPlayer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val reasoning: String? = null,
    val attachment: Attachment? = null,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val subtitleText: String = "",
    val isSubtitleVisible: Boolean = false,
    val inputText: String = "",
    val streamingText: String = "",
    val isStreaming: Boolean = false,
    val toolConfirm: ToolConfirmData? = null,
    val commands: List<String> = emptyList(),
    val commandDefinitions: List<CommandDefinition> = emptyList(),
    val conversations: List<ConversationManager.Conversation> = emptyList(),
    val currentConversationId: String = "",
    val showConversationList: Boolean = false,
)

class ChatViewModel(
    private val agentClient: AgentClient,
    private val settingsRepo: SettingsRepository,
    private val live2dController: Live2DController,
    private val audioPlayer: AudioStreamPlayer,
    private val dialogueManager: DialogueManager,
    private val builtinAgentService: BuiltinAgentService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            agentClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        viewModelScope.launch {
            agentClient.dialogueEvents.collect { event ->
                handleDialogueEvent(event)
            }
        }

        viewModelScope.launch {
            agentClient.live2dCommands.collect { cmd ->
                live2dController.handleCommand(cmd)
            }
        }

        viewModelScope.launch {
            agentClient.syncCommands.collect { cmd ->
                live2dController.executeSyncCommand(cmd)
            }
        }

        viewModelScope.launch {
            agentClient.audioEvents.collect { event ->
                handleAudioEvent(event)
            }
        }

        viewModelScope.launch {
            agentClient.toolConfirms.collect { data ->
                _uiState.update { it.copy(toolConfirm = data) }
            }
        }

        viewModelScope.launch {
            agentClient.commandRegistrations.collect { commands ->
                _uiState.update { it.copy(
                    commands = commands.map { c -> c.name },
                    commandDefinitions = commands
                ) }
            }
        }

        viewModelScope.launch {
            agentClient.commandResponses.collect { resp ->
                val prefix = "/${resp.command}"
                val text = if (resp.success) "$prefix\n${resp.text ?: ""}" else "$prefix\n❌ ${resp.error ?: "Failed"}"
                appendMessage(ChatMessage(text = text, isUser = false))
            }
        }

        // 仅在 custom 模式下由 ViewModel 触发 autoConnect
        // builtin 模式已在 setupWiring() 中自动连接
        if (settingsRepo.current.autoConnect && settingsRepo.current.backendMode != "builtin") {
            connect()
        }

        // 观察对话列表变化
        viewModelScope.launch {
            builtinAgentService.conversationList.collect { convList ->
                _uiState.update { it.copy(conversations = convList) }
            }
        }
        viewModelScope.launch {
            builtinAgentService.currentConversationId.collect { convId ->
                _uiState.update { it.copy(currentConversationId = convId) }
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage(text: String = _uiState.value.inputText) {
        val message = text.trim()
        if (message.isBlank()) return

        _uiState.update { it.copy(inputText = "") }

        // 命令处理
        if (message.startsWith("/")) {
            val parts = message.removePrefix("/").split(" ", limit = 2)
            val cmdName = parts[0]
            val cmdArgs = parts.getOrNull(1)?.trim() ?: ""
            appendMessage(ChatMessage(text = message, isUser = true))
            viewModelScope.launch {
                agentClient.sendCommand(cmdName, cmdArgs)
            }
            return
        }

        appendMessage(ChatMessage(text = message, isUser = true))
        viewModelScope.launch {
            agentClient.sendUserInput(message)
        }
    }

    fun respondToolConfirm(confirmId: String, approved: Boolean) {
        _uiState.update { it.copy(toolConfirm = null) }
        viewModelScope.launch {
            agentClient.sendToolConfirmResponse(confirmId, approved)
        }
    }

    /**
     * 发送带附件的消息（文件/图片）。
     */
    fun sendMessageWithAttachment(text: String, attachment: Attachment) {
        appendMessage(ChatMessage(
            text = text,
            isUser = true,
            attachment = attachment,
        ))
        viewModelScope.launch {
            agentClient.sendUserInput(text, attachment)
        }
    }

    fun connect() = agentClient.connect(settingsRepo.current.wsUrl)
    fun disconnect() = agentClient.disconnect()

    fun hideSubtitle() {
        _uiState.update { it.copy(isSubtitleVisible = false) }
    }

    // ==================== 对话管理 ====================

    fun toggleConversationList() {
        _uiState.update { it.copy(showConversationList = !it.showConversationList) }
    }

    fun hideConversationList() {
        _uiState.update { it.copy(showConversationList = false) }
    }

    fun newConversation() {
        builtinAgentService.newConversation()
        _uiState.update { it.copy(messages = emptyList(), showConversationList = false) }
    }

    fun switchConversation(conversationId: String) {
        if (builtinAgentService.switchConversation(conversationId)) {
            _uiState.update { it.copy(messages = emptyList(), showConversationList = false) }
        }
    }

    fun deleteConversation(conversationId: String) {
        builtinAgentService.deleteConversation(conversationId)
        _uiState.update { it.copy(messages = emptyList()) }
    }

    private fun handleDialogueEvent(event: DialogueEvent) {
        when (event) {
            is DialogueEvent.Complete -> {
                appendMessage(ChatMessage(
                    text = event.data.text,
                    isUser = false,
                    reasoning = event.data.reasoningContent
                ))
                val duration = event.data.duration ?: 5000L
                showSubtitle(event.data.text, duration)
                dialogueManager.showDialogue(event.data.text, durationMs = duration)
            }
            is DialogueEvent.StreamStart -> {
                _uiState.update { it.copy(isStreaming = true, streamingText = "") }
                dialogueManager.showDialogue("...", durationMs = 0, typewriter = false)
            }
            is DialogueEvent.StreamChunk -> {
                _uiState.update { old -> old.copy(streamingText = old.streamingText + event.delta) }
                dialogueManager.appendText(event.delta)
            }
            is DialogueEvent.StreamEnd -> {
                _uiState.update { it.copy(isStreaming = false, streamingText = "") }
                appendMessage(ChatMessage(
                    text = event.fullText,
                    isUser = false,
                    reasoning = event.reasoning
                ))
                val duration = event.duration ?: 5000L
                showSubtitle(event.fullText, duration)
                dialogueManager.showDialogue(event.fullText, durationMs = duration, typewriter = false)
            }
        }
    }

    private fun handleAudioEvent(event: AudioEvent) {
        when (event) {
            is AudioEvent.Start -> {
                audioPlayer.setVolume(settingsRepo.current.volume)
                audioPlayer.startStream(event.data.mimeType)
                event.data.text?.let { showSubtitle(it, event.data.totalDuration?.toLong() ?: 5000L) }
            }
            is AudioEvent.Chunk -> audioPlayer.appendChunk(event.data.chunk)
            is AudioEvent.End -> audioPlayer.endStream()
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        _uiState.update { old -> old.copy(messages = old.messages + msg) }
    }

    private fun showSubtitle(text: String, durationMs: Long) {
        if (!settingsRepo.current.showSubtitle) return
        _uiState.update { it.copy(subtitleText = text, isSubtitleVisible = true) }
    }

    override fun onCleared() {
        super.onCleared()
        // 内置模式下不断开连接（连接由 App 级管理）
        if (settingsRepo.current.backendMode != "builtin") {
            disconnect()
        }
    }
}
