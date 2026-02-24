package com.gameswu.nyadeskpet.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/**
 * Android 语音输入实现。
 * 使用 SpeechRecognizer API 进行实时语音识别，需要 RECORD_AUDIO 权限。
 */
@Composable
actual fun rememberVoiceInput(
    onResult: (String) -> Unit,
    onPartialResult: (String) -> Unit,
    onError: (String) -> Unit,
    locale: String,
): VoiceInputController {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    val isAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    var recognizerRef by remember { mutableStateOf<SpeechRecognizer?>(null) }

    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) onError("麦克风权限被拒绝")
    }

    // 组件销毁时释放 SpeechRecognizer
    DisposableEffect(Unit) {
        onDispose {
            recognizerRef?.destroy()
        }
    }

    return VoiceInputController(
        isAvailable = isAvailable,
        isListening = isListening,
        startListening = {
            if (!hasPermission) {
                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else if (!isListening && isAvailable) {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizerRef = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: ""
                        isListening = false
                        recognizer.destroy()
                        recognizerRef = null
                        if (text.isNotBlank()) onResult(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: ""
                        if (text.isNotBlank()) onPartialResult(text)
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        recognizer.destroy()
                        recognizerRef = null
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音输入"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器繁忙"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                            else -> "识别错误: $error"
                        }
                        onError(msg)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    if (locale.isNotBlank()) {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                    }
                }

                recognizer.startListening(intent)
                isListening = true
            }
        },
        stopListening = {
            recognizerRef?.stopListening()
            recognizerRef?.destroy()
            recognizerRef = null
            isListening = false
        },
    )
}
