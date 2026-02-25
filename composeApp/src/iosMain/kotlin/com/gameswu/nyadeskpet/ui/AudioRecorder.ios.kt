package com.gameswu.nyadeskpet.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.*
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.math.abs

/**
 * iOS 音频录制器实现
 *
 * 使用 AVAudioEngine 录制 16kHz 16-bit Mono PCM
 * 输出为 WAV 格式，可直接送入 Whisper API
 *
 * 注意：调用方需确保麦克风权限已授予
 * Info.plist 需要 NSMicrophoneUsageDescription
 */
actual class AudioRecorder actual constructor() {

    private val _state = MutableStateFlow(RecorderState.IDLE)
    actual val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    actual val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private var audioEngine: AVAudioEngine? = null
    private var pcmBuffers = mutableListOf<ByteArray>()

    actual fun startRecording(): Boolean {
        if (_state.value == RecorderState.RECORDING) return false

        try {
            val engine = AVAudioEngine()
            val inputNode = engine.inputNode

            // 配置音频会话
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryRecord, error = null)
            session.setActive(true, error = null)

            // 获取输入格式并安装 tap
            val nativeFormat = inputNode.outputFormatForBus(0u)

            // 我们需要 16kHz 16-bit Mono PCM，使用转换格式
            val targetFormat = AVAudioFormat(
                commonFormat = AVAudioPCMFormatInt16,
                sampleRate = 16000.0,
                channels = 1u,
                interleaved = true
            )

            // 安装 format converter
            val converter = AVAudioConverter(fromFormat = nativeFormat, toFormat = targetFormat!!)

            pcmBuffers.clear()

            inputNode.installTapOnBus(
                bus = 0u,
                bufferSize = 4096u,
                format = nativeFormat
            ) { buffer, _ ->
                if (buffer == null) return@installTapOnBus

                // 转换采样率和格式
                val convertedBuffer = AVAudioPCMBuffer(
                    pCMFormat = targetFormat,
                    frameCapacity = 4096u
                ) ?: return@installTapOnBus

                try {
                    var error: kotlinx.cinterop.ObjCObjectVar<platform.Foundation.NSError?>? = null
                    converter?.convertToBuffer(
                        outputBuffer = convertedBuffer,
                        error = null,
                        withInputFromBlock = { _, outStatus ->
                            // 提供输入数据
                            outStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                            buffer
                        }
                    )

                    val frameLength = convertedBuffer.frameLength.toInt()
                    if (frameLength > 0) {
                        // 提取 int16 PCM 数据
                        val int16Data = convertedBuffer.int16ChannelData
                        if (int16Data != null) {
                            val channelData = int16Data[0] ?: return@installTapOnBus
                            val byteArray = ByteArray(frameLength * 2)
                            for (i in 0 until frameLength) {
                                val sample = channelData[i]
                                byteArray[i * 2] = (sample.toInt() and 0xFF).toByte()
                                byteArray[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                            }
                            pcmBuffers.add(byteArray)

                            // 计算振幅
                            var maxAmp = 0
                            for (i in 0 until frameLength) {
                                val v = abs(channelData[i].toInt())
                                if (v > maxAmp) maxAmp = v
                            }
                            _amplitude.value = (maxAmp * 100 / Short.MAX_VALUE).coerceIn(0, 100)
                        }
                    }
                } catch (_: Exception) {
                    // 转换失败，跳过此帧
                }
            }

            engine.prepare()
            engine.startAndReturnError(null)
            audioEngine = engine
            _state.value = RecorderState.RECORDING
            return true
        } catch (e: Exception) {
            cleanup()
            _state.value = RecorderState.ERROR
            return false
        }
    }

    actual fun stopRecording(): ByteArray? {
        if (_state.value != RecorderState.RECORDING) return null

        audioEngine?.stop()
        audioEngine?.inputNode?.removeTapOnBus(0u)

        // 合并所有 PCM 缓冲区
        val totalSize = pcmBuffers.sumOf { it.size }
        if (totalSize == 0) {
            cleanup()
            _state.value = RecorderState.IDLE
            return null
        }

        val pcmData = ByteArray(totalSize)
        var offset = 0
        for (buf in pcmBuffers) {
            buf.copyInto(pcmData, offset)
            offset += buf.size
        }

        cleanup()
        _state.value = RecorderState.IDLE
        _amplitude.value = 0

        return pcmToWav(pcmData, sampleRate = 16000, channels = 1, bitsPerSample = 16)
    }

    actual fun cancelRecording() {
        audioEngine?.stop()
        audioEngine?.inputNode?.removeTapOnBus(0u)
        cleanup()
        _state.value = RecorderState.IDLE
        _amplitude.value = 0
    }

    actual fun release() {
        cancelRecording()
    }

    private fun cleanup() {
        audioEngine = null
        pcmBuffers.clear()
    }
}
