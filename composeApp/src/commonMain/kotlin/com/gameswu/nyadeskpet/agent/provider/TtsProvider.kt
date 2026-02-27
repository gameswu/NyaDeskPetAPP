/**
 * TTS Provider 抽象层
 * 对齐原 Electron 项目 src/agent/tts-provider.ts 的设计
 *
 * 设计原则：
 * - 策略模式：统一接口，多种实现（Fish Audio / Edge TTS / OpenAI TTS 等）
 * - 注册表模式：通过 registerTTSProvider() 声明式注册
 * - 生命周期：initialize() → synthesize → terminate()
 * - 配置分离：ProviderConfig（实例配置）与 ProviderMetadata（类型元信息）复用 LLM 侧定义
 */
package com.gameswu.nyadeskpet.agent.provider

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import kotlinx.serialization.Serializable

// ==================== TTS 核心类型 ====================

/** TTS 合成请求 */
data class TTSRequest(
    /** 要合成的文本 */
    val text: String,
    /** 音色 / 声音模型 ID */
    val voiceId: String? = null,
    /** 输出格式 */
    val format: String? = "mp3",
    /** 语速（0.5 - 2.0） */
    val speed: Float? = null,
    /** 音量调节 */
    val volume: Float? = null,
)

/** TTS 合成结果 */
data class TTSResponse(
    /** 音频数据（Base64 编码） */
    val audioBase64: String,
    /** MIME 类型（如 "audio/mpeg"） */
    val mimeType: String = "audio/mpeg",
    /** 音频时长（毫秒） */
    val duration: Long? = null,
)

/** 音色信息 */
@Serializable
data class VoiceInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val previewUrl: String? = null,
    val language: String? = null,
)

// ==================== TTS Provider 基类 ====================

/**
 * TTS Provider 抽象基类
 * 对应原项目 TTSProvider class
 *
 * 生命周期: constructor → initialize → synthesize → terminate
 */
abstract class TTSProvider(protected val config: ProviderConfig) {
    protected var initialized: Boolean = false

    /** 获取 Provider 元信息（复用 ProviderMetadata） */
    abstract fun getMetadata(): ProviderMetadata

    /** 完整合成 — 返回音频数据 */
    abstract suspend fun synthesize(request: TTSRequest): TTSResponse

    /** 初始化 */
    open suspend fun initialize() {
        initialized = true
    }

    /** 销毁 */
    open suspend fun terminate() {
        initialized = false
    }

    /** 连接测试 */
    open suspend fun test(): TestResult {
        return try {
            val voices = getVoices()
            TestResult(true)
        } catch (e: Exception) {
            TestResult(false, e.message)
        }
    }

    /** 获取可用音色列表 */
    open suspend fun getVoices(): List<VoiceInfo> = emptyList()

    /**
     * 创建已配置超时和代理的 HttpClient
     */
    protected fun buildHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient =
        buildProviderHttpClient(config, block)

    /** 获取配置值 */
    protected fun <T> getConfigValue(key: String, defaultValue: T): T =
        getProviderConfigValue(config, key, defaultValue)
}

// ==================== TTS Provider 注册表 ====================

typealias TTSProviderFactory = (ProviderConfig) -> TTSProvider

/**
 * TTS Provider 注册表（全局单例）
 * 对应原项目 TTSProviderRegistry / ttsProviderRegistry
 */
object TTSProviderRegistry {
    private data class RegistryEntry(
        val metadata: ProviderMetadata,
        val factory: TTSProviderFactory,
    )

    private val entries = mutableMapOf<String, RegistryEntry>()

    fun register(metadata: ProviderMetadata, factory: TTSProviderFactory) {
        entries[metadata.id] = RegistryEntry(metadata, factory)
    }

    /** 创建 TTS Provider 实例 */
    fun create(id: String, config: ProviderConfig): TTSProvider? {
        val entry = entries[id] ?: return null
        return entry.factory(config)
    }

    /** 获取所有已注册的 TTS Provider 类型元信息 */
    fun getAll(): List<ProviderMetadata> = entries.values.map { it.metadata }

    fun get(id: String): ProviderMetadata? = entries[id]?.metadata

    fun has(id: String): Boolean = entries.containsKey(id)
}

/** 注册 TTS Provider 的便捷函数 */
fun registerTTSProvider(metadata: ProviderMetadata, factory: TTSProviderFactory) {
    TTSProviderRegistry.register(metadata, factory)
}

// ==================== TTS 实例配置（持久化） ====================

/**
 * TTS Provider 实例配置
 * 对应原项目 TTSProviderInstanceConfig
 */
@Serializable
data class TTSProviderInstanceConfig(
    val instanceId: String,
    val providerId: String,
    val displayName: String,
    val config: ProviderConfig,
    val enabled: Boolean = false,
)

/**
 * TTS Provider 实例运行时信息（供 UI 展示）
 * 与 LLM 侧的 ProviderInstanceInfo 对称
 */
data class TTSProviderInstanceInfo(
    val instanceId: String,
    val providerId: String,
    val displayName: String,
    val config: ProviderConfig,
    val metadata: ProviderMetadata?,
    val enabled: Boolean,
    val status: ProviderStatus,
    val error: String? = null,
    val isPrimary: Boolean = false,
)
