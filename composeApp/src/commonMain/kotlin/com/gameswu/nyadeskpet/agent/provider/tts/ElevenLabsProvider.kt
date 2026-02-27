/**
 * ElevenLabs TTS Provider
 * 对齐原 Electron 项目 src/agent/tts-providers/elevenlabs.ts
 *
 * 通过 ElevenLabs API 进行高质量语音合成
 *
 * 特性：
 * - 业界领先的 AI 语音质量
 * - 支持 29+ 语言
 * - 丰富的预制音色 + 音色克隆
 * - 多种模型可选（多语言 v2、Turbo v2.5 等）
 * - 支持稳定性和相似度调节
 */
package com.gameswu.nyadeskpet.agent.provider.tts

import com.gameswu.nyadeskpet.agent.provider.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ==================== ElevenLabs 类型 ====================

@Serializable
internal data class ElevenLabsTTSRequestBody(
    val text: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("voice_settings") val voiceSettings: ElevenLabsVoiceSettings? = null,
)

@Serializable
internal data class ElevenLabsVoiceSettings(
    val stability: Float? = null,
    @SerialName("similarity_boost") val similarityBoost: Float? = null,
    val style: Float? = null,
    @SerialName("use_speaker_boost") val useSpeakerBoost: Boolean? = null,
)

@Serializable
internal data class ElevenLabsVoiceResponse(
    val voices: List<ElevenLabsVoice> = emptyList(),
)

@Serializable
internal data class ElevenLabsVoice(
    @SerialName("voice_id") val voiceId: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val category: String? = null,
    val labels: Map<String, String>? = null,
)

// ==================== ElevenLabs Provider 实现 ====================

class ElevenLabsProvider(config: ProviderConfig) : TTSProvider(config) {

    companion object {
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
    }

    private val httpClient = buildHttpClient()
    private var cachedVoices: List<VoiceInfo> = emptyList()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    override fun getMetadata(): ProviderMetadata = ELEVENLABS_METADATA

    override suspend fun initialize() {
        initialized = true
    }

    override suspend fun terminate() {
        httpClient.close()
        cachedVoices = emptyList()
        initialized = false
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun synthesize(request: TTSRequest): TTSResponse {
        val apiKey = getConfigValue<String?>("apiKey", null)
        val voiceId = request.voiceId ?: getConfigValue("voiceId", "JBFqnCBsd6RMkjVDRZzb")
        val model = getConfigValue("model", "eleven_multilingual_v2")
        val format = request.format ?: getConfigValue("format", "mp3")
        val stability = getConfigValue("stability", 0.5f)
        val similarityBoost = getConfigValue("similarityBoost", 0.75f)

        val body = ElevenLabsTTSRequestBody(
            text = request.text,
            modelId = model,
            voiceSettings = ElevenLabsVoiceSettings(
                stability = stability,
                similarityBoost = similarityBoost,
            ),
        )

        val outputFormat = when (format) {
            "mp3" -> "mp3_44100_128"
            "pcm" -> "pcm_44100"
            "opus" -> "opus_48000"
            else -> "mp3_44100_128"
        }

        val response = httpClient.post("$BASE_URL/text-to-speech/$voiceId") {
            apiKey?.let { header("xi-api-key", it) }
            contentType(ContentType.Application.Json)
            parameter("output_format", outputFormat)
            setBody(json.encodeToString(ElevenLabsTTSRequestBody.serializer(), body))
        }

        if (!response.status.isSuccess()) {
            val errorText = response.bodyAsText()
            val errorMsg = when (response.status.value) {
                401 -> "API Key 无效"
                else -> "HTTP ${response.status.value}: $errorText"
            }
            throw Exception("ElevenLabs TTS 合成失败: $errorMsg")
        }

        val audioBytes = response.readRawBytes()
        val audioBase64 = Base64.encode(audioBytes)

        val mimeType = audioFormatToMimeType(format)

        return TTSResponse(audioBase64 = audioBase64, mimeType = mimeType)
    }

    override suspend fun getVoices(): List<VoiceInfo> {
        if (cachedVoices.isNotEmpty()) return cachedVoices

        val apiKey = getConfigValue<String?>("apiKey", null)

        val response = httpClient.get("$BASE_URL/voices") {
            apiKey?.let { header("xi-api-key", it) }
        }

        if (!response.status.isSuccess()) {
            throw Exception("获取音色列表失败: HTTP ${response.status.value}")
        }

        val data = json.decodeFromString(ElevenLabsVoiceResponse.serializer(), response.bodyAsText())
        cachedVoices = data.voices.map { v ->
            VoiceInfo(
                id = v.voiceId,
                name = v.name,
                description = v.description,
                previewUrl = v.previewUrl,
                language = v.labels?.get("language"),
            )
        }
        return cachedVoices
    }

    override suspend fun test(): TestResult {
        return try {
            if (!initialized) initialize()
            val apiKey = getConfigValue<String?>("apiKey", null)
            val resp = httpClient.get("$BASE_URL/voices") {
                apiKey?.let { header("xi-api-key", it) }
            }
            if (resp.status.value == 401) {
                TestResult(false, "API Key 无效")
            } else {
                TestResult(true)
            }
        } catch (e: Exception) {
            TestResult(false, e.message)
        }
    }
}

// ==================== Provider 元信息 ====================

val ELEVENLABS_METADATA = ProviderMetadata(
    id = "elevenlabs",
    name = "ElevenLabs",
    description = "业界领先的 AI 语音合成服务，支持 29+ 语言，音色克隆，多种模型，超高音质",
    configSchema = listOf(
        ProviderConfigField(
            key = "apiKey", label = "API Key", type = "password",
            required = true, placeholder = "your_api_key",
            description = "从 ElevenLabs 获取的 API 密钥（https://elevenlabs.io/app/settings/api-keys）",
        ),
        ProviderConfigField(
            key = "voiceId", label = "音色 ID", type = "string",
            default = "JBFqnCBsd6RMkjVDRZzb", placeholder = "JBFqnCBsd6RMkjVDRZzb",
            description = "音色 ID，可从 https://elevenlabs.io/voice-library 获取。默认为 George",
        ),
        ProviderConfigField(
            key = "model", label = "TTS 模型", type = "string",
            default = "eleven_multilingual_v2", placeholder = "eleven_multilingual_v2",
            description = "模型 ID，如 eleven_multilingual_v2、eleven_flash_v2_5、eleven_flash_v2",
        ),
        ProviderConfigField(
            key = "format", label = "音频格式", type = "string",
            default = "mp3", placeholder = "mp3",
            description = "输出格式，如 mp3、pcm、opus",
        ),
        ProviderConfigField(
            key = "stability", label = "稳定性", type = "number",
            default = "0.5",
            description = "语音稳定性，范围 0.0 - 1.0。越高越稳定，越低越有表现力",
        ),
        ProviderConfigField(
            key = "similarityBoost", label = "相似度增强", type = "number",
            default = "0.75",
            description = "音色相似度增强，范围 0.0 - 1.0。越高越接近原始音色",
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