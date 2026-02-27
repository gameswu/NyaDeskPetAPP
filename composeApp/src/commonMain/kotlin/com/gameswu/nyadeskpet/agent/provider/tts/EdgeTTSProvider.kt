/**
 * Edge TTS Provider
 * 对齐原 Electron 项目 src/agent/tts-providers/edge-tts.ts
 *
 * 基于 Microsoft Edge Read Aloud 的免费 TTS 服务
 * 原项目使用 node-edge-tts（Node.js WebSocket），KMP 版通过 Ktor WebSocket 直接实现
 *
 * 特性：
 * - 完全免费，无需 API Key
 * - 高质量 Neural 语音
 * - 支持多语言（中文、英文、日语等 400+ 音色）
 * - 支持语速、音调、音量调节
 */
package com.gameswu.nyadeskpet.agent.provider.tts

import com.gameswu.nyadeskpet.agent.provider.*
import com.gameswu.nyadeskpet.formatUtcDate
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

// ==================== 常用 Edge TTS 音色列表 ====================

private val EDGE_TTS_VOICES = listOf(
    // 中文（普通话）
    VoiceInfo(id = "zh-CN-XiaoxiaoNeural", name = "晓晓（女）", language = "zh-CN", description = "温暖、活泼的女声"),
    VoiceInfo(id = "zh-CN-XiaoyiNeural", name = "晓伊（女）", language = "zh-CN", description = "亲切的女声"),
    VoiceInfo(id = "zh-CN-YunjianNeural", name = "云健（男）", language = "zh-CN", description = "阳光的男声"),
    VoiceInfo(id = "zh-CN-YunxiNeural", name = "云希（男）", language = "zh-CN", description = "沉稳的男声"),
    VoiceInfo(id = "zh-CN-YunxiaNeural", name = "云夏（男）", language = "zh-CN", description = "少年感的男声"),
    VoiceInfo(id = "zh-CN-YunyangNeural", name = "云扬（男）", language = "zh-CN", description = "专业播报男声"),
    VoiceInfo(id = "zh-CN-liaoning-XiaobeiNeural", name = "晓北（女·东北话）", language = "zh-CN", description = "东北方言女声"),
    VoiceInfo(id = "zh-CN-shaanxi-XiaoniNeural", name = "晓妮（女·陕西话）", language = "zh-CN", description = "陕西方言女声"),
    // 中文（台湾）
    VoiceInfo(id = "zh-TW-HsiaoChenNeural", name = "曉臻（女）", language = "zh-TW", description = "台湾女声"),
    VoiceInfo(id = "zh-TW-YunJheNeural", name = "雲哲（男）", language = "zh-TW", description = "台湾男声"),
    // 中文（粤语）
    VoiceInfo(id = "zh-HK-HiuGaaiNeural", name = "曉佳（女）", language = "zh-HK", description = "粤语女声"),
    VoiceInfo(id = "zh-HK-WanLungNeural", name = "雲龍（男）", language = "zh-HK", description = "粤语男声"),
    // 英文（美国）
    VoiceInfo(id = "en-US-AriaNeural", name = "Aria (Female)", language = "en-US", description = "Friendly female voice"),
    VoiceInfo(id = "en-US-JennyNeural", name = "Jenny (Female)", language = "en-US", description = "Warm female voice"),
    VoiceInfo(id = "en-US-GuyNeural", name = "Guy (Male)", language = "en-US", description = "Casual male voice"),
    VoiceInfo(id = "en-US-DavisNeural", name = "Davis (Male)", language = "en-US", description = "Confident male voice"),
    // 英文（英国）
    VoiceInfo(id = "en-GB-SoniaNeural", name = "Sonia (Female)", language = "en-GB", description = "British female voice"),
    VoiceInfo(id = "en-GB-RyanNeural", name = "Ryan (Male)", language = "en-GB", description = "British male voice"),
    // 日语
    VoiceInfo(id = "ja-JP-NanamiNeural", name = "Nanami（女）", language = "ja-JP", description = "日语女声"),
    VoiceInfo(id = "ja-JP-KeitaNeural", name = "Keita（男）", language = "ja-JP", description = "日语男声"),
    // 韩语
    VoiceInfo(id = "ko-KR-SunHiNeural", name = "SunHi（女）", language = "ko-KR", description = "韩语女声"),
    VoiceInfo(id = "ko-KR-InJoonNeural", name = "InJoon（男）", language = "ko-KR", description = "韩语男声"),
    // 法语
    VoiceInfo(id = "fr-FR-DeniseNeural", name = "Denise (Female)", language = "fr-FR", description = "French female voice"),
    VoiceInfo(id = "fr-FR-HenriNeural", name = "Henri (Male)", language = "fr-FR", description = "French male voice"),
    // 德语
    VoiceInfo(id = "de-DE-KatjaNeural", name = "Katja (Female)", language = "de-DE", description = "German female voice"),
    VoiceInfo(id = "de-DE-ConradNeural", name = "Conrad (Male)", language = "de-DE", description = "German male voice"),
    // 西班牙语
    VoiceInfo(id = "es-ES-ElviraNeural", name = "Elvira (Female)", language = "es-ES", description = "Spanish female voice"),
    VoiceInfo(id = "es-ES-AlvaroNeural", name = "Alvaro (Male)", language = "es-ES", description = "Spanish male voice"),
    // 俄语
    VoiceInfo(id = "ru-RU-SvetlanaNeural", name = "Svetlana (Female)", language = "ru-RU", description = "Russian female voice"),
    VoiceInfo(id = "ru-RU-DmitryNeural", name = "Dmitry (Male)", language = "ru-RU", description = "Russian male voice"),
)

