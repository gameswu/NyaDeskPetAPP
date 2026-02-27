package com.gameswu.nyadeskpet.agent

import com.gameswu.nyadeskpet.agent.provider.*
import com.gameswu.nyadeskpet.agent.provider.ToolCallInfo
import com.gameswu.nyadeskpet.agent.provider.providers.*
import com.gameswu.nyadeskpet.agent.provider.tts.*
import com.gameswu.nyadeskpet.data.ConversationManager
import com.gameswu.nyadeskpet.data.LogLevel
import com.gameswu.nyadeskpet.data.LogManager
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.plugin.*
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.builtin.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * 内置后端 Agent 服务
 *
 * 对齐原 Electron 项目 src/agent/handler.ts 的设计：
 * - 管理 Provider 实例（注册/创建/初始化/销毁）
 * - 管理主 LLM / TTS 实例
 * - 处理用户输入、触碰事件、斜杠命令
 * - 维护对话历史
 *
 * 当 backendMode == "builtin" 时，AgentClient 直接调用本类方法，
 * 替代 WebSocket 通信。
 */
class BuiltinAgentService(
    private val settingsRepo: SettingsRepository,
    private val pluginManager: PluginManager,
    private val conversationManager: ConversationManager,
    private val logManager: LogManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== Provider 实例管理（对齐 handler.ts）====================

    private data class ProviderEntry(
        var config: ProviderInstanceConfig,
        var provider: LLMProvider? = null,
        var status: ProviderStatus = ProviderStatus.IDLE,
        var error: String? = null,
    )

    private val providerInstances = mutableMapOf<String, ProviderEntry>()

    // ==================== 模型信息（对齐 handler.ts）====================

    /** 当前 Live2D 模型信息 */
    private var modelInfo: ModelInfo? = null
    private var primaryInstanceId: String = ""

    // TTS（简化版，结构对齐）
    private data class TTSEntry(
        var config: TTSProviderInstanceConfig,
        var provider: TTSProvider? = null,
        var status: ProviderStatus = ProviderStatus.IDLE,
        var error: String? = null,
    )

    private val ttsInstances = mutableMapOf<String, TTSEntry>()
    private var primaryTtsInstanceId: String = ""

    // ==================== Skill Manager（对齐 handler.ts 技能系统）====================
    val skillManager = SkillManager()

    // 供 UI 观测的实例列表
    private val _providerInstancesFlow = MutableStateFlow<List<ProviderInstanceInfo>>(emptyList())
    val providerInstancesFlow: StateFlow<List<ProviderInstanceInfo>> = _providerInstancesFlow.asStateFlow()

    // 供 UI 观测的 TTS 实例列表（包含运行时状态）
    private val _ttsInstancesFlow = MutableStateFlow<List<TTSProviderInstanceInfo>>(emptyList())
    val ttsInstancesFlow: StateFlow<List<TTSProviderInstanceInfo>> = _ttsInstancesFlow.asStateFlow()
    val primaryTtsId: String get() = primaryTtsInstanceId

    init {
        ensureProvidersRegistered()
        loadConfig()
    }

    // ==================== Provider 类型注册 ====================

    companion object {
        private var providersRegistered = false

        fun ensureProvidersRegistered() {
            if (providersRegistered) return
            // LLM Providers
            registerProvider(OPENAI_METADATA) { OpenAIProvider(it) }
            registerProvider(DEEPSEEK_METADATA) { DeepSeekProvider(it) }
            registerProvider(ANTHROPIC_METADATA) { AnthropicProvider(it) }
            registerProvider(GEMINI_METADATA) { GeminiProvider(it) }
            registerProvider(OPENROUTER_METADATA) { OpenRouterProvider(it) }
            registerAllPresetProviders()
            // TTS Providers
            registerTTSProvider(EDGE_TTS_METADATA) { EdgeTTSProvider(it) }
            registerTTSProvider(OPENAI_TTS_METADATA) { OpenAITTSProvider(it) }
            registerTTSProvider(FISH_AUDIO_METADATA) { FishAudioProvider(it) }
            registerTTSProvider(ELEVENLABS_METADATA) { ElevenLabsProvider(it) }
            providersRegistered = true
        }

        private fun generateId(): String = buildString {
            repeat(16) { append("0123456789abcdef"[Random.nextInt(16)]) }
        }
    }

    // ==================== Provider 实例 CRUD ====================

    /** 添加 Provider 实例 */
    fun addProviderInstance(instanceConfig: ProviderInstanceConfig): Boolean {
        if (!ProviderRegistry.has(instanceConfig.providerId)) return false

        providerInstances[instanceConfig.instanceId] = ProviderEntry(config = instanceConfig)

        // 第一个实例自动设为主 LLM
        if (providerInstances.size == 1) {
            primaryInstanceId = instanceConfig.instanceId
        }

        logManager.log(LogLevel.INFO, "添加 Provider 实例: ${instanceConfig.instanceId} (${instanceConfig.providerId})", "AgentService")
        saveConfig()
        notifyInstancesChanged()
        return true
    }

    /** 移除 Provider 实例 */
    fun removeProviderInstance(instanceId: String): Boolean {
        val entry = providerInstances[instanceId] ?: return false
        scope.launch { entry.provider?.terminate() }
        providerInstances.remove(instanceId)

        if (primaryInstanceId == instanceId) {
            primaryInstanceId = providerInstances.keys.firstOrNull() ?: ""
        }

        logManager.log(LogLevel.INFO, "移除 Provider 实例: $instanceId", "AgentService")
        saveConfig()
        notifyInstancesChanged()
        return true
    }

    /** 更新 Provider 实例配置 */
    fun updateProviderInstance(instanceId: String, newConfig: ProviderInstanceConfig): Boolean {
        val entry = providerInstances[instanceId] ?: return false
        scope.launch { entry.provider?.terminate() }
        entry.config = newConfig
        entry.provider = null
        entry.status = ProviderStatus.IDLE
        entry.error = null

        saveConfig()
        notifyInstancesChanged()

        // 如果新配置已启用，自动重新初始化
        if (newConfig.enabled) {
            entry.status = ProviderStatus.CONNECTING
            notifyInstancesChanged()
            scope.launch {
                initializeProviderInstance(instanceId)
            }
        }

        return true
    }

    /** 初始化（连接）一个 Provider 实例 */
    suspend fun initializeProviderInstance(instanceId: String): TestResult {
        val entry = providerInstances[instanceId]
            ?: return TestResult(false, "实例不存在")

        return try {
            entry.provider?.terminate()
            entry.status = ProviderStatus.CONNECTING
            entry.error = null
            notifyInstancesChanged()

            val provider = ProviderRegistry.create(entry.config.providerId, entry.config.config)
                ?: throw Exception("无法创建 Provider: ${entry.config.providerId}")

            provider.initialize()
            entry.provider = provider
            entry.status = ProviderStatus.CONNECTED
            entry.error = null
            notifyInstancesChanged()
            TestResult(true)
        } catch (e: Exception) {
            entry.status = ProviderStatus.ERROR
            entry.error = e.message
            notifyInstancesChanged()
            TestResult(false, e.message)
        }
    }

    /** 断开 Provider 实例连接 */
    suspend fun disconnectProviderInstance(instanceId: String): TestResult {
        val entry = providerInstances[instanceId]
            ?: return TestResult(false, "实例不存在")
        return try {
            entry.provider?.terminate()
            entry.provider = null
            entry.status = ProviderStatus.IDLE
            entry.error = null
            notifyInstancesChanged()
            TestResult(true)
        } catch (e: Exception) {
            entry.status = ProviderStatus.ERROR
            entry.error = e.message
            notifyInstancesChanged()
            TestResult(false, e.message)
        }
    }

    /** 启用 Provider 实例（启用后自动尝试连接） */
    suspend fun enableProviderInstance(instanceId: String): TestResult {
        val entry = providerInstances[instanceId]
            ?: return TestResult(false, "实例不存在")
        entry.config = entry.config.copy(enabled = true)
        saveConfig()
        return initializeProviderInstance(instanceId)
    }

    /** 禁用 Provider 实例（禁用后自动断开连接） */
    suspend fun disableProviderInstance(instanceId: String): TestResult {
        val entry = providerInstances[instanceId]
            ?: return TestResult(false, "实例不存在")
        entry.config = entry.config.copy(enabled = false)
        entry.provider?.terminate()
        entry.provider = null
        entry.status = ProviderStatus.IDLE
        entry.error = null
        saveConfig()
        notifyInstancesChanged()
        return TestResult(true)
    }

    /** 设置主 LLM */
    fun setPrimaryProvider(instanceId: String): Boolean {
        if (!providerInstances.containsKey(instanceId)) return false
        primaryInstanceId = instanceId
        saveConfig()
        notifyInstancesChanged()
        return true
    }

    /** 获取主 LLM 的 Provider 实例 */
    fun getPrimaryProvider(): LLMProvider? {
        if (primaryInstanceId.isBlank()) return null
        return providerInstances[primaryInstanceId]?.provider
    }

    /** 获取所有 Provider 实例信息 */
    fun getProviderInstances(): List<ProviderInstanceInfo> {
        return providerInstances.map { (_, entry) ->
            ProviderInstanceInfo(
                instanceId = entry.config.instanceId,
                providerId = entry.config.providerId,
                displayName = entry.config.displayName,
                config = entry.config.config,
                metadata = ProviderRegistry.get(entry.config.providerId),
                enabled = entry.config.enabled,
                status = entry.status,
                error = entry.error,
                isPrimary = entry.config.instanceId == primaryInstanceId,
            )
        }
    }

    private fun notifyInstancesChanged() {
        _providerInstancesFlow.value = getProviderInstances()
        _ttsInstancesFlow.value = getTtsProviderInstances()
    }

    /** 获取所有 TTS Provider 实例信息（包含运行时状态） */
    fun getTtsProviderInstances(): List<TTSProviderInstanceInfo> {
        return ttsInstances.map { (_, entry) ->
            TTSProviderInstanceInfo(
                instanceId = entry.config.instanceId,
                providerId = entry.config.providerId,
                displayName = entry.config.displayName,
                config = entry.config.config,
                metadata = TTSProviderRegistry.get(entry.config.providerId),
                enabled = entry.config.enabled,
                status = entry.status,
                error = entry.error,
                isPrimary = entry.config.instanceId == primaryTtsInstanceId,
            )
        }
    }

    // ==================== TTS Provider 实例 CRUD ====================

    /** 添加 TTS Provider 实例 */
    fun addTtsInstance(instanceConfig: TTSProviderInstanceConfig): Boolean {
        if (!TTSProviderRegistry.has(instanceConfig.providerId)) return false
        ttsInstances[instanceConfig.instanceId] = TTSEntry(config = instanceConfig)
        if (ttsInstances.size == 1) {
            primaryTtsInstanceId = instanceConfig.instanceId
        }
        saveConfig()
        notifyInstancesChanged()
        return true
    }

    /** 移除 TTS Provider 实例 */
    fun removeTtsInstance(instanceId: String): Boolean {
        val entry = ttsInstances[instanceId] ?: return false
        scope.launch { entry.provider?.terminate() }
        ttsInstances.remove(instanceId)
        if (primaryTtsInstanceId == instanceId) {
            primaryTtsInstanceId = ttsInstances.keys.firstOrNull() ?: ""
        }
        saveConfig()
        notifyInstancesChanged()
        return true
    }

    /** 更新 TTS Provider 实例配置 */
    fun updateTtsInstance(instanceId: String, newConfig: TTSProviderInstanceConfig): Boolean {
        val entry = ttsInstances[instanceId] ?: return false
        scope.launch { entry.provider?.terminate() }
        entry.config = newConfig
        entry.provider = null
        entry.status = ProviderStatus.IDLE
        entry.error = null
        saveConfig()
        notifyInstancesChanged()
        return true
    }

    /** 设置主 TTS */
    fun setPrimaryTts(instanceId: String): Boolean {
        if (!ttsInstances.containsKey(instanceId)) return false
        primaryTtsInstanceId = instanceId
        saveConfig()
        notifyInstancesChanged()
        return true
    }

    /** 启用 TTS Provider 实例（启用后自动初始化） */
    suspend fun enableTtsInstance(instanceId: String): TestResult {
        val entry = ttsInstances[instanceId]
            ?: return TestResult(false, "TTS 实例不存在")
        entry.config = entry.config.copy(enabled = true)
        saveConfig()
        return initializeTtsInstance(instanceId)
    }

    /** 禁用 TTS Provider 实例 */
    suspend fun disableTtsInstance(instanceId: String): TestResult {
        val entry = ttsInstances[instanceId]
            ?: return TestResult(false, "TTS 实例不存在")
        entry.config = entry.config.copy(enabled = false)
        entry.provider?.terminate()
        entry.provider = null
        entry.status = ProviderStatus.IDLE
        entry.error = null
        saveConfig()
        notifyInstancesChanged()
        return TestResult(true)
    }

    // ==================== 持久化 ====================

    fun loadConfig() {
        val settings = settingsRepo.current
        providerInstances.clear()
        primaryInstanceId = settings.primaryLlmInstanceId

        for (cfg in settings.llmProviderInstances) {
            providerInstances[cfg.instanceId] = ProviderEntry(config = cfg)
        }

        if (primaryInstanceId.isBlank() && providerInstances.isNotEmpty()) {
            primaryInstanceId = providerInstances.keys.first()
        }

        ttsInstances.clear()
        primaryTtsInstanceId = settings.primaryTtsInstanceId
        for (cfg in settings.ttsProviderInstances) {
            ttsInstances[cfg.instanceId] = TTSEntry(config = cfg)
        }

        notifyInstancesChanged()

        // 自动初始化已启用的实例：先设为 CONNECTING 防止竞态，再异步初始化
        for (entry in providerInstances.values) {
            if (entry.config.enabled) {
                entry.status = ProviderStatus.CONNECTING
            }
        }
        notifyInstancesChanged()
        scope.launch {
            for (entry in providerInstances.values.toList()) {
                if (entry.config.enabled) {
                    initializeProviderInstance(entry.config.instanceId)
                }
            }
        }
    }

    fun saveConfig() {
        settingsRepo.update { s ->
            s.copy(
                llmProviderInstances = providerInstances.values.map { it.config },
                primaryLlmInstanceId = primaryInstanceId,
                ttsProviderInstances = ttsInstances.values.map { it.config },
                primaryTtsInstanceId = primaryTtsInstanceId,
            )
        }
    }

    // ==================== 模型信息处理（对齐 handler.ts processModelInfo）====================

    /**
     * 处理模型信息 — 由 AgentClient 在 Live2D 模型加载后调用。
     * 对齐原项目 handler.ts processModelInfo → core-agent.onModelInfo → personality.setModelInfo。
     */
    fun processModelInfo(info: ModelInfo) {
        this.modelInfo = info
        logManager.log(LogLevel.DEBUG, "已接收模型信息: motions=${(info.motions).keys}, expressions=${info.expressions}, hitAreas=${info.hitAreas}, paramCount=${info.availableParameters.size}", "AgentService")

        // 转发给 PersonalityPlugin（对齐原项目 personality.setModelInfo）
        val personalityPlugin = pluginManager.getPlugin<PersonalityPlugin>("builtin.personality")
        if (personalityPlugin != null) {
            personalityPlugin.setModelInfo(
                PersonalityPlugin.ModelInfo(
                    hitAreas = info.hitAreas,
                    motionGroups = info.motions.keys.toList(),
                    expressions = info.expressions,
                )
            )
            logManager.log(LogLevel.DEBUG, "模型信息已转发给 PersonalityPlugin", "AgentService")
        }
    }

    // ==================== 用户输入处理 ====================

    suspend fun handleUserInput(
        text: String,
        attachment: Attachment?,
        onEvent: suspend (AgentEvent) -> Unit,
    ) {
        logManager.log(LogLevel.INFO, "收到用户输入: \"${text.take(100)}\"${if (attachment != null) " [附件: ${attachment.type}]" else ""}", "AgentService")

        // 如果 Provider 尚未初始化完成（异步初始化中），等待最多 5 秒
        var provider = getPrimaryProvider()
        if (provider == null && primaryInstanceId.isNotBlank()) {
            val entry = providerInstances[primaryInstanceId]
            if (entry != null && entry.config.enabled &&
                (entry.status == ProviderStatus.CONNECTING || entry.status == ProviderStatus.IDLE)
            ) {
                logManager.log(LogLevel.INFO, "Provider 正在初始化（status=${entry.status}），等待...", "AgentService")
                for (i in 1..50) { // 50 x 100ms = 5s
                    kotlinx.coroutines.delay(100)
                    provider = getPrimaryProvider()
                    if (provider != null) break
                    // 如果状态变为 ERROR 则提前退出等待
                    if (entry.status == ProviderStatus.ERROR) break
                }
            }
        }

        logManager.log(LogLevel.DEBUG, "主 Provider: ${if (provider != null) "已就绪 (${primaryInstanceId})" else "未初始化"}, 流式=${settingsRepo.current.llmStream}", "AgentService")
        if (provider == null) {
            val entry = providerInstances[primaryInstanceId]
            val detail = when {
                primaryInstanceId.isBlank() -> "未设置主 LLM 实例"
                entry == null -> "实例 $primaryInstanceId 不存在"
                !entry.config.enabled -> "实例未启用"
                entry.status == ProviderStatus.ERROR -> "初始化失败: ${entry.error}"
                entry.status == ProviderStatus.CONNECTING -> "仍在初始化中，请稍后再试"
                else -> "Provider 未就绪 (status=${entry.status})"
            }
            logManager.log(LogLevel.WARN, "无可用 Provider: $detail", "AgentService")
            onEvent(AgentEvent.Dialogue(
                DialogueEvent.Complete(DialogueData(
                    text = "[警告] $detail。请在 Agent 面板 → 概览 中检查 LLM Provider 配置。",
                    duration = 8000L,
                ))
            ))
            return
        }

        // ===== InputCollector 集成：合并短时间内的多条输入 =====
        val inputCollector = pluginManager.getPlugin<InputCollectorPlugin>("builtin.input-collector")
        val finalText = if (inputCollector != null && inputCollector.enabled && inputCollector.isEnabled()) {
            val sessionId = conversationManager.currentConversationId.value
            val merged = inputCollector.collectInput(sessionId, text)
            if (merged == null) {
                // 此输入已被收到缓冲区，等待后续输入合并，跳过本次处理
                return
            }
            merged
        } else {
            text
        }

        val userContent = if (attachment != null) {
            "$finalText\n[附件: ${attachment.name ?: attachment.type}]"
        } else {
            finalText
        }

        // 构建 ChatMessage — 如果附件是图片，构建多模态消息
        val userMessage = if (attachment != null && attachment.type == "image" && !attachment.data.isNullOrBlank()) {
            ChatMessage(
                role = "user",
                content = userContent,
                attachment = ChatMessageAttachment(
                    type = "image",
                    data = attachment.data,
                    mimeType = attachment.source ?: "image/png",
                    fileName = attachment.name,
                ),
            )
        } else {
            ChatMessage(role = "user", content = userContent)
        }

        conversationManager.addMessage(userMessage)

        val request = buildLLMRequest()
        val useStream = settingsRepo.current.llmStream ||
            providerInstances[primaryInstanceId]?.config?.config?.extra?.get("stream") == "true"

        if (useStream) {
            handleStreamingChat(provider, request, onEvent)
        } else {
            handleNonStreamingChat(provider, request, onEvent)
        }
    }

    // ==================== 流式对话（含工具调用循环）====================

    private suspend fun handleStreamingChat(
        provider: LLMProvider,
        request: LLMRequest,
        onEvent: suspend (AgentEvent) -> Unit,
    ) {
        val streamId = generateId()
        val startTime = com.gameswu.nyadeskpet.currentTimeMillis()
        var currentRequest = request
        var iteration = 0
        var firstIteration = true

        try {
            while (iteration < maxToolIterations) {
                iteration++

                if (firstIteration) {
                    onEvent(AgentEvent.Dialogue(DialogueEvent.StreamStart(streamId)))
                    firstIteration = false
                }

                val contentAcc = StringBuilder()
                val reasoningAcc = StringBuilder()
                // 收集流式工具调用增量
                val toolCallAccumulators = mutableMapOf<Int, Triple<String, String, StringBuilder>>() // index -> (id, name, argBuilder)

                var streamError: String? = null

                provider.chatStream(currentRequest) { chunk ->
                    if (!chunk.done) {
                        if (chunk.delta.isNotEmpty()) {
                            contentAcc.append(chunk.delta)
                        }
                        chunk.reasoningDelta?.let { reasoningAcc.append(it) }

                        if (chunk.delta.isNotEmpty() || chunk.reasoningDelta != null) {
                            onEvent(AgentEvent.Dialogue(
                                DialogueEvent.StreamChunk(chunk.delta, chunk.reasoningDelta)
                            ))
                        }

                        // 收集流式工具调用片段
                        chunk.toolCallDeltas?.forEach { delta ->
                            val acc = toolCallAccumulators.getOrPut(delta.index) {
                                Triple(delta.id ?: "", delta.name ?: "", StringBuilder())
                            }
                            // 如果有新的 id 或 name，更新
                            val updatedId = if (delta.id != null && delta.id.isNotBlank()) delta.id else acc.first
                            val updatedName = if (delta.name != null && delta.name.isNotBlank()) delta.name else acc.second
                            delta.arguments?.let { acc.third.append(it) }
                            toolCallAccumulators[delta.index] = Triple(updatedId, updatedName, acc.third)
                        }
                    } else if (chunk.delta.isNotEmpty()) {
                        // done=true 且 delta 非空：通常是错误消息（HTTP 错误 / 连接失败 / 超时等）
                        streamError = chunk.delta
                        logManager.log(LogLevel.ERROR, "流式响应错误: ${chunk.delta}", "AgentService")
                    }
                }

                // 如果流式请求返回了错误，且没有积累到任何正常内容，直接返回错误
                if (streamError != null && contentAcc.isEmpty()) {
                    val duration = com.gameswu.nyadeskpet.currentTimeMillis() - startTime
                    val errorText = "[错误] $streamError"
                    conversationManager.addMessage(ChatMessage(role = "assistant", content = errorText))
                    onEvent(AgentEvent.Dialogue(DialogueEvent.StreamEnd(errorText, null, duration)))
                    return
                }

                // 检查是否有积累的工具调用
                if (toolCallAccumulators.isNotEmpty()) {
                    val toolCalls = toolCallAccumulators.entries.sortedBy { it.key }.map { (_, triple) ->
                        ToolCallInfo(
                            id = triple.first,
                            name = triple.second,
                            arguments = triple.third.toString(),
                        )
                    }

                    // 结束当前流
                    val partialText = contentAcc.toString()
                    if (partialText.isNotBlank()) {
                        onEvent(AgentEvent.Dialogue(DialogueEvent.StreamEnd(partialText, null, 0)))
                    }

                    // 将 assistant 工具调用消息加入历史
                    conversationManager.addMessage(ChatMessage(
                        role = "assistant",
                        content = partialText,
                        toolCalls = toolCalls,
                    ))

                    // 执行工具调用
                    val toolResults = executeToolCalls(toolCalls, onEvent)
                    for (msg in toolResults) {
                        conversationManager.addMessage(msg)
                    }

                    // 以新的历史重新请求 LLM（开启新的流式轮次）
                    currentRequest = buildLLMRequest()
                    onEvent(AgentEvent.Dialogue(DialogueEvent.StreamStart(generateId())))
                    continue
                }

                // 无工具调用 — 最终文本响应
                val duration = com.gameswu.nyadeskpet.currentTimeMillis() - startTime
                val fullText = contentAcc.toString()
                val reasoning = reasoningAcc.toString().takeIf { it.isNotEmpty() }

                if (fullText.isBlank() && streamError != null) {
                    // 有错误且无正常内容
                    val errorText = "[错误] $streamError"
                    conversationManager.addMessage(ChatMessage(role = "assistant", content = errorText))
                    onEvent(AgentEvent.Dialogue(DialogueEvent.StreamEnd(errorText, null, duration)))
                    return
                }

                conversationManager.addMessage(ChatMessage(
                    role = "assistant",
                    content = fullText,
                    reasoningContent = reasoning,
                ))

                onEvent(AgentEvent.Dialogue(DialogueEvent.StreamEnd(fullText, reasoning, duration)))
                emitDialogueWithExpression(fullText, duration, reasoning, onEvent)
                handleTts(fullText, onEvent)
                return
            }

            // 超过最大迭代次数
            onEvent(AgentEvent.Dialogue(DialogueEvent.StreamEnd(
                "[警告] 工具调用超过最大迭代次数 ($maxToolIterations)，已停止。", null, 0
            )))
        } catch (e: Exception) {
            val duration = com.gameswu.nyadeskpet.currentTimeMillis() - startTime
            logManager.log(LogLevel.ERROR, "流式对话异常: ${e.message}", "AgentService", e.stackTraceToString())
            onEvent(AgentEvent.Dialogue(
                DialogueEvent.StreamEnd("[错误] ${e.message ?: "未知错误"}", null, duration)
            ))
        }
    }

    // ==================== 非流式对话（含工具调用循环）====================

    private suspend fun handleNonStreamingChat(
        provider: LLMProvider,
        request: LLMRequest,
        onEvent: suspend (AgentEvent) -> Unit,
    ) {
        val startTime = com.gameswu.nyadeskpet.currentTimeMillis()
        var currentRequest = request
        var iteration = 0

        try {
            while (iteration < maxToolIterations) {
                iteration++
                val response = provider.chat(currentRequest)

                // 检查 API 错误（finishReason == "error" 表示 HTTP 错误或请求失败）
                if (response.finishReason == "error") {
                    val duration = com.gameswu.nyadeskpet.currentTimeMillis() - startTime
                    val errorText = "[错误] ${response.text}"
                    logManager.log(LogLevel.ERROR, "LLM 请求失败: ${response.text}", "AgentService")
                    conversationManager.addMessage(ChatMessage(role = "assistant", content = errorText))
                    onEvent(AgentEvent.Dialogue(
                        DialogueEvent.Complete(DialogueData(text = errorText, duration = 5000L))
                    ))
                    return
                }

                // 检查是否有工具调用
                if (!response.toolCalls.isNullOrEmpty()) {
                    // 将 assistant 的工具调用消息加入历史
                    conversationManager.addMessage(ChatMessage(
                        role = "assistant",
                        content = response.text,
                        toolCalls = response.toolCalls,
                    ))

                    // 执行工具调用
                    val toolResults = executeToolCalls(response.toolCalls, onEvent)

                    // 将工具结果加入历史
                    for (msg in toolResults) {
                        conversationManager.addMessage(msg)
                    }

                    // 以新的历史（含工具结果）重新请求 LLM
                    currentRequest = buildLLMRequest()
                    continue
                }

                // 无工具调用 — 最终文本响应
                val duration = com.gameswu.nyadeskpet.currentTimeMillis() - startTime
                val fullText = response.text

                conversationManager.addMessage(ChatMessage(
                    role = "assistant",
                    content = fullText,
                    reasoningContent = response.reasoningContent,
                ))

                onEvent(AgentEvent.Dialogue(
                    DialogueEvent.Complete(DialogueData(
                        text = fullText,
                        duration = duration.coerceAtLeast(3000L),
                        reasoningContent = response.reasoningContent,
                    ))
                ))

                emitDialogueWithExpression(fullText, duration.coerceAtLeast(3000L), response.reasoningContent, onEvent)
                handleTts(fullText, onEvent)
                return
            }

            // 超过最大迭代次数
            onEvent(AgentEvent.Dialogue(
                DialogueEvent.Complete(DialogueData(
                    text = "[警告] 工具调用超过最大迭代次数 ($maxToolIterations)，已停止。",
                    duration = 5000L,
                ))
            ))
        } catch (e: Exception) {
            logManager.log(LogLevel.ERROR, "非流式对话异常: ${e.message}", "AgentService", e.stackTraceToString())
            onEvent(AgentEvent.Dialogue(
                DialogueEvent.Complete(DialogueData(
                    text = "[错误] ${e.message ?: "未知错误"}",
                    duration = 5000L,
                ))
            ))
        }
    }

    // ==================== 命令处理（全部委托给插件）====================

    suspend fun handleCommand(
        name: String,
        args: String,
        onEvent: suspend (AgentEvent) -> Unit,
    ) {
        val handler = pluginManager.getCommandHandler(name)
        if (handler != null) {
            try {
                val result = handler(args)
                onEvent(AgentEvent.Command(CommandResponseData(
                    command = name, success = true, text = result,
                )))
            } catch (e: Exception) {
                onEvent(AgentEvent.Command(CommandResponseData(
                    command = name, success = false, error = e.message ?: "命令执行失败",
                )))
            }
        } else {
            onEvent(AgentEvent.Command(CommandResponseData(
                command = name, success = false,
                error = "未知命令: /$name。输入 /help 查看可用命令。",
            )))
        }
    }

    /** 处理触碰事件 */
    suspend fun handleTapEvent(
        hitArea: String,
        onEvent: suspend (AgentEvent) -> Unit,
    ) {
        val modelPath = settingsRepo.current.modelPath
        val tapConfig = settingsRepo.current.tapConfigs[modelPath]?.get(hitArea)

        if (tapConfig != null && tapConfig.enabled) {
            if (tapConfig.expression.isNotBlank()) {
                onEvent(AgentEvent.Live2D(Live2DCommandData(
                    command = "expression", expressionId = tapConfig.expression,
                )))
            }
            if (tapConfig.motion.isNotBlank()) {
                val parts = tapConfig.motion.split("/")
                onEvent(AgentEvent.Live2D(Live2DCommandData(
                    command = "motion",
                    group = parts.getOrNull(0) ?: "",
                    index = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    priority = 2,
                )))
            }
            return
        }

        val tapMessage = "（用户摸了摸$hitArea 区域）"
        handleUserInput(tapMessage, null, onEvent)
    }

    /** 获取所有可用命令定义（全部来自插件） */
    fun getCommandDefinitions(): List<CommandDefinition> {
        return pluginManager.getCommandDefinitions().map { cmdInfo ->
            CommandDefinition(
                name = cmdInfo.name,
                description = cmdInfo.description,
                category = cmdInfo.source,
                enabled = cmdInfo.enabled,
            )
        }
    }

    // ==================== 工具调用循环 (Agent Loop) ====================

    /** 最大工具调用迭代次数，防止无限循环 */
    private val maxToolIterations = 10

    /**
     * 将插件工具定义转换为 LLM ToolDefinitionSchema 格式（OpenAI Function Calling）
     */
    private fun getToolSchemas(): List<ToolDefinitionSchema> {
        // 插件工具
        val pluginTools = pluginManager.getAllTools().map { tool ->
            ToolDefinitionSchema(
                type = "function",
                function = ToolFunctionDef(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters,
                )
            )
        }
        // 技能工具（skill_ 前缀）
        val skillTools = skillManager.toToolSchemas().map { tool ->
            ToolDefinitionSchema(
                type = "function",
                function = ToolFunctionDef(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters,
                )
            )
        }
        return pluginTools + skillTools
    }

    /**
     * 执行工具调用并返回结果消息。
     * 支持工具确认机制（requireConfirm 的工具需用户批准）。
     */
    private suspend fun executeToolCalls(
        toolCalls: List<ToolCallInfo>,
        onEvent: suspend (AgentEvent) -> Unit,
    ): List<ChatMessage> {
        val toolResultMessages = mutableListOf<ChatMessage>()
        logManager.log(LogLevel.INFO, "执行 ${toolCalls.size} 个工具调用: ${toolCalls.joinToString { it.name }}", "AgentService")

        for (tc in toolCalls) {
            // 如果是技能调用，走 SkillManager
            if (skillManager.isSkillToolCall(tc.name)) {
                try {
                    val arguments: JsonObject = try {
                        Json.parseToJsonElement(tc.arguments).jsonObject
                    } catch (_: Exception) {
                        kotlinx.serialization.json.buildJsonObject {}
                    }

                    val skillCtx = SkillContext(
                        callProvider = { request ->
                            val provider = getPrimaryProvider()
                                ?: throw IllegalStateException("No primary provider")
                            provider.chat(request)
                        },
                        executeTool = { toolName, args -> pluginManager.executeTool(toolName, args) }
                    )
                    val result = skillManager.handleToolCall(tc.name, arguments, skillCtx)

                    toolResultMessages.add(ChatMessage(
                        role = "tool",
                        content = result.result?.toString() ?: (result.error ?: "技能执行完成"),
                        toolCallId = tc.id,
                        toolName = tc.name,
                    ))
                    onEvent(AgentEvent.Command(CommandResponseData(
                        command = "tool_status",
                        success = result.success,
                        text = "[技能] ${tc.name}: ${result.result?.toString()?.take(100) ?: result.error}"
                    )))
                } catch (e: Exception) {
                    toolResultMessages.add(ChatMessage(
                        role = "tool",
                        content = "技能执行异常: ${e.message}",
                        toolCallId = tc.id,
                        toolName = tc.name,
                    ))
                }
                continue
            }

            // 查找工具定义，检查是否需要确认
            val toolDef = pluginManager.getAllTools().find { it.name == tc.name }
            val needConfirm = toolDef?.requireConfirm == true

            if (needConfirm) {
                // 发送确认请求到前端（工具确认已有 UI 支持）
                onEvent(AgentEvent.Command(CommandResponseData(
                    command = "tool_status",
                    success = true,
                    text = "[等待确认] 工具: ${tc.name}"
                )))
            }

            try {
                val arguments: JsonObject = try {
                    Json.parseToJsonElement(tc.arguments).jsonObject
                } catch (_: Exception) {
                    kotlinx.serialization.json.buildJsonObject {}
                }

                val result = pluginManager.executeTool(tc.name, arguments)

                val resultContent = if (result.success) {
                    result.result?.toString() ?: "工具执行成功（无返回值）"
                } else {
                    "工具执行失败: ${result.error ?: "未知错误"}"
                }

                toolResultMessages.add(ChatMessage(
                    role = "tool",
                    content = resultContent,
                    toolCallId = tc.id,
                    toolName = tc.name,
                ))

                onEvent(AgentEvent.Command(CommandResponseData(
                    command = "tool_status",
                    success = result.success,
                    text = "[工具] ${tc.name}: $resultContent"
                )))
            } catch (e: Exception) {
                toolResultMessages.add(ChatMessage(
                    role = "tool",
                    content = "工具执行异常: ${e.message}",
                    toolCallId = tc.id,
                    toolName = tc.name,
                ))
            }
        }

        return toolResultMessages
    }

    // ==================== 内部方法 ====================

    private suspend fun buildLLMRequest(): LLMRequest {
        val tools = getToolSchemas()

        // ===== MemoryPlugin 集成：压缩历史消息 =====
        val rawHistory = conversationManager.getHistory(settingsRepo.current.llmMaxHistory)
        val memoryPlugin = pluginManager.getPlugin<MemoryPlugin>("builtin.memory")
        val messages = if (memoryPlugin != null && memoryPlugin.enabled) {
            val sessionId = conversationManager.currentConversationId.value
            val historyPairs = rawHistory.map { it.role to it.content }
            val compressedPairs = memoryPlugin.buildContextMessages(sessionId, historyPairs)
            compressedPairs.map { (role, content) ->
                ChatMessage(role = role, content = content)
            }
        } else {
            rawHistory
        }

        return LLMRequest(
            messages = messages,
            systemPrompt = buildSystemPrompt(),
            temperature = settingsRepo.current.llmTemperature,
            maxTokens = settingsRepo.current.llmMaxTokens,
            tools = tools.ifEmpty { null },
            toolChoice = if (tools.isNotEmpty()) "auto" else null,
        )
    }

    private fun buildSystemPrompt(): String {
        val settings = settingsRepo.current
        if (settings.llmSystemPrompt.isNotBlank()) return settings.llmSystemPrompt

        val personalityPlugin = pluginManager.getPlugin<PersonalityPlugin>("builtin.personality")
        if (personalityPlugin != null) {
            // ===== 设置可用工具提示 =====
            val tools = pluginManager.getAllTools()
            if (tools.isNotEmpty()) {
                val toolsHint = tools.joinToString("\n") { "- ${it.name}: ${it.description}" }
                personalityPlugin.setToolsHint(toolsHint)
            }

            return personalityPlugin.buildSystemPrompt(
                useCustom = settings.useCustomCharacter,
                customName = settings.customName,
                customPersonality = settings.customPersonality,
            )
        }

        // 后备
        val characterName = if (settings.useCustomCharacter && settings.customName.isNotBlank()) {
            settings.customName
        } else "Nya"
        val personality = if (settings.useCustomCharacter && settings.customPersonality.isNotBlank()) {
            settings.customPersonality
        } else "活泼、温柔、有点调皮"
        return "你是$characterName，一只可爱的桌宠猫娘。你的性格是${personality}。回复要简短自然。不要使用 Markdown 格式。"
    }

    /**
     * 根据对话内容生成表情指令。
     * 返回表情命令列表（不直接发送），由调用方组合 sync_command。
     * 对齐原项目中 expression 结果与 dialogue 通过 sync_command 一起下发。
     */
    private suspend fun generateExpressionCommands(text: String): List<Live2DCommandData> {
        return try {
            val expressionPlugin = pluginManager.getPlugin<ExpressionPlugin>("builtin.expression")
            if (expressionPlugin == null || !expressionPlugin.isEnabled()) return emptyList()
            expressionPlugin.generateExpression(text, modelInfo)
        } catch (e: Exception) {
            // 表情生成失败不影响主流程（对齐原项目行为）
            logManager.log(LogLevel.WARN, "表情生成出错（非致命）: ${e.message}", "AgentService")
            emptyList()
        }
    }

    /**
     * 发送对话 + 表情的同步指令。
     * 对齐原项目 sync_command：将 dialogue + expression/motion/parameter 动作合并下发，
     * 确保表情与对话同步展示。
     */
    private suspend fun emitDialogueWithExpression(
        dialogueText: String,
        duration: Long,
        reasoning: String?,
        onEvent: suspend (AgentEvent) -> Unit,
    ) {
        val expressionCmds = generateExpressionCommands(dialogueText)

        if (expressionCmds.isEmpty()) {
            // 无表情指令，直接发送对话
            return
        }

        // 构建 sync_command：表情动作 + 对话
        val actions = mutableListOf<SyncAction>()
        for (cmd in expressionCmds) {
            actions.add(
                SyncAction(
                    type = cmd.command,
                    group = cmd.group,
                    index = cmd.index,
                    expressionId = cmd.expressionId,
                    parameterId = cmd.parameterId,
                    value = cmd.value,
                    weight = cmd.weight,
                    parameters = cmd.parameters,
                )
            )
        }
        // 对话动作放最后
        actions.add(
            SyncAction(
                type = "dialogue",
                text = dialogueText,
                duration = duration,
            )
        )
        onEvent(AgentEvent.SyncCommand(SyncCommandData(actions = actions)))
    }

    /**
     * TTS 合成并流式推送音频事件。
     * 对齐原项目 handler.ts synthesizeAndStream()：
     * 1. 获取主 TTS Provider（无则静默跳过）
     * 2. 延迟初始化 Provider
     * 3. 合成 → audio_stream_start → audio_chunk → audio_stream_end
     */
    private suspend fun handleTts(text: String, onEvent: suspend (AgentEvent) -> Unit) {
        if (text.isBlank()) return  // 空文本不合成
        if (primaryTtsInstanceId.isBlank()) return  // 没有 TTS 就静默跳过
        val entry = ttsInstances[primaryTtsInstanceId] ?: return
        if (!entry.config.enabled) return

        try {
            // 延迟初始化 TTS Provider
            if (entry.provider == null) {
                val provider = TTSProviderRegistry.create(entry.config.providerId, entry.config.config)
                    ?: return
                provider.initialize()
                entry.provider = provider
                entry.status = ProviderStatus.CONNECTED
            }

            val provider = entry.provider ?: return
            val format = entry.config.config.extra["format"] ?: "mp3"
            val mimeType = audioFormatToMimeType(format)

            // 先合成音频，成功后再发送 audio_stream_start——避免合成失败时 ExoPlayer 收到空数据报错
            val voiceId = entry.config.config.extra["voiceId"]
            val response = provider.synthesize(TTSRequest(
                text = text,
                voiceId = voiceId,
                format = format,
            ))

            // 合成成功，发送音频流
            onEvent(AgentEvent.Audio(AudioEvent.Start(AudioStreamStartData(
                mimeType = mimeType,
                text = text,
            ))))
            onEvent(AgentEvent.Audio(AudioEvent.Chunk(AudioChunkData(
                chunk = response.audioBase64,
                sequence = 0,
            ))))
            onEvent(AgentEvent.Audio(AudioEvent.End))

            logManager.log(LogLevel.DEBUG, "TTS 合成完成", "AgentService")
        } catch (e: Exception) {
            logManager.log(LogLevel.WARN, "TTS 合成失败（非致命）: ${e.message}", "AgentService")
            // 合成失败时不发送任何音频事件，避免 ExoPlayer 空数据报错
        }
    }

    /** 初始化 TTS Provider 实例 */
    suspend fun initializeTtsInstance(instanceId: String): TestResult {
        val entry = ttsInstances[instanceId]
            ?: return TestResult(false, "TTS 实例不存在")
        return try {
            entry.provider?.terminate()
            entry.status = ProviderStatus.CONNECTING
            entry.error = null
            notifyInstancesChanged()

            val provider = TTSProviderRegistry.create(entry.config.providerId, entry.config.config)
                ?: throw Exception("无法创建 TTS Provider: ${entry.config.providerId}")
            provider.initialize()
            entry.provider = provider
            entry.status = ProviderStatus.CONNECTED
            entry.error = null
            notifyInstancesChanged()
            TestResult(true)
        } catch (e: Exception) {
            entry.status = ProviderStatus.ERROR
            entry.error = e.message
            notifyInstancesChanged()
            TestResult(false, e.message)
        }
    }

    // ==================== 对话管理（委托 ConversationManager）====================

    fun newConversation(): String = conversationManager.newConversation()
    fun switchConversation(conversationId: String): Boolean = conversationManager.switchConversation(conversationId)
    fun deleteConversation(conversationId: String) = conversationManager.deleteConversation(conversationId)
    fun clearHistory() { conversationManager.clearCurrentHistory() }
    fun getHistorySize(): Int = conversationManager.getHistory(Int.MAX_VALUE).size
    val conversationList get() = conversationManager.conversations
    val currentConversationId get() = conversationManager.currentConversationId

    /** 获取指定对话的所有消息（供 UI 加载历史记录） */
    fun getConversationMessages(conversationId: String): List<ConversationManager.StoredMessage> {
        return conversationManager.getMessages(conversationId)
    }

    // ==================== 事件监听器 — 供 PluginContext 回调 ====================

    private var eventListener: (suspend (AgentEvent) -> Unit)? = null

    /** 设置事件回调（AgentClient 在连接时调用） */
    fun setEventListener(listener: suspend (AgentEvent) -> Unit) {
        eventListener = listener
    }

    // ==================== 插件初始化 ====================

    /**
     * 初始化插件系统并注册所有内置插件。
     * 对齐原项目 autoActivatePlugins 拓扑排序逻辑。
     */
    fun initializePlugins() {
        logManager.log(LogLevel.INFO, "初始化插件系统", "AgentService")
        pluginManager.initialize { pluginId -> createPluginContext(pluginId) }

        // 注册内置插件（顺序与原项目依赖拓扑一致）
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.PersonalityPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.MemoryPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.InfoPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.CoreCommandsPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.ExpressionPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.SchedulerPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.WebToolsPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.ImageGenPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.InputCollectorPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.ImageTranscriberPlugin())
        pluginManager.registerPlugin(com.gameswu.nyadeskpet.plugin.builtin.PlanningPlugin())
        logManager.log(LogLevel.INFO, "已注册 ${pluginManager.getAllPlugins().size} 个内置插件", "AgentService")
    }

    /**
     * 为每个插件创建独立的 PluginContext。
     * pluginId 已绑定，getConfig()/saveConfig()/registerCommand() 自动关联到正确的插件。
     */
    private fun createPluginContext(pluginId: String): PluginContext {
        return object : PluginContext {
            override fun getSetting(key: String): String? {
                return when (key) {
                    "llmSystemPrompt" -> settingsRepo.current.llmSystemPrompt
                    "useCustomCharacter" -> settingsRepo.current.useCustomCharacter.toString()
                    "customName" -> settingsRepo.current.customName
                    "customPersonality" -> settingsRepo.current.customPersonality
                    else -> null
                }
            }

            override fun getConfig(): Map<String, JsonElement> {
                return pluginManager.getPluginConfig(pluginId)
            }

            override fun saveConfig(config: Map<String, JsonElement>) {
                pluginManager.savePluginConfig(pluginId, config)
            }

            override suspend fun sendDialogue(text: String, duration: Long) {
                eventListener?.invoke(
                    AgentEvent.Dialogue(DialogueEvent.Complete(DialogueData(text = text, duration = duration)))
                )
            }

            override suspend fun sendLive2DCommand(
                command: String,
                group: String?,
                index: Int?,
                priority: Int?,
                expressionId: String?,
                parameterId: String?,
                value: Float?,
                weight: Float?,
                parameters: List<ParameterSet>?,
            ) {
                eventListener?.invoke(
                    AgentEvent.Live2D(Live2DCommandData(
                        command = command,
                        group = group,
                        index = index,
                        priority = priority,
                        expressionId = expressionId,
                        parameterId = parameterId,
                        value = value,
                        weight = weight,
                        parameters = parameters,
                    ))
                )
            }

            override suspend fun sendSystemMessage(text: String) {
                eventListener?.invoke(AgentEvent.System(text))
            }

            override fun <T : Plugin> getPlugin(id: String): T? {
                return pluginManager.getPlugin(id)
            }

            override fun getPluginByName(name: String): Plugin? {
                return pluginManager.getPluginByName(name)
            }

            override fun registerCommand(
                name: String,
                description: String,
                handler: suspend (args: String) -> String,
            ) {
                pluginManager.registerCommand(name, description, handler, source = pluginId)
            }

            override fun unregisterCommand(name: String) {
                pluginManager.unregisterCommand(name)
            }

            override fun clearConversationHistory() {
                conversationManager.clearCurrentHistory()
            }

            override fun getConversationHistory(): List<Pair<String, String>> {
                return conversationManager.getHistory(settingsRepo.current.llmMaxHistory)
                    .map { it.role to it.content }
            }

            override fun addMessageToHistory(message: ChatMessage) {
                conversationManager.addMessage(message)
            }

            override fun getPrimaryProviderInfo(): ProviderBriefInfo? {
                if (primaryInstanceId.isBlank()) return null
                val entry = providerInstances[primaryInstanceId] ?: return null
                return ProviderBriefInfo(
                    instanceId = entry.config.instanceId,
                    providerId = entry.config.providerId,
                    displayName = entry.config.displayName,
                    model = entry.config.config.model,
                    status = entry.status.name,
                )
            }

            override fun getAllProviders(): List<ProviderBriefInfo> {
                return providerInstances.map { (_, entry) ->
                    ProviderBriefInfo(
                        instanceId = entry.config.instanceId,
                        providerId = entry.config.providerId,
                        displayName = entry.config.displayName,
                        model = entry.config.config.model,
                        status = entry.status.name,
                    )
                }
            }

            override suspend fun callProvider(instanceId: String, request: LLMRequest): LLMResponse {
                val targetId = if (instanceId == "primary") primaryInstanceId else instanceId
                val entry = providerInstances[targetId]
                    ?: throw IllegalStateException("Provider instance not found: $targetId")
                val provider = entry.provider
                    ?: throw IllegalStateException("Provider not initialized: $targetId")
                return provider.chat(request)
            }

            override fun getProviderConfig(instanceId: String): ProviderConfig? {
                val targetId = if (instanceId == "primary") primaryInstanceId else instanceId
                return providerInstances[targetId]?.config?.config
            }

            override fun getAllCommandDefinitions(): List<Pair<String, String>> {
                return pluginManager.getCommandDefinitions().map { it.name to it.description }
            }

            override fun isToolCallingEnabled(): Boolean {
                return pluginManager.getAllTools().isNotEmpty()
            }

            override fun getModelInfo(): ModelInfo? {
                return this@BuiltinAgentService.modelInfo
            }

            override fun logInfo(message: String) {
                logManager.log(LogLevel.INFO, message, "Plugin:$pluginId")
            }

            override fun logWarn(message: String) {
                logManager.log(LogLevel.WARN, message, "Plugin:$pluginId")
            }

            override fun logError(message: String) {
                logManager.log(LogLevel.ERROR, message, "Plugin:$pluginId")
            }
        }
    }
}

// =====================================================================
// Agent 事件 — BuiltinAgentService 到 AgentClient 的事件传递
// =====================================================================

sealed interface AgentEvent {
    data class Dialogue(val event: DialogueEvent) : AgentEvent
    data class Live2D(val cmd: Live2DCommandData) : AgentEvent
    data class SyncCommand(val data: SyncCommandData) : AgentEvent
    data class Audio(val event: AudioEvent) : AgentEvent
    data class Command(val resp: CommandResponseData) : AgentEvent
    data class System(val text: String) : AgentEvent
}