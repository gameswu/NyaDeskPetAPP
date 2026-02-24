/**
 * Fish Audio TTS Provider
 * 对齐原 Electron 项目 src/agent/tts-providers/fish-audio.ts
 *
 * 通过 Fish Audio API 将文本转换为自然语音
 *
 * 特性：
 * - 支持 400+ 预制音色和自定义克隆音色
 * - 多种音频格式（MP3、WAV、PCM、Opus）
 * - 情感标记支持（(happy)、(sad)、(whispering) 等）
 * - 语速和音量调节
 * - 多种 TTS 模型可选（s1、speech-1.6、speech-1.5）
 *
 * API 文档：https://docs.fish.audio/developer-guide/core-features/text-to-speech
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

// ==================== Fish Audio 类型 ====================

@Serializable
internal data class FishAudioTTSRequestBody(
    val text: String,
    @SerialName("reference_id") val referenceId: String? = null,
    val format: String? = "mp3",
    @SerialName("chunk_length") val chunkLength: Int? = 200,
    val normalize: Boolean? = true,
    val latency: String? = "normal",
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    val prosody: FishAudioProsody? = null,
)

@Serializable
internal data class FishAudioProsody(
    val speed: Float? = null,
    val volume: Float? = null,
)

@Serializable
internal data class FishAudioModelResponse(
    val total: Int = 0,
    val items: List<FishAudioModel> = emptyList(),
)

@Serializable
internal data class FishAudioModel(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val description: String? = null,
    val languages: List<String>? = null,
)

// ==================== Fish Audio Provider 实现 ====================

class FishAudioProvider(config: ProviderConfig) : TTSProvider(config) {

    private val httpClient = HttpClient { expectSuccess = false }
    private var cachedVoices: List<VoiceInfo> = emptyList()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    override fun getMetadata(): ProviderMetadata = FISH_AUDIO_METADATA

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
        val voiceId = request.voiceId ?: getConfigValue("voiceId", "")
        val format = request.format ?: getConfigValue("format", "mp3")
        val model = getConfigValue("model", "s1")
        val latency = getConfigValue("latency", "normal")

        val speed = request.speed ?: getConfigValue<Float?>("speed", null)
        val volume = request.volume ?: getConfigValue<Float?>("volume", null)

        val body = FishAudioTTSRequestBody(
            text = request.text,
            referenceId = voiceId.takeIf { it.isNotEmpty() },
            format = format,
            chunkLength = getConfigValue("chunkLength", 200),
            normalize = true,
            latency = latency,
            temperature = getConfigValue("temperature", 0.7f),
            topP = getConfigValue("topP", 0.7f),
            prosody = if (speed != null || volume != null) {
                FishAudioProsody(speed = speed, volume = volume)
            } else null,
        )

        val response = httpClient.post("https://api.fish.audio/v1/tts") {
            apiKey?.let { header("Authorization", "Bearer $it") }
            header("model", model)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(FishAudioTTSRequestBody.serializer(), body))
        }

        if (!response.status.isSuccess()) {
            val errorText = response.bodyAsText()
            val errorMsg = when (response.status.value) {
                401 -> "API Key 无效"
                else -> "HTTP ${response.status.value}: $errorText"
            }
            throw Exception("Fish Audio TTS 合成失败: $errorMsg")
        }

        val audioBytes = response.readRawBytes()
        val audioBase64 = Base64.encode(audioBytes)

        val mimeType = when (format) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "opus" -> "audio/opus"
            "pcm" -> "audio/pcm"
            else -> "audio/mpeg"
        }

        return TTSResponse(audioBase64 = audioBase64, mimeType = mimeType)
    }

    override suspend fun getVoices(): List<VoiceInfo> {
        if (cachedVoices.isNotEmpty()) return cachedVoices

        val apiKey = getConfigValue<String?>("apiKey", null)
        val voices = mutableListOf<VoiceInfo>()

        try {
            // 获取用户自己的音色
            val selfResp = httpClient.get("https://api.fish.audio/model") {
                apiKey?.let { header("Authorization", "Bearer $it") }
                parameter("page_size", 100)
                parameter("page_number", 1)
                parameter("self", true)
                parameter("sort_by", "created_at")
            }
            if (selfResp.status.isSuccess()) {
                val selfData = json.decodeFromString(FishAudioModelResponse.serializer(), selfResp.bodyAsText())
                for (m in selfData.items) {
                    voices.add(VoiceInfo(
                        id = m.id,
                        name = "${m.title} ⭐",
                        description = m.description,
                        language = m.languages?.joinToString(", "),
                    ))
                }
            }

            // 获取平台热门音色
            val publicResp = httpClient.get("https://api.fish.audio/model") {
                apiKey?.let { header("Authorization", "Bearer $it") }
                parameter("page_size", 50)
                parameter("page_number", 1)
                parameter("sort_by", "task_count")
            }
            if (publicResp.status.isSuccess()) {
                val publicData = json.decodeFromString(FishAudioModelResponse.serializer(), publicResp.bodyAsText())
                for (m in publicData.items) {
                    if (voices.none { it.id == m.id }) {
                        voices.add(VoiceInfo(
                            id = m.id,
                            name = m.title,
                            description = m.description,
                            language = m.languages?.joinToString(", "),
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            throw Exception("获取音色列表失败: ${e.message}")
        }

        cachedVoices = voices
        return voices
    }

    override suspend fun test(): TestResult {
        return try {
            if (!initialized) initialize()
            val apiKey = getConfigValue<String?>("apiKey", null)
            val resp = httpClient.get("https://api.fish.audio/model") {
                apiKey?.let { header("Authorization", "Bearer $it") }
                parameter("page_size", 1)
                parameter("page_number", 1)
                parameter("self", true)
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

val FISH_AUDIO_METADATA = ProviderMetadata(
    id = "fish-audio",
    name = "Fish Audio",
    description = "高质量 AI 语音合成服务，支持 400+ 预制音色和自定义音色克隆，流式传输，情感标记，多语言",
    configSchema = listOf(
        ProviderConfigField(
            key = "apiKey", label = "API Key", type = "password",
            required = true, placeholder = "your_api_key",
            description = "从 Fish Audio 获取的 API 密钥（https://fish.audio/account/api-keys）",
        ),
        ProviderConfigField(
            key = "voiceId", label = "音色 ID", type = "string",
            placeholder = "802e3bc2b27e49c2995d23ef70e6ac89",
            description = "音色模型 ID，可从 https://fish.audio/discovery 获取。留空使用默认音色",
        ),
        ProviderConfigField(
            key = "model", label = "TTS 模型", type = "string",
            default = "s1", placeholder = "s1",
            description = "模型 ID，如 s1、speech-1.6、speech-1.5",
        ),
        ProviderConfigField(
            key = "format", label = "音频格式", type = "string",
            default = "mp3", placeholder = "mp3",
            description = "输出格式，如 mp3、wav、opus、pcm",
        ),
        ProviderConfigField(
            key = "latency", label = "延迟模式", type = "string",
            default = "normal", placeholder = "normal",
            description = "延迟与质量的权衡，如 normal、balanced",
        ),
        ProviderConfigField(
            key = "speed", label = "语速", type = "number",
            default = "1.0",
            description = "语速调节，范围 0.5 - 2.0",
        ),
        ProviderConfigField(
            key = "timeout", label = "超时时间（秒）", type = "number",
            default = "60", description = "请求超时时间",
        ),
        ProviderConfigField(
            key = "proxy", label = "代理地址", type = "string",
            placeholder = "http://127.0.0.1:7890",
            description = "HTTP/HTTPS 代理（如需使用）",
        ),
    ),
)