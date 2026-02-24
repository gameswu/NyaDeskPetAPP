/**
 * Google Gemini Provider
 * 对齐原 Electron 项目 src/agent/providers/gemini.ts
 *
 * 通过 Gemini API 的 OpenAI 兼容模式访问 Gemini 系列模型
 * 支持: gemini-2.5-flash、gemini-2.5-pro、gemini-2.0-flash
 */
package com.gameswu.nyadeskpet.agent.provider.providers

import com.gameswu.nyadeskpet.agent.provider.*

class GeminiProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = config.baseUrl ?: "https://generativelanguage.googleapis.com/v1beta/openai/",
        model = config.model ?: "gemini-2.5-flash",
    )
) {
    override fun getMetadata(): ProviderMetadata = GEMINI_METADATA
}

val GEMINI_METADATA = ProviderMetadata(
    id = "gemini",
    name = "Google Gemini",
    description = "Google Gemini 系列模型，支持多模态理解与生成、Function Calling、思考推理，免费额度慷慨",
    configSchema = listOf(
        ProviderConfigField(
            key = "apiKey", label = "API Key", type = "password",
            required = true, placeholder = "AIza...",
            description = "从 Google AI Studio 获取的 API 密钥（https://aistudio.google.com/apikey）",
        ),
        ProviderConfigField(
            key = "baseUrl", label = "API Base URL", type = "string",
            default = "https://generativelanguage.googleapis.com/v1beta/openai/",
            description = "Gemini OpenAI 兼容端点，通常无需修改。如需代理可修改",
        ),
        ProviderConfigField(
            key = "model", label = "模型", type = "string",
            default = "gemini-2.5-flash", placeholder = "gemini-2.5-flash",
            description = "模型 ID，如 gemini-2.5-flash、gemini-2.5-pro、gemini-2.0-flash",
        ),
        ProviderConfigField(
            key = "timeout", label = "超时时间（秒）", type = "number",
            default = "60", description = "请求超时时间，推理模型建议适当增大",
        ),
        ProviderConfigField(
            key = "proxy", label = "代理地址", type = "string",
            placeholder = "http://127.0.0.1:7890",
            description = "HTTP/HTTPS 代理（如需翻墙访问）",
        ),
        ProviderConfigField(
            key = "stream", label = "流式输出", type = "boolean",
            default = "false",
            description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验",
        ),
    ) + PROVIDER_CAPABILITY_FIELDS,
)