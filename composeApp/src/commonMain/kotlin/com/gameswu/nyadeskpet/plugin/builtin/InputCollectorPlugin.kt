package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.currentTimeMillis
import com.gameswu.nyadeskpet.plugin.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 会话抖动收集器 — 对齐原项目 agent-plugins/input-collector
 *
 * 解决问题：用户短时间内连续发送多条消息（如分多行输入），每条都触发独立的 LLM 调用，
 * 导致产生多个不完整的回复，浪费 token 且体验差。
 *
 * 解决方案：在一个可配置的抖动窗口内收集所有输入，窗口结束后合并为一条消息再处理。
 *
 * 工作原理：
 * 1. handler 或 core-agent 在处理 user_input 前调用 collectInput()
 * 2. collectInput() 返回一个挂起函数结果:
 *    - 如果在 debounceMs 内有新输入到达，旧调用返回 null（跳过）
 *    - 当 debounceMs 内无新输入（或达到 maxWaitMs），最新调用返回合并文本
 *
 * 暴露服务 API：
 * - collectInput(sessionId, text): 返回 null 表示"跳过"，返回 String 表示"合并完毕"
 * - isEnabled(): 是否启用
 */
class InputCollectorPlugin : Plugin {

    override val manifest = PluginManifest(
        id = "builtin.input-collector",
        name = "输入收集器",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "合并短时间内的多条输入消息，避免重复 LLM 调用",
        type = PluginType.BACKEND,
        capabilities = emptyList(),
        autoActivate = true,
    )
    override var enabled: Boolean = true

    // ==================== 配置 Schema ====================

    override val configSchema = PluginConfigSchema(
        fields = listOf(
            ConfigFieldDef(
                key = "enabled",
                type = ConfigFieldType.BOOL,
                description = "是否启用输入合并。关闭后每条消息都会立即处理。",
                default = JsonPrimitive(true),
            ),
            ConfigFieldDef(
                key = "debounceMs",
                type = ConfigFieldType.INT,
                description = "抖动等待时间（毫秒）。用户停止输入超过此时间后，将合并的消息发送给 Agent 处理。",
                default = JsonPrimitive(1500),
            ),
            ConfigFieldDef(
                key = "maxWaitMs",
                type = ConfigFieldType.INT,
                description = "最大等待时间（毫秒）。即使用户持续输入，也不会等待超过此时间。防止无限等待。",
                default = JsonPrimitive(10000),
            ),
            ConfigFieldDef(
                key = "separator",
                type = ConfigFieldType.STRING,
                description = "多条消息的合并分隔符。",
                default = JsonPrimitive("\n"),
            ),
        ),
    )

    // ==================== 状态 ====================

    private var ctx: PluginContext? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var collectorEnabled: Boolean = true
    private var debounceMs: Long = 1500L
    private var maxWaitMs: Long = 10000L
    private var separator: String = "\n"

    /**
     * 每个 session 的收集状态
     */
    private data class SessionState(
        val texts: MutableList<String> = mutableListOf(),
        var debounceJob: Job? = null,
        var maxWaitJob: Job? = null,
        var firstInputTime: Long = 0L,
        var currentDeferred: CompletableDeferred<String?>? = null,
        val previousDeferreds: MutableList<CompletableDeferred<String?>> = mutableListOf(),
    )

    private val sessions = mutableMapOf<String, SessionState>()

    // ==================== 生命周期 ====================

    override fun onLoad(context: PluginContext) {
        ctx = context
        loadConfig(context.getConfig())
        context.logInfo("输入收集器已初始化 (enabled=$collectorEnabled, debounce=${debounceMs}ms, maxWait=${maxWaitMs}ms)")
    }

    override fun onUnload() {
        // 清理所有挂起的定时器和 Deferred
        for ((_, state) in sessions) {
            state.debounceJob?.cancel()
            state.maxWaitJob?.cancel()
            // 立即完成所有挂起的回调
            state.currentDeferred?.complete(state.texts.joinToString(separator))
            state.previousDeferreds.forEach { it.complete(null) }
        }
        sessions.clear()
        scope.cancel()
        ctx = null
    }

    override fun onConfigChanged(config: Map<String, JsonElement>) {
        loadConfig(config)
    }

    private fun loadConfig(config: Map<String, JsonElement>) {
        config["enabled"]?.jsonPrimitive?.booleanOrNull?.let { collectorEnabled = it }
        config["debounceMs"]?.jsonPrimitive?.intOrNull?.let { debounceMs = it.toLong() }
        config["maxWaitMs"]?.jsonPrimitive?.intOrNull?.let { maxWaitMs = it.toLong() }
        config["separator"]?.jsonPrimitive?.contentOrNull?.let { separator = it }
    }

    // ==================== 服务 API ====================

    /**
     * 是否启用
     */
    fun isEnabled(): Boolean = collectorEnabled

    /**
     * 收集输入
     *
     * @param sessionId 会话 ID
     * @param text 用户输入文本
     * @return
     *   - String: 合并完毕的文本，调用方应处理此文本
     *   - null: 此输入已被收集到缓冲区，调用方应跳过（不处理）
     */
    suspend fun collectInput(sessionId: String, text: String): String? {
        if (!collectorEnabled || text.isBlank()) {
            // 未启用或空输入，直接返回原文本
            return text.takeIf { it.isNotBlank() }
        }

        val state = sessions.getOrPut(sessionId) { SessionState() }

        // 追加文本
        state.texts.add(text)

        // 如果有之前等待中的 deferred，标记为"跳过"
        state.currentDeferred?.let {
            state.previousDeferreds.add(it)
        }

        // 清除旧的 debounce 定时器
        state.debounceJob?.cancel()

        // 如果是首条消息，记录时间并设置 maxWait 定时器
        if (state.texts.size == 1) {
            state.firstInputTime = currentTimeMillis()
            state.maxWaitJob = scope.launch {
                delay(maxWaitMs)
                flush(sessionId, "maxWait")
            }
        }

        // 检查是否已超过 maxWait（安全检查）
        val elapsed = currentTimeMillis() - state.firstInputTime
        if (elapsed >= maxWaitMs) {
            val deferred = CompletableDeferred<String?>()
            state.currentDeferred = deferred
            flush(sessionId, "maxWait-immediate")
            return deferred.await()
        }

        // 创建新的 Deferred 并设置 debounce 定时器
        val deferred = CompletableDeferred<String?>()
        state.currentDeferred = deferred

        state.debounceJob = scope.launch {
            delay(debounceMs)
            flush(sessionId, "debounce")
        }

        return deferred.await()
    }

    /**
     * 刷出收集的输入
     */
    private fun flush(sessionId: String, reason: String) {
        val state = sessions[sessionId] ?: return

        // 清理定时器
        state.debounceJob?.cancel()
        state.maxWaitJob?.cancel()

        // 合并文本
        val merged = state.texts.joinToString(separator)
        val count = state.texts.size

        if (count > 1) {
            ctx?.logInfo("收集器合并了 $count 条消息 ($reason): \"${merged.take(100)}...\"")
        }

        // 解析之前的 deferred 为 null（跳过）
        state.previousDeferreds.forEach { it.complete(null) }

        // 解析当前 deferred 为合并文本
        state.currentDeferred?.complete(merged)

        // 清除该 session 的状态
        sessions.remove(sessionId)
    }
}
