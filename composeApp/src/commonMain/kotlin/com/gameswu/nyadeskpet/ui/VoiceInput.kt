package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable

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
