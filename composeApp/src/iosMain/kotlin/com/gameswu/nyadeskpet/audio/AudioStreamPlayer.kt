@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.gameswu.nyadeskpet.audio

import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.util.DebugLog
import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.*
import platform.posix.memcpy

/**
 * iOS AudioStreamPlayer 实现。
 * 采用"累积-播放"策略：将流式音频数据累积到 NSMutableData，
 * endStream 时写入临时文件并使用 AVAudioPlayer 播放。
 * 通过 AVAudioPlayer 的 metering 功能实现唇形同步。
 */
@OptIn(ExperimentalForeignApi::class)
actual class AudioStreamPlayer actual constructor(private val context: PlatformContext) {
    private var audioData: NSMutableData? = null
    private var player: AVAudioPlayer? = null
    private var currentMimeType: String? = null
    private var currentVolume: Float = 1.0f
    private var lipSyncCallback: ((Float) -> Unit)? = null
    private var lipSyncTimer: NSTimer? = null

    actual fun startStream(mimeType: String) {
        stop()
        audioData = NSMutableData()
        currentMimeType = mimeType
    }

    actual fun appendChunk(base64Data: String) {
        val decoded = NSData.create(
            base64EncodedString = base64Data,
            options = 0u
        ) ?: return
        audioData?.appendData(decoded)
    }

    actual fun endStream() {
        val data = audioData ?: return
        audioData = null

        // 写入临时文件
        val tempDir = NSTemporaryDirectory()
        val ext = when {
            currentMimeType?.contains("mp3") == true -> "mp3"
            currentMimeType?.contains("wav") == true -> "wav"
            currentMimeType?.contains("ogg") == true -> "ogg"
            else -> "m4a"
        }
        val filePath = "${tempDir}nyadeskpet_audio.$ext"
        val fileUrl = NSURL.fileURLWithPath(filePath)
        data.writeToURL(fileUrl, atomically = true)

        // 创建并播放
        try {
            val audioPlayer = AVAudioPlayer(contentsOfURL = fileUrl, error = null)
            audioPlayer.volume = currentVolume
            audioPlayer.meteringEnabled = true
            audioPlayer.prepareToPlay()
            audioPlayer.play()
            player = audioPlayer

            // 启动唇形同步监控
            startLipSyncMonitoring()
        } catch (e: Exception) {
            DebugLog.e("AudioStreamPlayer") { "Error playing audio: ${e.message}" }
        }
    }

    actual fun setVolume(volume: Float) {
        currentVolume = volume
        player?.volume = volume
    }

    actual fun setLipSyncCallback(callback: (Float) -> Unit) {
        lipSyncCallback = callback
    }

    actual fun stop() {
        lipSyncTimer?.invalidate()
        lipSyncTimer = null
        lipSyncCallback?.invoke(0f)
        player?.stop()
        player = null
        audioData = null
    }

    private fun startLipSyncMonitoring() {
        lipSyncTimer?.invalidate()
        val callback = lipSyncCallback ?: return

        lipSyncTimer = NSTimer.scheduledTimerWithTimeInterval(
            interval = 1.0 / 30.0,
            repeats = true,
            block = { _ ->
                val p = player
                if (p != null && p.isPlaying()) {
                    p.updateMeters()
                    val power = p.averagePowerForChannel(0u)
                    // 将 dB 值 (-160~0) 映射到 0~1
                    val normalized = ((power + 50f) / 50f).coerceIn(0f, 1f)
                    callback(normalized)
                } else if (p != null && !p.isPlaying()) {
                    callback(0f)
                    lipSyncTimer?.invalidate()
                    lipSyncTimer = null
                }
            }
        )
    }
}
