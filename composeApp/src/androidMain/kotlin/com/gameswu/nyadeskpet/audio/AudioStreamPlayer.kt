package com.gameswu.nyadeskpet.audio

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.gameswu.nyadeskpet.PlatformContext
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.sqrt

/**
 * Android 平台的流式音频播放器实现
 *
 * 使用 ByteArrayOutputStream 缓冲完整音频数据，在 endStream() 时通过
 * ByteArrayDataSource 交给 ExoPlayer 播放。避免 PipedStream 竞态导致的
 * UnrecognizedInputFormatException。
 */
@OptIn(UnstableApi::class)
actual class AudioStreamPlayer actual constructor(private val context: PlatformContext) {

    companion object {
        private const val TAG = "AudioStreamPlayer"
    }

    private var player: ExoPlayer? = null
    private var audioBuffer: ByteArrayOutputStream? = null
    private var currentMimeType: String = "audio/mpeg"
    private var currentVolume: Float = 0.8f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lipSyncCallback: ((Float) -> Unit)? = null
    private var lipSyncJob: Job? = null
    @Volatile
    private var isPlayerPlaying = false

    actual fun startStream(mimeType: String) {
        stop()
        currentMimeType = mimeType
        audioBuffer = ByteArrayOutputStream()
    }

    @OptIn(ExperimentalEncodingApi::class)
    actual fun appendChunk(base64Data: String) {
        try {
            val bytes = Base64.decode(base64Data)
            audioBuffer?.write(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "appendChunk error: ${e.message}")
        }
    }

    actual fun endStream() {
        val buffer = audioBuffer
        audioBuffer = null
        val audioData = buffer?.toByteArray()
        if (audioData == null || audioData.isEmpty()) {
            Log.d(TAG, "endStream: no audio data to play")
            return
        }

        Log.d(TAG, "endStream: ${audioData.size} bytes, mime=$currentMimeType, header=${audioData.take(8).joinToString(" ") { "%02X".format(it) }}")

        val exoMimeType = when {
            currentMimeType.contains("mpeg") || currentMimeType.contains("mp3") -> MimeTypes.AUDIO_MPEG
            currentMimeType.contains("ogg") -> MimeTypes.AUDIO_OGG
            currentMimeType.contains("wav") -> MimeTypes.AUDIO_WAV
            currentMimeType.contains("opus") -> MimeTypes.AUDIO_OPUS
            currentMimeType.contains("flac") -> MimeTypes.AUDIO_FLAC
            else -> MimeTypes.AUDIO_MPEG
        }

        player = ExoPlayer.Builder(context).build().apply {
            volume = currentVolume
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    isPlayerPlaying = isPlaying
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        isPlayerPlaying = false
                        lipSyncCallback?.invoke(0f)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error: ${error.message}")
                    isPlayerPlaying = false
                    lipSyncCallback?.invoke(0f)
                }
            })
        }

        val dataSource = ByteArrayDataSource(audioData)
        val mediaSource = ProgressiveMediaSource.Factory { dataSource }
            .createMediaSource(MediaItem.fromUri("data://audio"))
        player?.setMediaSource(mediaSource)
        player?.prepare()
        player?.play()
        startLipSyncAnalysis()
    }

    actual fun setVolume(volume: Float) {
        currentVolume = volume
        player?.volume = volume
    }

    actual fun setLipSyncCallback(callback: (Float) -> Unit) {
        this.lipSyncCallback = callback
    }

    actual fun stop() {
        lipSyncJob?.cancel()
        isPlayerPlaying = false
        lipSyncCallback?.invoke(0f)
        audioBuffer = null
        player?.stop()
        player?.release()
        player = null
    }

    private fun startLipSyncAnalysis() {
        lipSyncJob?.cancel()
        // 必须在主线程获取 audioSessionId（ExoPlayer 要求）
        val audioSessionId = player?.audioSessionId ?: return
        if (audioSessionId == 0) return

        lipSyncJob = scope.launch {
            // 等待播放器真正开始播放
            for (i in 1..30) { // max 1.5s
                if (isPlayerPlaying) break
                delay(50)
            }
            if (!isPlayerPlaying) return@launch

            // 先检查 RECORD_AUDIO 运行时权限（Android 6.0+ 危险权限需动态申请）
            val hasRecordPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasRecordPermission) {
                Log.d(TAG, "RECORD_AUDIO 权限未授予，Visualizer 不可用，使用模拟唇形")
            }

            // 尝试使用 Visualizer（需要 RECORD_AUDIO 权限，部分设备/ROM 不支持）
            val visualizer = if (hasRecordPermission) {
                try {
                    android.media.audiofx.Visualizer(audioSessionId).apply {
                        captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0]
                        enabled = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Visualizer 初始化失败: ${e.message}，使用模拟唇形")
                    null
                }
            } else null

            if (visualizer != null) {
                // ===== Visualizer 模式：真实音频分析 =====
                try {
                    val waveform = ByteArray(visualizer.captureSize)
                    while (isActive && isPlayerPlaying) {
                        visualizer.getWaveForm(waveform)
                        var sum = 0.0
                        for (b in waveform) {
                            val amplitude = (b.toInt() and 0xFF) - 128
                            sum += amplitude * amplitude
                        }
                        val rms = sqrt(sum / waveform.size).toFloat()
                        val lipValue = (rms / 35f).coerceIn(0f, 1f)

                        withContext(Dispatchers.Main) {
                            lipSyncCallback?.invoke(lipValue)
                        }
                        delay(16)
                    }
                } finally {
                    try { visualizer.release() } catch (_: Exception) {}
                }
            } else {
                // ===== Fallback 模式：基于时间的模拟唇形动画 =====
                // 使用正弦波 + 随机扰动模拟说话时的嘴型变化
                val random = kotlin.random.Random
                var phase = 0.0
                while (isActive && isPlayerPlaying) {
                    // 基础正弦波（模拟说话节奏）+ 随机噪声
                    phase += 0.15 + random.nextDouble(0.0, 0.05)
                    val base = (kotlin.math.sin(phase) * 0.5 + 0.5).toFloat()
                    val noise = random.nextFloat() * 0.2f
                    val lipValue = (base * 0.7f + noise).coerceIn(0f, 1f)

                    withContext(Dispatchers.Main) {
                        lipSyncCallback?.invoke(lipValue)
                    }
                    delay(50) // ~20fps，足够平滑
                }
            }

            withContext(Dispatchers.Main) {
                lipSyncCallback?.invoke(0f)
            }
        }
    }
}
