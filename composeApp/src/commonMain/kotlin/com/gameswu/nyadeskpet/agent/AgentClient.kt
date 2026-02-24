package com.gameswu.nyadeskpet.agent

import com.gameswu.nyadeskpet.data.SettingsRepository
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

class AgentClient(
    private val responseController: ResponseController,
    private val settingsRepo: SettingsRepository,
    private val builtinAgentService: BuiltinAgentService,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: WebSocketSession? = null
    private var reconnectJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 当前是否为内置模式 */
    val isBuiltinMode: Boolean get() = settingsRepo.current.backendMode == "builtin"

    // ----- Flows -----
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _dialogueEvents = MutableSharedFlow<DialogueEvent>()
    val dialogueEvents: SharedFlow<DialogueEvent> = _dialogueEvents.asSharedFlow()

    private val _live2dCommands = MutableSharedFlow<Live2DCommandData>()
    val live2dCommands: SharedFlow<Live2DCommandData> = _live2dCommands.asSharedFlow()

    private val _syncCommands = MutableSharedFlow<SyncCommandData>()
    val syncCommands: SharedFlow<SyncCommandData> = _syncCommands.asSharedFlow()

    private val _audioEvents = MutableSharedFlow<AudioEvent>()
    val audioEvents: SharedFlow<AudioEvent> = _audioEvents.asSharedFlow()

    private val _toolConfirms = MutableSharedFlow<ToolConfirmData>()
    val toolConfirms: SharedFlow<ToolConfirmData> = _toolConfirms.asSharedFlow()

    private val _toolStatusEvents = MutableSharedFlow<ToolStatusData>()
    val toolStatusEvents: SharedFlow<ToolStatusData> = _toolStatusEvents.asSharedFlow()

    private val _commandRegistrations = MutableStateFlow<List<CommandDefinition>>(emptyList())
    val commandRegistrations: StateFlow<List<CommandDefinition>> = _commandRegistrations.asStateFlow()

    private val _commandResponses = MutableSharedFlow<CommandResponseData>()
    val commandResponses: SharedFlow<CommandResponseData> = _commandResponses.asSharedFlow()

    private val _pluginInvokes = MutableSharedFlow<PluginInvokeData>()
    val pluginInvokes: SharedFlow<PluginInvokeData> = _pluginInvokes.asSharedFlow()

    private val _systemMessages = MutableSharedFlow<String>()
    val systemMessages: SharedFlow<String> = _systemMessages.asSharedFlow()

    private var currentStreamId: String? = null
    private val streamAccumulated = StringBuilder()
    private val streamReasoningAccumulated = StringBuilder()

    // 连接后自动发送的回调（由外部设置）
    var onConnectedCallback: (suspend () -> Unit)? = null

    private val client = HttpClient {
        install(WebSockets) {
            pingInterval = 30.seconds
        }
    }

    // ===================== Connection =====================

    fun connect(wsUrl: String) {
        if (isBuiltinMode) {
            if (_connectionState.value == ConnectionState.CONNECTED) return
            connectBuiltin()
            return
        }

        if (_connectionState.value == ConnectionState.CONNECTING) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive) {
                _connectionState.value = ConnectionState.CONNECTING
                try {
                    client.webSocket(wsUrl) {
                        session = this
                        _connectionState.value = ConnectionState.CONNECTED
                        // 连接建立后自动发送 character_info + model_info
                        onConnectedCallback?.invoke()
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleMessage(frame.readText())
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 连接失败或断开，将自动重连
                } finally {
                    session = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                delay(5000)
            }
        }
    }

    /** 内置模式连接 — 无需 WebSocket，直接就绪 */
    private fun connectBuiltin() {
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.CONNECTED
        // 注册内置命令
        _commandRegistrations.value = builtinAgentService.getCommandDefinitions()
        scope.launch {
            _systemMessages.emit("内置 Agent 已就绪")
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        if (isBuiltinMode) {
            _connectionState.value = ConnectionState.DISCONNECTED
        } else {
            scope.launch { session?.close() }
        }
    }

    // ===================== Send (Frontend → Backend) =====================

    suspend fun sendMessage(message: BackendMessage) {
        val text = json.encodeToString(BackendMessage.serializer(), message)
        session?.send(Frame.Text(text))
    }

    /** 发送用户文本输入（可选附件） */
    suspend fun sendUserInput(text: String, attachment: Attachment? = null) {
        if (isBuiltinMode) {
            builtinAgentService.handleUserInput(text, attachment, ::dispatchAgentEvent)
            return
        }
        sendMessage(BackendMessage(
            type = "user_input",
            text = text,
            timestamp = com.gameswu.nyadeskpet.currentTimeMillis(),
            attachment = attachment
        ))
    }

    /** 发送模型元信息（模型加载后 + WS连接后） */
    suspend fun sendModelInfo(modelInfo: ModelInfo) {
        if (isBuiltinMode) {
            builtinAgentService.processModelInfo(modelInfo)
            return
        }
        sendMessage(BackendMessage(
            type = "model_info",
            data = json.encodeToJsonElement(ModelInfo.serializer(), modelInfo),
            timestamp = com.gameswu.nyadeskpet.currentTimeMillis()
        ))
    }

    /** 发送角色人设信息（WS连接后）。内置模式下直接从 settingsRepo 读取，无需发送。 */
    suspend fun sendCharacterInfo(info: CharacterInfo) {
        if (isBuiltinMode) {
            // 内置模式下角色信息由 settingsRepo 直接提供给 BuiltinAgentService，无需额外传递
            return
        }
        sendMessage(BackendMessage(
            type = "character_info",
            data = json.encodeToJsonElement(CharacterInfo.serializer(), info),
            timestamp = com.gameswu.nyadeskpet.currentTimeMillis()
        ))
    }

    /** 发送触碰事件 */
    suspend fun sendTapEvent(hitArea: String, x: Float, y: Float) {
        if (isBuiltinMode) {
            builtinAgentService.handleTapEvent(hitArea, ::dispatchAgentEvent)
            return
        }
        val data = TapEventData(
            hitArea = hitArea,
            position = TapPosition(x, y),
            timestamp = com.gameswu.nyadeskpet.currentTimeMillis()
        )
        sendMessage(BackendMessage(
            type = "tap_event",
            data = json.encodeToJsonElement(TapEventData.serializer(), data)
        ))
    }

    /** 发送文件上传 */
    suspend fun sendFileUpload(fileName: String, fileType: String, fileSize: Long, fileDataBase64: String) {
        val data = FileUploadData(
            fileName = fileName,
            fileType = fileType,
            fileSize = fileSize,
            fileData = fileDataBase64,
            timestamp = com.gameswu.nyadeskpet.currentTimeMillis()
        )
        sendMessage(BackendMessage(
            type = "file_upload",
            data = json.encodeToJsonElement(FileUploadData.serializer(), data)
        ))
    }

    /** 发送工具确认响应 */
    suspend fun sendToolConfirmResponse(confirmId: String, approved: Boolean, remember: Boolean? = null) {
        sendMessage(BackendMessage(
            type = "tool_confirm_response",
            data = json.encodeToJsonElement(
                ToolConfirmResponseData.serializer(),
                ToolConfirmResponseData(confirmId, approved, remember)
            )
        ))
    }

    /** 发送命令执行请求 */
    suspend fun sendCommand(name: String, args: String = "") {
        if (isBuiltinMode) {
            builtinAgentService.handleCommand(name, args, ::dispatchAgentEvent)
            return
        }
        sendMessage(BackendMessage(
            type = "command_execute",
            text = name,
            data = json.parseToJsonElement("""{"command":"$name","args":"$args"}""")
        ))
    }

    /** 发送插件状态 */
    suspend fun sendPluginStatus(plugins: List<PluginStatusData.PluginEntry>) {
        sendMessage(BackendMessage(
            type = "plugin_status",
            data = json.encodeToJsonElement(
                PluginStatusData.serializer(),
                PluginStatusData(plugins)
            )
        ))
    }

    /** 发送插件响应（对 plugin_invoke 的回复） */
    suspend fun sendPluginResponse(data: PluginResponseData) {
        sendMessage(BackendMessage(
            type = "plugin_response",
            data = json.encodeToJsonElement(PluginResponseData.serializer(), data)
        ))
    }

    // ===================== Builtin Agent 事件分发 =====================

    /**
     * 将内置 Agent 产生的事件分发到对应的 SharedFlow
     * 使得 ChatViewModel/Live2DController 等下游消费者无感知差异
     */
    private suspend fun dispatchAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Dialogue -> _dialogueEvents.emit(event.event)
            is AgentEvent.Live2D -> _live2dCommands.emit(event.cmd)
            is AgentEvent.SyncCommand -> _syncCommands.emit(event.data)
            is AgentEvent.Audio -> _audioEvents.emit(event.event)
            is AgentEvent.Command -> _commandResponses.emit(event.resp)
            is AgentEvent.System -> _systemMessages.emit(event.text)
        }
    }

    // ===================== Receive (Backend → Frontend) =====================

    /**
     * 可中断消息类型 — 这些消息需要经过 ResponseController 优先级过滤
     */
    private val interruptibleTypes = setOf(
        "dialogue", "dialogue_stream_start", "audio_stream_start", "sync_command", "live2d"
    )

    private suspend fun handleMessage(raw: String) {
        val message = json.decodeFromString(BackendMessage.serializer(), raw)
        val responseId = message.responseId
        val priority = message.priority ?: 0

        // ResponseController 优先级过滤
        if (responseId != null && message.type in interruptibleTypes) {
            if (!responseController.shouldAccept(responseId, priority)) {
                return // 低优先级消息被丢弃
            }
        }

        when (message.type) {
            // ----- 对话 -----
            "dialogue" -> {
                val data = json.decodeFromJsonElement(DialogueData.serializer(), message.data!!)
                _dialogueEvents.emit(DialogueEvent.Complete(data))
                responseId?.let { responseController.notifyComplete(it) }
            }
            "dialogue_stream_start" -> {
                val data = json.decodeFromJsonElement(DialogueStreamStartData.serializer(), message.data!!)
                currentStreamId = data.streamId
                streamAccumulated.clear()
                streamReasoningAccumulated.clear()
                _dialogueEvents.emit(DialogueEvent.StreamStart(data.streamId))
            }
            "dialogue_stream_chunk" -> {
                val data = json.decodeFromJsonElement(DialogueStreamChunkData.serializer(), message.data!!)
                if (data.streamId == currentStreamId) {
                    data.delta?.let { streamAccumulated.append(it) }
                    data.reasoningDelta?.let { streamReasoningAccumulated.append(it) }
                    _dialogueEvents.emit(DialogueEvent.StreamChunk(data.delta ?: "", data.reasoningDelta))
                }
            }
            "dialogue_stream_end" -> {
                val data = json.decodeFromJsonElement(DialogueStreamEndData.serializer(), message.data!!)
                if (data.streamId == currentStreamId) {
                    currentStreamId = null
                    val fullText = data.fullText ?: streamAccumulated.toString()
                    val reasoning = streamReasoningAccumulated.toString().takeIf { it.isNotEmpty() }
                    _dialogueEvents.emit(DialogueEvent.StreamEnd(fullText, reasoning, data.duration))
                    responseId?.let { responseController.notifyComplete(it) }
                }
            }

            // ----- Live2D -----
            "live2d" -> {
                val data = json.decodeFromJsonElement(Live2DCommandData.serializer(), message.data!!)
                _live2dCommands.emit(data)
            }
            "sync_command" -> {
                val data = json.decodeFromJsonElement(SyncCommandData.serializer(), message.data!!)
                _syncCommands.emit(data)
            }

            // ----- 音频 -----
            "audio_stream_start" -> {
                val data = json.decodeFromJsonElement(AudioStreamStartData.serializer(), message.data!!)
                responseId?.let { responseController.markAudioActive() }
                _audioEvents.emit(AudioEvent.Start(data))
            }
            "audio_chunk" -> {
                val data = json.decodeFromJsonElement(AudioChunkData.serializer(), message.data!!)
                _audioEvents.emit(AudioEvent.Chunk(data))
            }
            "audio_stream_end" -> {
                _audioEvents.emit(AudioEvent.End)
                responseId?.let { responseController.notifyComplete(it) }
            }

            // ----- 工具 -----
            "tool_confirm" -> {
                val data = json.decodeFromJsonElement(ToolConfirmData.serializer(), message.data!!)
                _toolConfirms.emit(data)
            }
            "tool_status" -> {
                val data = json.decodeFromJsonElement(ToolStatusData.serializer(), message.data!!)
                _toolStatusEvents.emit(data)
            }

            // ----- 指令 -----
            "commands_register" -> {
                val data = json.decodeFromJsonElement(CommandsRegisterData.serializer(), message.data!!)
                _commandRegistrations.value = data.commands
            }
            "command_response" -> {
                val data = json.decodeFromJsonElement(CommandResponseData.serializer(), message.data!!)
                _commandResponses.emit(data)
            }

            // ----- 插件 -----
            "plugin_invoke" -> {
                val data = json.decodeFromJsonElement(PluginInvokeData.serializer(), message.data!!)
                _pluginInvokes.emit(data)
            }

            // ----- 系统 -----
            "system" -> {
                val text = message.text ?: message.data?.toString() ?: ""
                _systemMessages.emit(text)
            }
        }
    }
}

sealed class DialogueEvent {
    data class Complete(val data: DialogueData) : DialogueEvent()
    data class StreamStart(val streamId: String) : DialogueEvent()
    data class StreamChunk(val delta: String, val reasoningDelta: String?) : DialogueEvent()
    data class StreamEnd(val fullText: String, val reasoning: String?, val duration: Long?) : DialogueEvent()
}

sealed class AudioEvent {
    data class Start(val data: AudioStreamStartData) : AudioEvent()
    data class Chunk(val data: AudioChunkData) : AudioEvent()
    data object End : AudioEvent()
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }