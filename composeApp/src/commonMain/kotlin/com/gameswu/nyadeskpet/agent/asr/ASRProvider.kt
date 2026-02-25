package com.gameswu.nyadeskpet.agent.asr

/**
 * ASR（自动语音识别）提供者接口 — 对齐原项目 asr-service.ts 的设计
 *
 * 支持两种模式：
 * - system: 使用平台原生语音识别（Android SpeechRecognizer / iOS SFSpeechRecognizer）
 * - whisper: 使用 OpenAI Whisper API（或兼容的 API，如 Groq）
 *
 * 原项目使用 Sherpa-ONNX 本地识别（需要 native 模块 + ffmpeg），
 * KMP 版本改为 API-based 方案，兼容性更好。
 */

/** ASR 识别结果 */
data class ASRResult(
    val text: String,
    val confidence: Float? = null,
    /** 识别耗时 (ms) */
    val durationMs: Long? = null,
)

/** ASR 提供者元数据 */
data class ASRProviderMetadata(
    val id: String,
    val name: String,
    val description: String,
    /** 是否需要 API Key */
    val requiresApiKey: Boolean,
    /** 是否需要录音（false = 使用系统识别器） */
    val requiresRecording: Boolean,
)

/** ASR 提供者配置 */
data class ASRProviderConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val language: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

/** ASR 提供者接口 */
interface ASRProvider {
    val metadata: ASRProviderMetadata

    /** 初始化 */
    suspend fun initialize()

    /** 销毁资源 */
    suspend fun terminate()

    /**
     * 识别音频数据
     * @param audioData WAV 格式的音频字节（16kHz 16-bit mono PCM with WAV header）
     * @param language 语言代码（如 "zh", "en", "ja"），null 表示自动检测
     * @return 识别结果，null 表示识别失败
     */
    suspend fun recognize(audioData: ByteArray, language: String? = null): ASRResult?
}

// ==================== ASR Provider Registry ====================

/** 系统默认的 ASR 模式标识 */
const val ASR_MODE_SYSTEM = "system"

object ASRProviderRegistry {
    private val providers = mutableMapOf<String, Pair<ASRProviderMetadata, (ASRProviderConfig) -> ASRProvider>>()

    fun register(metadata: ASRProviderMetadata, factory: (ASRProviderConfig) -> ASRProvider) {
        providers[metadata.id] = metadata to factory
    }

    fun create(id: String, config: ASRProviderConfig): ASRProvider? {
        return providers[id]?.second?.invoke(config)
    }

    fun get(id: String): ASRProviderMetadata? = providers[id]?.first

    fun has(id: String): Boolean = providers.containsKey(id)

    fun getAll(): List<ASRProviderMetadata> = providers.values.map { it.first }
}
