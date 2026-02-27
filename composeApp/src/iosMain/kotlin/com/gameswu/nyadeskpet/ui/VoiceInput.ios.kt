@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.*
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.setActive
import platform.Speech.*
import platform.Foundation.NSLocale

/**
 * iOS 语音输入实现。
 * 使用 SFSpeechRecognizer + AVAudioEngine 进行实时语音识别。
 * 需要在 Info.plist 中添加:
 *  - NSSpeechRecognitionUsageDescription
 *  - NSMicrophoneUsageDescription
 */
@Composable
actual fun rememberVoiceInput(
    onResult: (String) -> Unit,
    onPartialResult: (String) -> Unit,
    onError: (String) -> Unit,
    locale: String,
): VoiceInputController {
    var isListening by remember { mutableStateOf(false) }
    var audioEngine: AVAudioEngine? by remember { mutableStateOf(null) }
    var recognitionTask: SFSpeechRecognitionTask? by remember { mutableStateOf(null) }
    val recognizer = remember {
        if (locale.isNotBlank()) {
            SFSpeechRecognizer(locale = NSLocale(localeIdentifier = locale))
        } else {
            SFSpeechRecognizer()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioEngine?.stop()
            recognitionTask?.cancel()
        }
    }

    return VoiceInputController(
        isAvailable = recognizer.isAvailable(),
        isListening = isListening,
        startListening = {
            if (isListening) return@VoiceInputController
            val rec = recognizer

            SFSpeechRecognizer.requestAuthorization { status ->
                if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    onError("语音识别权限被拒绝")
                    return@requestAuthorization
                }

                try {
                    val request = SFSpeechAudioBufferRecognitionRequest()
                    request.setShouldReportPartialResults(true)

                    val engine = AVAudioEngine()
                    val inputNode = engine.inputNode
                    val format = inputNode.outputFormatForBus(0u)

                    recognitionTask = rec.recognitionTaskWithRequest(request) { result, error ->
                        if (error != null || result?.isFinal() == true) {
                            engine.stop()
                            inputNode.removeTapOnBus(0u)
                            isListening = false
                            audioEngine = null
                            recognitionTask = null
                            result?.bestTranscription?.formattedString?.let {
                                if (it.isNotBlank()) onResult(it)
                            }
                        } else {
                            result?.bestTranscription?.formattedString?.let {
                                if (it.isNotBlank()) onPartialResult(it)
                            }
                        }
                    }

                    inputNode.installTapOnBus(
                        0u,
                        bufferSize = 1024u,
                        format = format
                    ) { buffer, _ ->
                        buffer?.let { request.appendAudioPCMBuffer(it) }
                    }

                    val audioSession = AVAudioSession.sharedInstance()
                    audioSession.setCategory(AVAudioSessionCategoryRecord, error = null)
                    audioSession.setActive(true, error = null)

                    engine.prepare()
                    engine.startAndReturnError(null)
                    audioEngine = engine
                    isListening = true
                } catch (e: Exception) {
                    onError("语音识别启动失败: ${e.message}")
                    isListening = false
                }
            }
        },
        stopListening = {
            audioEngine?.stop()
            audioEngine?.inputNode?.removeTapOnBus(0u)
            recognitionTask?.cancel()
            audioEngine = null
            recognitionTask = null
            isListening = false
        },
    )
}