// ==================== Edge TTS Provider 实现 ====================

/**
 * Edge TTS Provider
 * 通过 WebSocket 直接连接 Microsoft Edge TTS 服务
 *
 * 协议参考：
 * - WebSocket 地址: wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1
 * - 使用 SSML 格式发送文本
 * - 音频数据通过 Binary Frame 返回，带有 "Path:audio" 头标记
 */
class EdgeTTSProvider(config: ProviderConfig) : TTSProvider(config) {

    companion object {
        private const val WSS_BASE_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROME_EXTENSION_ORIGIN =
            "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
        private const val AUDIO_OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    }

    private val httpClient = buildHttpClient {
        install(WebSockets)
    }

    override fun getMetadata(): ProviderMetadata = EDGE_TTS_METADATA

    override suspend fun initialize() {
        initialized = true
    }

    override suspend fun terminate() {
        httpClient.close()
        initialized = false
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun synthesize(request: TTSRequest): TTSResponse {
        val voice = request.voiceId ?: getConfigValue("voice", "zh-CN-XiaoyiNeural")
        val rate = getConfigValue("rate", "default")
        val pitch = getConfigValue("pitch", "default")
        val volume = getConfigValue("volume", "default")
        val timeoutSec = getConfigValue("timeout", 30)

        val requestId = generateRequestId()
        val ssml = buildSSML(request.text, voice, rate, pitch, volume)

        val audioChunks = mutableListOf<ByteArray>()

        withTimeout(timeoutSec * 1000L) {
            httpClient.webSocket(
                urlString = "$WSS_BASE_URL" +
                    "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                    "&ConnectionId=$requestId",
                request = {
                    headers {
                        append(HttpHeaders.Origin, CHROME_EXTENSION_ORIGIN)
                        append(HttpHeaders.UserAgent, EDGE_USER_AGENT)
                    }
                },
            ) {
                // 1. 发送配置消息
                val configMessage = "X-Timestamp:${getTimestamp()}\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"$AUDIO_OUTPUT_FORMAT"}}}}"""
                send(Frame.Text(configMessage))

                // 2. 发送 SSML 消息
                val ssmlMessage = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${getTimestamp()}\r\n" +
                    "Path:ssml\r\n\r\n" +
                    ssml
                send(Frame.Text(ssmlMessage))

                // 3. 接收音频数据
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            val data = frame.readBytes()
                            // Binary frame 格式: 2字节头长度(big-endian) + 头内容 + 音频数据
                            if (data.size > 2) {
                                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                                if (data.size > 2 + headerLen) {
                                    val headerStr = data.decodeToString(2, 2 + headerLen)
                                    if (headerStr.contains("Path:audio")) {
                                        val audioData = data.copyOfRange(2 + headerLen, data.size)
                                        audioChunks.add(audioData)
                                    }
                                }
                            }
                        }
                        is Frame.Text -> {
                            val text = frame.readText()
                            if (text.contains("Path:turn.end")) {
                                // 合成完成
                                break
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        // 合并所有音频块
        val totalSize = audioChunks.sumOf { it.size }
        val audioBytes = ByteArray(totalSize)
        var offset = 0
        for (chunk in audioChunks) {
            chunk.copyInto(audioBytes, offset)
            offset += chunk.size
        }

        val audioBase64 = Base64.encode(audioBytes)
        return TTSResponse(
            audioBase64 = audioBase64,
            mimeType = "audio/mpeg",
        )
    }

    override suspend fun getVoices(): List<VoiceInfo> = EDGE_TTS_VOICES

    override suspend fun test(): TestResult {
        return try {
            if (!initialized) initialize()
            synthesize(TTSRequest(text = "test"))
            TestResult(true)
        } catch (e: Exception) {
            TestResult(false, e.message)
        }
    }

    // ==================== 辅助方法 ====================

    private fun buildSSML(text: String, voice: String, rate: String, pitch: String, volume: String): String {
        val rateAttr = if (rate != "default") " rate='$rate'" else ""
        val pitchAttr = if (pitch != "default") " pitch='$pitch'" else ""
        val volumeAttr = if (volume != "default") " volume='$volume'" else ""
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        return """<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>""" +
            """<voice name='$voice'>""" +
            """<prosody$rateAttr$pitchAttr$volumeAttr>""" +
            escapedText +
            """</prosody></voice></speak>"""
    }

    private fun generateRequestId(): String = buildString {
        repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    private fun getTimestamp(): String {
        return formatUtcDate()
    }
}

// ==================== Provider 元信息 ====================

val EDGE_TTS_METADATA = ProviderMetadata(
    id = "edge-tts",
    name = "Edge TTS",
    description = "基于 Microsoft Edge Read Aloud 的免费 TTS 服务，无需 API Key，支持 400+ 高质量 Neural 音色，多语言",
    configSchema = listOf(
        ProviderConfigField(
            key = "voice", label = "音色", type = "string",
            default = "zh-CN-XiaoyiNeural", placeholder = "zh-CN-XiaoyiNeural",
            description = "音色 ID，如 zh-CN-XiaoyiNeural、en-US-AriaNeural。完整列表见 https://learn.microsoft.com/azure/ai-services/speech-service/language-support",
        ),
        ProviderConfigField(
            key = "lang", label = "语言", type = "string",
            default = "zh-CN", placeholder = "zh-CN",
            description = "语言代码，如 zh-CN、en-US、ja-JP",
        ),
        ProviderConfigField(
            key = "rate", label = "语速", type = "string",
            default = "default", placeholder = "default 或 +20% 或 -10%",
            description = "语速调节。使用 default 表示默认，或 +/-百分比（如 +20%、-10%）",
        ),
        ProviderConfigField(
            key = "pitch", label = "音调", type = "string",
            default = "default", placeholder = "default 或 +5% 或 -10%",
            description = "音调调节。使用 default 表示默认，或 +/-百分比",
        ),
        ProviderConfigField(
            key = "volume", label = "音量", type = "string",
            default = "default", placeholder = "default 或 -50%",
            description = "音量调节。使用 default 表示默认，或 +/-百分比",
        ),
        ProviderConfigField(
            key = "timeout", label = "超时时间（秒）", type = "number",
            default = "30", description = "请求超时时间",
        ),
        ProviderConfigField(
            key = "proxy", label = "代理地址", type = "string",
            placeholder = "http://127.0.0.1:7890",
            description = "HTTP/HTTPS 代理（如需使用）",
        ),
    ),
)