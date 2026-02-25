package com.gameswu.nyadeskpet.ui

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Android 音频录制器实现
 *
 * 使用 AudioRecord 录制 16kHz 16-bit Mono PCM
 * 输出为 WAV 格式，可直接送入 Whisper API
 *
 * 注意：调用方需确保 RECORD_AUDIO 权限已授予
 */
actual class AudioRecorder actual constructor() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val _state = MutableStateFlow(RecorderState.IDLE)
    actual val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    actual val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmOutputStream: ByteArrayOutputStream? = null

    @Volatile
    private var isRecording = false

    actual fun startRecording(): Boolean {
        if (_state.value == RecorderState.RECORDING) return false

        // 调用方负责确保 RECORD_AUDIO 权限已授予

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            _state.value = RecorderState.ERROR
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                _state.value = RecorderState.ERROR
                return false
            }

            pcmOutputStream = ByteArrayOutputStream()
            isRecording = true
            _state.value = RecorderState.RECORDING

            audioRecord?.startRecording()

            recordingThread = thread(name = "AudioRecorder") {
                val buffer = ShortArray(bufferSize / 2)
                val byteBuffer = ByteArray(bufferSize)

                while (isRecording) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readCount > 0) {
                        // 计算振幅 (0-100)
                        var maxAmp = 0
                        for (i in 0 until readCount) {
                            val v = abs(buffer[i].toInt())
                            if (v > maxAmp) maxAmp = v
                        }
                        _amplitude.value = (maxAmp * 100 / Short.MAX_VALUE).coerceIn(0, 100)

                        // Short[] → ByteArray (Little Endian)
                        for (i in 0 until readCount) {
                            byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                        }
                        pcmOutputStream?.write(byteBuffer, 0, readCount * 2)
                    }
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            _state.value = RecorderState.ERROR
            return false
        }
    }

    actual fun stopRecording(): ByteArray? {
        if (_state.value != RecorderState.RECORDING) return null

        isRecording = false

        try {
            recordingThread?.join(2000)
        } catch (_: InterruptedException) {
            // ignore
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // ignore
        }

        val pcmData = pcmOutputStream?.toByteArray()
        cleanup()
        _state.value = RecorderState.IDLE
        _amplitude.value = 0

        if (pcmData == null || pcmData.isEmpty()) return null

        // PCM → WAV
        return pcmToWav(pcmData, SAMPLE_RATE, 1, 16)
    }

    actual fun cancelRecording() {
        isRecording = false
        try {
            recordingThread?.join(2000)
        } catch (_: InterruptedException) {
            // ignore
        }
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // ignore
        }
        cleanup()
        _state.value = RecorderState.IDLE
        _amplitude.value = 0
    }

    actual fun release() {
        cancelRecording()
    }

    private fun cleanup() {
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
        pcmOutputStream = null
    }
}
