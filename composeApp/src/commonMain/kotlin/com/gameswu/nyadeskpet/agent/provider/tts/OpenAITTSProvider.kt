/**
 * OpenAI TTS Provider
 * 对齐原 Electron 项目 src/agent/tts-providers/openai-tts.ts
 *
 * 通过 OpenAI 或兼容 API 的 /v1/audio/speech 端点进行语音合成
 *
 * 特性：
 * - 6 种预制音色：alloy、echo、fable、onyx、nova、shimmer
 * - 2 种模型：tts-1（低延迟）、tts-1-hd（高质量）
 * - 支持 MP3、Opus、AAC、FLAC、WAV、PCM 格式
 * - 语速调节（0.25 - 4.0）
 * - 兼容所有 OpenAI TTS 兼容 API
 */
package com.gameswu.nyadeskpet.agent.provider.tts

import com.gameswu.nyadeskpet.agent.provider.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ==================== 内部请求类型 ====================

@Serializable
internal data class OpenAITTSRequestBody(
    val model: String,
    val input: String,
    val voice: String,
    @SerialName("response_format") val responseFormat: String? = null,
    val speed: Float? = null,
)

// ==================== 预制音色列表 ====================

private val OPENAI_TTS_VOICES = listOf(
    VoiceInfo(id = "alloy", name = "Alloy", description = "中性、平衡的声音"),
    VoiceInfo(id = "echo", name = "Echo", description = "温暖、清晰的男声"),
    VoiceInfo(id = "fable", name = "Fable", description = "富有表现力的英式口音"),
    VoiceInfo(id = "onyx", name = "Onyx", description = "深沉、有力的男声"),
    VoiceInfo(id = "nova", name = "Nova", description = "友好、自然的女声"),
    VoiceInfo(id = "shimmer", name = "Shimmer", description = "明亮、活泼的女声"),
)

// ==================== OpenAI TTS Provider 实现 ====================

class OpenAITTSProvider(config: ProviderConfig) : TTSProvider(config) {

    private val httpClient = buildHttpClient()

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    override fun getMetadata(): ProviderMetadata = OPENAI_TTS_METADATA

    override suspend fun initialize() {
        initialized = true
    }

    override suspend fun terminate() {
        httpClient.close()
        initialized = false
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun synthesize(request: TTSRequest): TTSResponse {
        val baseUrl = getConfigValue("baseUrl", "https://api.openai.com/v1")
        val apiKey = getConfigValue<String?>("apiKey", null)
        val voice = request.voiceId ?: getConfigValue("voiceId", "alloy")
        val model = getConfigValue("model", "tts-1")
        val format = request.format ?: getConfigValue("format", "mp3")
        val speed = request.speed ?: getConfigValue<Float?>("speed", null)

        val body = OpenAITTSRequestBody(
            model = model,
            input = request.text,
            voice = voice,
            responseFormat = format,
            speed = speed,
        )

        val response = httpClient.post("$baseUrl/audio/speech") {
            apiKey?.let { header("Authorization", "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpenAITTSRequestBody.serializer(), body))
        }

        if (!response.status.isSuccess()) {
            val errorText = response.bodyAsText()
            val errorMsg = when (response.status.value) {
                401 -> "API Key 无效"
                429 -> "请求频率过高或配额已用尽"
                else -> "HTTP ${response.status.value}: $errorText"
            }
            throw Exception("OpenAI TTS 合成失败: $errorMsg")
        }

        val audioBytes = response.readRawBytes()
        val audioBase64 = Base64.encode(audioBytes)

        return TTSResponse(
            audioBase64 = audioBase64,
            mimeType = getMimeType(format),
        )
    }

    override suspend fun getVoices(): List<VoiceInfo> = OPENAI_TTS_VOICES

    override suspend fun test(): TestResult {
        return try {
            if (!initialized) initialize()
            synthesize(TTSRequest(text = "test", voiceId = "alloy"))
            TestResult(true)
        } catch (e: Exception) {
            TestResult(false, e.message)
        }
    }

    private fun getMimeType(format: String?): String = audioFormatToMimeType(format)
}

// ==================== Provider 元信息 ====================

val OPENAI_TTS_METADATA = ProviderMetadata(
    id = "openai-tts",
    name = "OpenAI TTS",
    description = "OpenAI 语音合成 API，6 种预制音色，支持高质量（tts-1-hd）和低延迟（tts-1）模式，多种音频格式，兼容 OpenAI TTS API 的其他服务也可使用",
    configSchema = listOf(
        ProviderConfigField(
            key = "apiKey", label = "API Key", type = "password",
            required = true, placeholder = "sk-...",
            description = "从 OpenAI 或兼容服务商获取的 API 密钥",
        ),
        ProviderConfigField(
            key = "baseUrl", label = "API Base URL", type = "string",
            default = "https://api.openai.com/v1", placeholder = "https://api.openai.com/v1",
            description = "API 地址。兼容服务可修改此地址",
        ),
        ProviderConfigField(
            key = "voiceId", label = "音色", type = "string",
            default = "alloy", placeholder = "alloy",
            description = "音色 ID，如 alloy、echo、fable、onyx、nova、shimmer",
        ),
        ProviderConfigField(
            key = "model", label = "TTS 模型", type = "string",
            default = "tts-1", placeholder = "tts-1",
            description = "模型 ID，如 tts-1（低延迟）、tts-1-hd（高音质）",
        ),
        ProviderConfigField(
            key = "format", label = "音频格式", type = "string",
            default = "mp3", placeholder = "mp3",
            description = "输出格式，如 mp3、opus、aac、flac、wav、pcm",
        ),
        ProviderConfigField(
            key = "speed", label = "语速", type = "number",
            default = "1.0",
            description = "语速调节，范围 0.25 - 4.0",
        ),
        ProviderConfigField(
            key = "timeout", label = "超时时间（秒）", type = "number",
            default = "60", description = "请求超时时间",
        ),
        ProviderConfigField(
            key = "proxy", label = "代理地址", type = "string",
            placeholder = "http://127.0.0.1:7890",
            description = "HTTP/HTTPS 代理（如需翻墙访问）",
        ),
    ),
)