package com.gameswu.nyadeskpet.agent

import com.gameswu.nyadeskpet.audio.AudioStreamPlayer
import com.gameswu.nyadeskpet.dialogue.DialogueManager

data class ResponseSession(
    val responseId: String,
    val priority: Int,
    var hasActiveAudio: Boolean = false
)

class ResponseController {
    private var currentSession: ResponseSession? = null
    private val discardedIds = mutableSetOf<String>()

    // 可选联动引用（由 Koin 注入后通过 setter 注入，避免循环依赖）
    var audioPlayer: AudioStreamPlayer? = null
    var dialogueManager: DialogueManager? = null

    fun shouldAccept(responseId: String, priority: Int): Boolean {
        val current = currentSession
        if (current == null || current.responseId == responseId) {
            currentSession = ResponseSession(responseId, priority)
            return true
        }
        // 高优先级可中断低优先级
        return if (priority >= current.priority) {
            interruptCurrent()
            currentSession = ResponseSession(responseId, priority)
            true
        } else {
            discardedIds.add(responseId)
            false
        }
    }

    fun markAudioActive() {
        currentSession?.hasActiveAudio = true
    }

    fun notifyComplete(responseId: String) {
        if (currentSession?.responseId == responseId) {
            currentSession = null
        }
        discardedIds.remove(responseId)
    }

    /**
     * 中断当前回复：停止音频播放、清除对话气泡
     */
    fun interruptCurrent() {
        val session = currentSession ?: return
        if (session.hasActiveAudio) {
            audioPlayer?.stop()
        }
        dialogueManager?.hide()
        discardedIds.add(session.responseId)
        currentSession = null
    }
}