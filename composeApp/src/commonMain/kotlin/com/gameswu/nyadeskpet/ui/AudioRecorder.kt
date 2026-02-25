package com.gameswu.nyadeskpet.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * 音频录制器状态
 */
enum class RecorderState {
    IDLE,       // 空闲
    RECORDING,  // 录音中
    ERROR,      // 出错
}

/**
 * 平台通用音频录制器接口 — 录制原始音频用于 Whisper API 等远程识别
 *
 * 对齐原项目 asr-service.ts 的音频输入方案：
 * 原项目通过浏览器 MediaRecorder API 录制 WebM → FFmpeg 转换 → 16kHz PCM
 * KMP 版直接录制 16kHz 16-bit Mono PCM，省去转换步骤
 *
 * 输出格式：WAV (16kHz, 16-bit, Mono) — 可直接送入 Whisper API
 */
expect class AudioRecorder() {
    /** 当前录制状态 */
    val state: StateFlow<RecorderState>

    /** 当前录音振幅 (0-100)，可用于 UI 展示 */
    val amplitude: StateFlow<Int>

    /**
     * 开始录音
     * @return true 表示成功开始
     */
    fun startRecording(): Boolean

    /**
     * 停止录音并返回 WAV 数据
     * @return WAV 格式的完整音频字节（含 44 字节头），null 表示录音失败
     */
    fun stopRecording(): ByteArray?

    /** 取消录音（不返回数据） */
    fun cancelRecording()

    /** 释放资源 */
    fun release()
}

/**
 * 将 PCM 数据（16kHz 16-bit Mono）封装为 WAV 格式
 */
fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
    val dataSize = pcmData.size
    val fileSize = 36 + dataSize
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8

    val header = ByteArray(44)
    // RIFF header
    header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
    header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
    writeIntLE(header, 4, fileSize)
    header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
    header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
    // fmt sub-chunk
    header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
    header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
    writeIntLE(header, 16, 16)       // SubChunk1Size (PCM = 16)
    writeShortLE(header, 20, 1)      // AudioFormat (PCM = 1)
    writeShortLE(header, 22, channels)
    writeIntLE(header, 24, sampleRate)
    writeIntLE(header, 28, byteRate)
    writeShortLE(header, 32, blockAlign)
    writeShortLE(header, 34, bitsPerSample)
    // data sub-chunk
    header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
    header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
    writeIntLE(header, 40, dataSize)

    return header + pcmData
}

private fun writeIntLE(arr: ByteArray, offset: Int, value: Int) {
    arr[offset] = (value and 0xFF).toByte()
    arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
    arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
    arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun writeShortLE(arr: ByteArray, offset: Int, value: Int) {
    arr[offset] = (value and 0xFF).toByte()
    arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
}
