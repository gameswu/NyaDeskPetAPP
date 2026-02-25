package com.gameswu.nyadeskpet.agent.asr

import com.gameswu.nyadeskpet.currentTimeMillis
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Whisper ASR Provider — 通过 OpenAI Whisper API（或兼容 API）进行语音识别
 *
 * 对齐原项目 asr-service.ts 的远程识别方案
 * 原项目使用 Sherpa-ONNX 本地识别，KMP 版改为 API 方案（Whisper / Groq / 通义听悟等）
 *
 * API 调用方式：POST /v1/audio/transcriptions (multipart/form-data)
 * - file: 音频文件（WAV 格式）
 * - model: 模型名称（whisper-1）
 * - language: 语言代码（可选）
 */

@Serializable
private data class WhisperTranscriptionResponse(
    val text: String,
)

@Serializable
private data class WhisperErrorDetail(val message: String? = null, val type: String? = null)

@Serializable
private data class WhisperErrorResponse(val error: WhisperErrorDetail? = null)

class WhisperASRProvider(private val config: ASRProviderConfig) : ASRProvider {

    override val metadata = WHISPER_ASR_METADATA

    private val httpClient = HttpClient { expectSuccess = false }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun initialize() { /* stateless */ }

    override suspend fun terminate() {
        httpClient.close()
    }

    /**
     * 通过 Whisper API 识别音频
     *
     * @param audioData WAV 格式的完整音频（含 44 字节 WAV 头 + PCM 数据，16kHz 16-bit mono）
     * @param language 语言代码，null = 自动检测
     */
    override suspend fun recognize(audioData: ByteArray, language: String?): ASRResult? {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.openai.com/v1"
        val apiKey = config.apiKey ?: throw IllegalArgumentException("Whisper API 需要 API Key")
        val model = config.model ?: "whisper-1"
        val lang = language ?: config.language

        val startTime = currentTimeMillis()

        val response = httpClient.submitFormWithBinaryData(
            url = "$baseUrl/audio/transcriptions",
            formData = formData {
                append("file", audioData, Headers.build {
                    append(HttpHeaders.ContentType, "audio/wav")
                    append(HttpHeaders.ContentDisposition, "filename=\"recording.wav\"")
                })
                append("model", model)
                if (!lang.isNullOrBlank()) {
                    append("language", lang)
                }
                append("response_format", "json")
            }
        ) {
            header("Authorization", "Bearer $apiKey")
        }

        val elapsed = currentTimeMillis() - startTime

        if (!response.status.isSuccess()) {
            val errorText = response.bodyAsText()
            val errorMsg = try {
                json.decodeFromString(WhisperErrorResponse.serializer(), errorText).error?.message
                    ?: errorText
            } catch (_: Exception) {
                errorText
            }
            val readableError = when (response.status.value) {
                401 -> "API Key 无效"
                413 -> "音频文件过大（限制 25MB）"
                429 -> "请求频率过高或配额已用尽"
                else -> "HTTP ${response.status.value}: $errorMsg"
            }
            throw Exception("Whisper ASR 识别失败: $readableError")
        }

        val body = response.bodyAsText()
        val result = json.decodeFromString(WhisperTranscriptionResponse.serializer(), body)

        if (result.text.isBlank()) return null

        return ASRResult(
            text = result.text.trim(),
            durationMs = elapsed,
        )
    }
}

// ==================== Metadata ====================

val WHISPER_ASR_METADATA = ASRProviderMetadata(
    id = "whisper",
    name = "Whisper API",
    description = "OpenAI Whisper 语音识别 API，支持多语种自动检测，兼容 Groq、通义听悟等兼容 API",
    requiresApiKey = true,
    requiresRecording = true,
)

// ==================== 注册 ====================

fun registerWhisperASRProvider() {
    ASRProviderRegistry.register(WHISPER_ASR_METADATA) { config ->
        WhisperASRProvider(config)
    }
}
