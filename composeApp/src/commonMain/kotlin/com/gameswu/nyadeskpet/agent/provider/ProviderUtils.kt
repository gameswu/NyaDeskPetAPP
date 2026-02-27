/**
 * Provider 共享工具方法
 *
 * LLMProvider 和 TTSProvider 的公共基础设施：
 * - buildProviderHttpClient: 创建带超时和代理配置的 HttpClient
 * - getProviderConfigValue: 类型安全的配置值读取
 *
 * 避免两个基类中重复实现相同逻辑。
 */
package com.gameswu.nyadeskpet.agent.provider

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*

/**
 * 创建已配置超时和代理的 HttpClient
 * 对齐原项目中 axios.create({ timeout, httpsAgent }) 的行为
 */
fun buildProviderHttpClient(
    config: ProviderConfig,
    block: HttpClientConfig<*>.() -> Unit = {},
): HttpClient {
    val timeoutSec = getProviderConfigValue(config, "timeout", 60)
    val timeoutMs = timeoutSec.toLong() * 1000L
    val proxyUrl = getProviderConfigValue<String?>(config, "proxy", null)

    return HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
        }
        if (!proxyUrl.isNullOrBlank()) {
            engine {
                proxy = ProxyBuilder.http(proxyUrl)
            }
        }
        block()
    }
}

/**
 * 类型安全的配置值读取
 */
@Suppress("UNCHECKED_CAST")
fun <T> getProviderConfigValue(config: ProviderConfig, key: String, defaultValue: T): T {
    val value: Any? = when (key) {
        "apiKey" -> config.apiKey
        "baseUrl" -> config.baseUrl
        "model" -> config.model
        "timeout" -> config.timeout
        "proxy" -> config.proxy
        else -> config.extra[key]
    }
    if (value == null || value == "") return defaultValue
    // config.extra 的值总是 String，需要根据目标类型做转换
    if (value is String) {
        val converted: Any? = when (defaultValue) {
            is Float -> value.toFloatOrNull()
            is Double -> value.toDoubleOrNull()
            is Int -> value.toIntOrNull()
            is Long -> value.toLongOrNull()
            is Boolean -> value.toBooleanStrictOrNull()
            is String -> value
            null -> value.toFloatOrNull() ?: value.toDoubleOrNull()
                ?: value.toIntOrNull() ?: value.toLongOrNull()
                ?: value.toBooleanStrictOrNull() ?: value
            else -> value
        }
        return (converted ?: defaultValue) as T
    }
    return value as T
}

/**
 * 将音频格式名称转换为对应的 MIME 类型
 * 统一所有 TTS Provider 和 AgentService 使用的映射逻辑
 */
fun audioFormatToMimeType(format: String?): String = when (format) {
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "opus" -> "audio/opus"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    "pcm" -> "audio/pcm"
    "ogg" -> "audio/ogg"
    else -> "audio/mpeg"
}
