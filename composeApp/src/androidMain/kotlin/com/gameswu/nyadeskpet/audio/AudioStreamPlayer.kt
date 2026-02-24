package com.gameswu.nyadeskpet.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.gameswu.nyadeskpet.PlatformContext
import kotlinx.coroutines.*
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.sqrt

/**
 * Android 平台的流式音频播放器实现
 */
@OptIn(UnstableApi::class)
actual class AudioStreamPlayer actual constructor(private val context: PlatformContext) {

    private var player: ExoPlayer? = null
    private var pipedOutput: PipedOutputStream? = null
    private var pipedInput: PipedInputStream? = null
    private var currentVolume: Float = 0.8f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lipSyncCallback: ((Float) -> Unit)? = null
    private var lipSyncJob: Job? = null

    actual fun startStream(mimeType: String) {
        stop()

        pipedInput = PipedInputStream(65536)
        pipedOutput = PipedOutputStream(pipedInput)

        val exoMimeType = when {
            mimeType.contains("mpeg") || mimeType.contains("mp3") -> MimeTypes.AUDIO_MPEG
            mimeType.contains("ogg") -> MimeTypes.AUDIO_OGG
            mimeType.contains("wav") -> MimeTypes.AUDIO_WAV
            else -> MimeTypes.AUDIO_MPEG
        }

        player = ExoPlayer.Builder(context).build().apply {
            volume = currentVolume
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        lipSyncCallback?.invoke(0f)
                    }
                }
            })
        }

        scope.launch {
            delay(200) // 等待缓冲区
            withContext(Dispatchers.Main) {
                val dataSource = InputStreamDataSource(pipedInput!!)
                val mediaSource = ProgressiveMediaSource.Factory { dataSource }
                    .createMediaSource(MediaItem.fromUri("pipe://audio"))
                player?.setMediaSource(mediaSource)
                player?.prepare()
                player?.play()
                startLipSyncAnalysis()
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    actual fun appendChunk(base64Data: String) {
        scope.launch {
            try {
                val bytes = Base64.decode(base64Data)
                pipedOutput?.write(bytes)
                pipedOutput?.flush()
            } catch (e: Exception) { }
        }
    }

    actual fun endStream() {
        scope.launch {
            try {
                pipedOutput?.close()
                pipedOutput = null
            } catch (e: Exception) { }
        }
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
        lipSyncCallback?.invoke(0f)
        try {
            pipedOutput?.close()
            pipedInput?.close()
        } catch (e: Exception) { }
        player?.stop()
        player?.release()
        player = null
    }

    private fun startLipSyncAnalysis() {
        lipSyncJob?.cancel()
        lipSyncJob = scope.launch {
            val audioSessionId = player?.audioSessionId ?: return@launch
            if (audioSessionId == 0) return@launch
            
            try {
                val visualizer = android.media.audiofx.Visualizer(audioSessionId).apply {
                    captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0]
                    enabled = true
                }
                val waveform = ByteArray(visualizer.captureSize)
                
                while (isActive && player?.isPlaying == true) {
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
                visualizer.release()
            } catch (e: Exception) { }
        }
    }
}

@UnstableApi
private class InputStreamDataSource(private val inputStream: PipedInputStream) : androidx.media3.datasource.DataSource {
    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long = -1
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inputStream.read(buffer, offset, length)
    override fun getUri(): android.net.Uri? = android.net.Uri.parse("pipe://audio")
    override fun close() {}
}
