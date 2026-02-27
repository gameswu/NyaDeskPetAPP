package com.gameswu.nyadeskpet.dialogue

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 对话气泡状态
 */
data class DialogueState(
    val visible: Boolean = false,
    val text: String = "",
    val progress: Float = 1f,
)

/**
 * 对话管理器（跨平台版本）
 * 负责桌宠气泡（字幕）的显示、打字机效果和自动消失
 */
class DialogueManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(DialogueState())
    val state: StateFlow<DialogueState> = _state.asStateFlow()

    private var autoHideJob: Job? = null
    private var typewriterJob: Job? = null

    /**
     * 显示对话
     * @param text 对话文本
     * @param durationMs 显示时长（毫秒），0 表示不自动隐藏
     * @param typewriter 是否使用打字机效果
     * @param attachment 附件
     */
    fun showDialogue(
        text: String,
        durationMs: Long = 5000L,
        typewriter: Boolean = true,
    ) {
        clearJobs()

        if (typewriter && text.isNotEmpty()) {
            _state.value = DialogueState(visible = true, text = "", progress = 1f)
            typewriterJob = scope.launch {
                val speed = when {
                    text.length > 50 -> 50L
                    else -> 80L
                }.coerceIn(30L, 100L)
                val sb = StringBuilder()
                for (ch in text) {
                    sb.append(ch)
                    _state.update { it.copy(text = sb.toString()) }
                    delay(speed)
                }
                if (durationMs > 0) startAutoHide(durationMs)
            }
        } else {
            _state.value = DialogueState(visible = true, text = text, progress = 1f)
            if (durationMs > 0) startAutoHide(durationMs)
        }
    }

    /**
     * 追加文本（流式对话增量）
     */
    fun appendText(delta: String) {
        if (!_state.value.visible) {
            _state.value = DialogueState(visible = true, text = delta, progress = 1f)
        } else {
            _state.update { it.copy(text = it.text + delta) }
        }
    }

    /**
     * 开始自动隐藏倒计时
     */
    fun startAutoHide(durationMs: Long) {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            val steps = 60
            val interval = durationMs / steps
            for (i in steps downTo 0) {
                _state.update { it.copy(progress = i.toFloat() / steps) }
                delay(interval)
            }
            hide()
        }
    }

    fun hide() {
        clearJobs()
        _state.value = DialogueState()
    }

    private fun clearJobs() {
        autoHideJob?.cancel()
        typewriterJob?.cancel()
    }
}
