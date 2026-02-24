/**
 * OpenRouter Provider
 * 对齐原 Electron 项目 src/agent/providers/openrouter.ts
 *
 * 通过 OpenRouter 统一网关访问 400+ AI 模型
 * 特殊行为：
 * - 强制 baseUrl = https://openrouter.ai/api/v1
 * - 注入 HTTP-Referer / X-Title 头
 * - 覆盖 getModels() 获取丰富模型信息
 * - 覆盖 test() 使用模型列表 API 验证
 */
package com.gameswu.nyadeskpet.agent.provider.providers

import com.gameswu.nyadeskpet.agent.provider.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== OpenRouter 模型信息 ====================

@Serializable
internal data class OpenRouterModel(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    val architecture: OpenRouterArchitecture? = null,
    val pricing: OpenRouterPricing? = null,
    @SerialName("supported_parameters") val supportedParameters: List<String>? = null,
)

@Serializable
internal data class OpenRouterArchitecture(
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
    @SerialName("output_modalities") val outputModalities: List<String>? = null,
    val tokenizer: String? = null,
    @SerialName("instruct_type") val instructType: String? = null,
)

@Serializable
internal data class OpenRouterPricing(
    val prompt: String? = null,
    val completion: String? = null,
)

@Serializable
internal data class OpenRouterModelsResponse(
    val data: List<OpenRouterModel> = emptyList(),
)

// ==================== OpenRouter Provider 实现 ====================

class OpenRouterProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = "https://openrouter.ai/api/v1", // 强制使用 OpenRouter URL
        model = config.model ?: "openai/gpt-4o-mini",
    )
) {
    /** 缓存的模型详细信息 */
    private var openRouterModels: List<OpenRouterModel> = emptyList()

    override fun getMetadata(): ProviderMetadata = OPENROUTER_METADATA

    /**
     * 获取可用模型列表（覆盖父类，使用 OpenRouter /models API）
     */
    override suspend fun getModels(): List<String> {
        if (openRouterModels.isNotEmpty()) return openRouterModels.map { it.id }

        val baseUrl = "https://openrouter.ai/api/v1"
        val apiKey = getConfigValue<String?>("apiKey", null)

        val response = httpClient.get("$baseUrl/models") {
            apiKey?.let { header("Authorization", "Bearer $it") }
            header("HTTP-Referer", "https://github.com/NyaDeskPet")
            header("X-Title", "NyaDeskPet")
        }

        val bodyText = response.bodyAsText()
        val modelsResp = json.decodeFromString(OpenRouterModelsResponse.serializer(), bodyText)
        openRouterModels = modelsResp.data.sortedBy { it.id }
        cachedModels = openRouterModels.map { it.id }
        return cachedModels
    }

    /** 查询模型详细信息 */
    internal fun getModelInfo(modelId: String): OpenRouterModel? =
        openRouterModels.find { it.id == modelId }

    /** 查询模型是否支持特定输入模态 */
    fun modelSupportsModality(modelId: String, modality: String): Boolean =
        getModelInfo(modelId)?.architecture?.inputModalities?.contains(modality) ?: false

    /** 查询模型是否支持 Function Calling */
    fun modelSupportsTools(modelId: String): Boolean =
        getModelInfo(modelId)?.supportedParameters?.contains("tools") ?: false

    /**
     * 测试连接（覆盖父类，使用模型列表 API 测试）
     */
    override suspend fun test(): TestResult {
        return try {
            if (!initialized) initialize()
            val models = getModels()
            if (models.isEmpty()) {
                TestResult(false, "OpenRouter 返回的模型列表为空")
            } else {
                val currentModel = getModel()
                val modelExists = models.contains(currentModel)
                TestResult(
                    success = true,
                    model = if (modelExists) currentModel else "$currentModel (${models.size} 个模型可用)",
                )
            }
        } catch (e: Exception) {
            TestResult(false, e.message)
        }
    }
}

// ==================== Provider 元信息 ====================

val OPENROUTER_METADATA = ProviderMetadata(
    id = "openrouter",
    name = "OpenRouter",
    description = "通过 OpenRouter 统一网关访问 400+ AI 模型（OpenAI、Claude、Gemini、Llama 等），自动故障转移，支持多模态和 Function Calling",
    configSchema = listOf(
        ProviderConfigField(
            key = "apiKey", label = "API Key", type = "password",
            required = true, placeholder = "sk-or-v1-...",
            description = "从 OpenRouter 获取的 API 密钥（https://openrouter.ai/keys）",
        ),
        ProviderConfigField(
            key = "model", label = "模型", type = "string",
            default = "openai/gpt-4o-mini", placeholder = "openai/gpt-4o-mini",
            description = "模型 ID，格式为 provider/model（如 anthropic/claude-sonnet-4、google/gemini-2.5-flash）。完整列表见 https://openrouter.ai/models",
        ),
        ProviderConfigField(
            key = "timeout", label = "超时时间（秒）", type = "number",
            default = "120", description = "请求超时时间，推理模型建议适当增大",
        ),
        ProviderConfigField(
            key = "proxy", label = "代理地址", type = "string",
            placeholder = "http://127.0.0.1:7890",
            description = "HTTP/HTTPS 代理（如需使用）",
        ),
        ProviderConfigField(
            key = "stream", label = "流式输出", type = "boolean",
            default = "false",
            description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验",
        ),
    ) + PROVIDER_CAPABILITY_FIELDS,
)