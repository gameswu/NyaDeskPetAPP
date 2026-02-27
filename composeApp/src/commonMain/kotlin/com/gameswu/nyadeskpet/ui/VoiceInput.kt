package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.*

/**
 * 语音输入控制器状态。
 */
class VoiceInputController(
    /** 当前设备是否支持语音识别 */
    val isAvailable: Boolean,
    /** 是否正在录音 */
    val isListening: Boolean,
    /** 开始语音识别 */
    val startListening: () -> Unit,
    /** 停止语音识别 */
    val stopListening: () -> Unit,
)

/**
 * 创建并记住一个语音输入控制器，内部封装平台语音识别 API。
 *
 * @param onResult     识别完成后的最终文本回调
 * @param onPartialResult 实时中间结果回调
 * @param onError      出错回调
 * @param locale       识别语言（如 "zh-CN"），空字符串使用系统默认
 */
@Composable
expect fun rememberVoiceInput(
    onResult: (String) -> Unit,
    onPartialResult: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    locale: String = "",
): VoiceInputController

/**
 * 双模式语音输入 — 支持本地模型 ASR
 *
 * - "local": 使用本地 ASR 模型（如 GGML / ONNX Whisper 模型）
 *   当本地模型未导入时，回退到平台原生语音识别作为临时实现
 *
 * 返回统一的 VoiceInputController 接口，上层无需关心具体实现
 */
@Composable
fun rememberDualModeVoiceInput(
    asrMode: String,
    asrModelPath: String,
    asrLanguage: String,
    onResult: (String) -> Unit,
    onPartialResult: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    locale: String = "",
): VoiceInputController {
    // 本地模式 — 当前回退到平台原生识别器
    // TODO: 当 asrModelPath 不为空时，使用本地 Whisper 推理（sherpa-onnx / whisper.cpp）
    val systemVoiceInput = rememberVoiceInput(
        onResult = onResult,
        onPartialResult = onPartialResult,
        onError = onError,
        locale = locale,
    )

    return systemVoiceInput
}

