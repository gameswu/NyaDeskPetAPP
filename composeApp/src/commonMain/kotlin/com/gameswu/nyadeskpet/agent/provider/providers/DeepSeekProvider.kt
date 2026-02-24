/**
 * DeepSeek Provider
 * 对齐原 Electron 项目 src/agent/providers/deepseek.ts
 *
 * 继承 OpenAI Provider，预设 DeepSeek 平台参数
 * 支持: deepseek-chat（通用对话）、deepseek-reasoner（深度思考）
 */
package com.gameswu.nyadeskpet.agent.provider.providers

import com.gameswu.nyadeskpet.agent.provider.*

class DeepSeekProvider(config: ProviderConfig) : OpenAIProvider(
    config.let {
        it.copy(
            baseUrl = it.baseUrl ?: "https://api.deepseek.com",
            model = it.model ?: "deepseek-chat",
        )
    }
) {
    override fun getMetadata(): ProviderMetadata = DEEPSEEK_METADATA
}

val DEEPSEEK_METADATA = ProviderMetadata(
    id = "deepseek",
    name = "DeepSeek",
    description = "DeepSeek 官方 API，支持 deepseek-chat 和 deepseek-reasoner，128K 上下文，支持 Function Calling",
    configSchema = listOf(
        ProviderConfigField(key = "apiKey", label = "API Key", type = "password", required = true, placeholder = "sk-...", description = "从 DeepSeek 平台获取的 API 密钥"),
        ProviderConfigField(key = "baseUrl", label = "API Base URL", type = "string", default = "https://api.deepseek.com", placeholder = "https://api.deepseek.com", description = "DeepSeek API 地址，通常无需修改"),
        ProviderConfigField(key = "model", label = "模型", type = "string", default = "deepseek-chat", placeholder = "deepseek-chat", description = "模型 ID，如 deepseek-chat 或 deepseek-reasoner"),
        ProviderConfigField(key = "timeout", label = "超时时间（秒）", type = "number", default = "60", description = "请求超时时间"),
        ProviderConfigField(key = "stream", label = "流式输出", type = "boolean", default = "false", description = "启用后 LLM 回复将逐字流式显示"),
    ) + PROVIDER_CAPABILITY_FIELDS,
)
