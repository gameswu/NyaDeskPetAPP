package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.*
import com.gameswu.nyadeskpet.agent.asr.*
import kotlinx.coroutines.launch

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
 * 双模式语音输入 — 根据 asrMode 自动切换系统识别器或 Whisper API
 *
 * - "system": 使用平台原生语音识别（SpeechRecognizer / SFSpeechRecognizer）
 * - "whisper": 使用 AudioRecorder 录音 + Whisper API 识别
 *
 * 返回统一的 VoiceInputController 接口，上层无需关心具体实现
 */
@Composable
fun rememberDualModeVoiceInput(
    asrMode: String,
    asrApiKey: String,
    asrBaseUrl: String,
    asrModel: String,
    asrLanguage: String,
    onResult: (String) -> Unit,
    onPartialResult: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    locale: String = "",
): VoiceInputController {
    // 系统模式 — 走原有平台识别器
    val systemVoiceInput = rememberVoiceInput(
        onResult = onResult,
        onPartialResult = onPartialResult,
        onError = onError,
        locale = locale,
    )

    // Whisper 模式状态
    var whisperListening by remember { mutableStateOf(false) }
    val recorder = remember { AudioRecorder() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            recorder.release()
        }
    }

    // 如果当前是系统模式，直接返回系统控制器
    if (asrMode != "whisper") {
        return systemVoiceInput
    }

    // Whisper 模式控制器
    return VoiceInputController(
        isAvailable = true,
        isListening = whisperListening,
        startListening = {
            if (!whisperListening) {
                val started = recorder.startRecording()
                if (started) {
                    whisperListening = true
                } else {
                    onError("无法启动录音")
                }
            }
        },
        stopListening = {
            if (whisperListening) {
                whisperListening = false
                val wavData = recorder.stopRecording()
                if (wavData == null || wavData.size <= 44) {
                    onError("录音数据为空")
                    return@VoiceInputController
                }

                // 异步调用 Whisper API
                scope.launch {
                    try {
                        val config = ASRProviderConfig(
                            apiKey = asrApiKey.ifBlank { null },
                            baseUrl = asrBaseUrl.ifBlank { null },
                            model = asrModel.ifBlank { null },
                            language = asrLanguage.ifBlank { null },
                        )
                        val provider = WhisperASRProvider(config)
                        val result = provider.recognize(wavData, asrLanguage.ifBlank { null })
                        if (result != null && result.text.isNotBlank()) {
                            onResult(result.text)
                        } else {
                            onError("Whisper 未识别到文本")
                        }
                    } catch (e: Exception) {
                        onError("Whisper 识别失败: ${e.message}")
                    }
                }
            }
        },
    )
}

