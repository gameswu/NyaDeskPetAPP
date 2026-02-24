/**
 * 预设 Provider 合集
 * 对齐原 Electron 项目 src/agent/providers/ 下各子类：
 * - SiliconFlow（硅基流动）
 * - DashScope（阿里云百炼 / 通义千问）
 * - Zhipu（智谱 AI / GLM）
 * - Volcengine（火山引擎 / 豆包）
 * - Groq
 * - Mistral
 * - xAI
 *
 * 所有 Provider 均继承 OpenAIProvider，仅预设 baseUrl / model 和自定义 configSchema。
 * OpenRouter / Anthropic / Gemini 因有特殊逻辑，在各自独立文件中实现。
 */
package com.gameswu.nyadeskpet.agent.provider.providers

import com.gameswu.nyadeskpet.agent.provider.*

// ==================== SiliconFlow（硅基流动）====================

class SiliconFlowProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = config.baseUrl ?: "https://api.siliconflow.cn/v1",
        model = config.model ?: "Qwen/Qwen2.5-7B-Instruct",
    )
) {
    override fun getMetadata() = SILICONFLOW_METADATA
}

val SILICONFLOW_METADATA = ProviderMetadata(
    id = "siliconflow",
    name = "SiliconFlow（硅基流动）",
    description = "硅基流动一站式云服务平台，集合 DeepSeek、Qwen、GLM 等顶尖大模型，部分模型免费使用，支持 Function Calling",
    configSchema = listOf(
        ProviderConfigField(key = "apiKey", label = "API Key", type = "password", required = true, placeholder = "sk-...", description = "从 SiliconFlow 平台获取的 API 密钥（https://cloud.siliconflow.cn/account/ak）"),
        ProviderConfigField(key = "baseUrl", label = "API Base URL", type = "string", default = "https://api.siliconflow.cn/v1", placeholder = "https://api.siliconflow.cn/v1", description = "SiliconFlow API 地址，通常无需修改"),
        ProviderConfigField(key = "model", label = "模型", type = "string", default = "Qwen/Qwen2.5-7B-Instruct", placeholder = "Qwen/Qwen2.5-7B-Instruct", description = "模型 ID，如 Qwen/Qwen2.5-7B-Instruct、deepseek-ai/DeepSeek-V3。完整列表见 https://cloud.siliconflow.cn/models"),
        ProviderConfigField(key = "timeout", label = "超时时间（秒）", type = "number", default = "60", description = "请求超时时间，推理模型建议适当增大"),
        ProviderConfigField(key = "proxy", label = "代理地址", type = "string", placeholder = "http://127.0.0.1:7890", description = "HTTP/HTTPS 代理（如需使用）"),
        ProviderConfigField(key = "stream", label = "流式输出", type = "boolean", default = "false", description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验"),
    ) + PROVIDER_CAPABILITY_FIELDS,
)

// ==================== DashScope（阿里云百炼）====================

class DashScopeProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = config.baseUrl ?: "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = config.model ?: "qwen-plus",
    )
) {
    override fun getMetadata() = DASHSCOPE_METADATA
}

val DASHSCOPE_METADATA = ProviderMetadata(
    id = "dashscope",
    name = "DashScope（阿里云百炼）",
    description = "阿里云百炼平台，通义千问全系列模型（Qwen），支持 Vision、Function Calling、深度思考，国内直连",
    configSchema = listOf(
        ProviderConfigField(key = "apiKey", label = "API Key", type = "password", required = true, placeholder = "sk-...", description = "从阿里云百炼平台获取的 API 密钥（https://bailian.console.aliyun.com/#/key-manage）"),
        ProviderConfigField(key = "baseUrl", label = "API Base URL", type = "string", default = "https://dashscope.aliyuncs.com/compatible-mode/v1", description = "DashScope OpenAI 兼容端点，通常无需修改"),
        ProviderConfigField(key = "model", label = "模型", type = "string", default = "qwen-plus", placeholder = "qwen-plus", description = "模型 ID，如 qwen-plus、qwen-turbo、qwen-max、qwen-long、qwq-plus"),
        ProviderConfigField(key = "timeout", label = "超时时间（秒）", type = "number", default = "60", description = "请求超时时间，推理模型建议适当增大"),
        ProviderConfigField(key = "proxy", label = "代理地址", type = "string", placeholder = "http://127.0.0.1:7890", description = "HTTP/HTTPS 代理（国内一般无需使用）"),
        ProviderConfigField(key = "stream", label = "流式输出", type = "boolean", default = "false", description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验"),
    ) + PROVIDER_CAPABILITY_FIELDS,
)

// ==================== 智谱 AI（GLM）====================

class ZhipuProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = config.baseUrl ?: "https://open.bigmodel.cn/api/paas/v4",
        model = config.model ?: "glm-4-flash",
    )
) {
    override fun getMetadata() = ZHIPU_METADATA
}

val ZHIPU_METADATA = ProviderMetadata(
    id = "zhipu",
    name = "智谱 AI（GLM）",
    description = "智谱 AI 开放平台，GLM-4 系列模型，glm-4-flash 免费使用，支持 Vision 和 Function Calling，国内直连",
    configSchema = listOf(
        ProviderConfigField(key = "apiKey", label = "API Key", type = "password", required = true, placeholder = "your_api_key", description = "从智谱 AI 开放平台获取的 API 密钥（https://open.bigmodel.cn/usercenter/apikeys）"),
        ProviderConfigField(key = "baseUrl", label = "API Base URL", type = "string", default = "https://open.bigmodel.cn/api/paas/v4", description = "智谱 AI API 地址，通常无需修改"),
        ProviderConfigField(key = "model", label = "模型", type = "string", default = "glm-4-flash", placeholder = "glm-4-flash", description = "模型 ID，如 glm-4-flash、glm-4-plus、glm-4-long、glm-4v-plus"),
        ProviderConfigField(key = "timeout", label = "超时时间（秒）", type = "number", default = "60", description = "请求超时时间"),
        ProviderConfigField(key = "proxy", label = "代理地址", type = "string", placeholder = "http://127.0.0.1:7890", description = "HTTP/HTTPS 代理（国内一般无需使用）"),
        ProviderConfigField(key = "stream", label = "流式输出", type = "boolean", default = "false", description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验"),
    ) + PROVIDER_CAPABILITY_FIELDS,
)

// ==================== 火山引擎（豆包）====================

class VolcengineProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = config.baseUrl ?: "https://ark.cn-beijing.volces.com/api/v3",
    )
) {
    override fun getMetadata() = VOLCENGINE_METADATA
}

val VOLCENGINE_METADATA = ProviderMetadata(
    id = "volcengine",
    name = "火山引擎（豆包）",
    description = "火山引擎方舟平台，豆包大模型（Doubao），使用推理接入点 ID 访问，支持 Function Calling 和 Vision，国内直连",
    configSchema = listOf(
        ProviderConfigField(key = "apiKey", label = "API Key", type = "password", required = true, placeholder = "your_api_key", description = "从火山引擎方舟平台获取的 API 密钥（https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey）"),
        ProviderConfigField(key = "baseUrl", label = "API Base URL", type = "string", default = "https://ark.cn-beijing.volces.com/api/v3", description = "火山引擎方舟 API 地址，通常无需修改"),
        ProviderConfigField(key = "model", label = "推理接入点 ID", type = "string", required = true, placeholder = "ep-20240901xxxxx-xxxxx", description = "在方舟控制台创建的推理接入点 ID（Endpoint ID），非模型名称。创建地址：https://console.volcengine.com/ark/region:ark+cn-beijing/endpoint"),
        ProviderConfigField(key = "timeout", label = "超时时间（秒）", type = "number", default = "60", description = "请求超时时间"),
        ProviderConfigField(key = "proxy", label = "代理地址", type = "string", placeholder = "http://127.0.0.1:7890", description = "HTTP/HTTPS 代理（国内一般无需使用）"),
        ProviderConfigField(key = "stream", label = "流式输出", type = "boolean", default = "false", description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验"),
    ) + PROVIDER_CAPABILITY_FIELDS,
)

// ==================== Groq ====================

class GroqProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = config.baseUrl ?: "https://api.groq.com/openai/v1",
        model = config.model ?: "llama-3.3-70b-versatile",
    )
) {
    override fun getMetadata() = GROQ_METADATA
}

val GROQ_METADATA = ProviderMetadata(
    id = "groq",
    name = "Groq",
    description = "Groq 超快 LPU 推理平台，支持 Llama、DeepSeek、Qwen、Gemma 等开源模型，有免费额度，推理速度极快",
    configSchema = listOf(
        ProviderConfigField(key = "apiKey", label = "API Key", type = "password", required = true, placeholder = "gsk_...", description = "从 Groq 控制台获取的 API 密钥（https://console.groq.com/keys）"),
        ProviderConfigField(key = "baseUrl", label = "API Base URL", type = "string", default = "https://api.groq.com/openai/v1", description = "Groq API 地址，通常无需修改"),
        ProviderConfigField(key = "model", label = "模型", type = "string", default = "llama-3.3-70b-versatile", placeholder = "llama-3.3-70b-versatile", description = "模型 ID，如 llama-3.3-70b-versatile、llama-3.1-8b-instant。完整列表见 https://console.groq.com/docs/models"),
        ProviderConfigField(key = "timeout", label = "超时时间（秒）", type = "number", default = "60", description = "请求超时时间"),
        ProviderConfigField(key = "proxy", label = "代理地址", type = "string", placeholder = "http://127.0.0.1:7890", description = "HTTP/HTTPS 代理（如需翻墙访问）"),
        ProviderConfigField(key = "stream", label = "流式输出", type = "boolean", default = "false", description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验"),
    ) + PROVIDER_CAPABILITY_FIELDS,
)

// ==================== Mistral ====================

class MistralProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = config.baseUrl ?: "https://api.mistral.ai/v1",
        model = config.model ?: "mistral-small-latest",
    )
) {
    override fun getMetadata() = MISTRAL_METADATA
}

val MISTRAL_METADATA = ProviderMetadata(
    id = "mistral",
    name = "Mistral AI",
    description = "Mistral AI 官方 API，支持 Mistral Large/Small、Codestral 代码模型、Pixtral 多模态模型，欧洲公司注重数据安全",
    configSchema = listOf(
        ProviderConfigField(key = "apiKey", label = "API Key", type = "password", required = true, placeholder = "your_api_key", description = "从 Mistral AI 控制台获取的 API 密钥（https://console.mistral.ai/api-keys/）"),
        ProviderConfigField(key = "baseUrl", label = "API Base URL", type = "string", default = "https://api.mistral.ai/v1", description = "Mistral AI API 地址，通常无需修改"),
        ProviderConfigField(key = "model", label = "模型", type = "string", default = "mistral-small-latest", placeholder = "mistral-small-latest", description = "模型 ID，如 mistral-small-latest、mistral-large-latest、codestral-latest"),
        ProviderConfigField(key = "timeout", label = "超时时间（秒）", type = "number", default = "60", description = "请求超时时间"),
        ProviderConfigField(key = "proxy", label = "代理地址", type = "string", placeholder = "http://127.0.0.1:7890", description = "HTTP/HTTPS 代理（如需翻墙访问）"),
        ProviderConfigField(key = "stream", label = "流式输出", type = "boolean", default = "false", description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验"),
    ) + PROVIDER_CAPABILITY_FIELDS,
)

// ==================== xAI ====================

class XAIProvider(config: ProviderConfig) : OpenAIProvider(
    config.copy(
        baseUrl = config.baseUrl ?: "https://api.x.ai/v1",
        model = config.model ?: "grok-3-mini",
    )
) {
    override fun getMetadata() = XAI_METADATA
}

val XAI_METADATA = ProviderMetadata(
    id = "xai",
    name = "xAI（Grok）",
    description = "xAI 官方 API，Grok 系列模型，强大的推理和实时信息获取能力，支持 Vision 和 Function Calling",
    configSchema = listOf(
        ProviderConfigField(key = "apiKey", label = "API Key", type = "password", required = true, placeholder = "xai-...", description = "从 xAI 控制台获取的 API 密钥（https://console.x.ai/）"),
        ProviderConfigField(key = "baseUrl", label = "API Base URL", type = "string", default = "https://api.x.ai/v1", description = "xAI API 地址，通常无需修改"),
        ProviderConfigField(key = "model", label = "模型", type = "string", default = "grok-3-mini", placeholder = "grok-3-mini", description = "模型 ID，如 grok-3、grok-3-mini、grok-2"),
        ProviderConfigField(key = "timeout", label = "超时时间（秒）", type = "number", default = "60", description = "请求超时时间"),
        ProviderConfigField(key = "proxy", label = "代理地址", type = "string", placeholder = "http://127.0.0.1:7890", description = "HTTP/HTTPS 代理（如需翻墙访问）"),
        ProviderConfigField(key = "stream", label = "流式输出", type = "boolean", default = "false", description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验"),
    ) + PROVIDER_CAPABILITY_FIELDS,
)

// ==================== 统一注册 ====================

/**
 * 注册所有预设 Provider（不含 OpenRouter / Anthropic / Gemini，它们在各自文件中）
 */
fun registerAllPresetProviders() {
    registerProvider(SILICONFLOW_METADATA) { SiliconFlowProvider(it) }
    registerProvider(DASHSCOPE_METADATA) { DashScopeProvider(it) }
    registerProvider(ZHIPU_METADATA) { ZhipuProvider(it) }
    registerProvider(VOLCENGINE_METADATA) { VolcengineProvider(it) }
    registerProvider(GROQ_METADATA) { GroqProvider(it) }
    registerProvider(MISTRAL_METADATA) { MistralProvider(it) }
    registerProvider(XAI_METADATA) { XAIProvider(it) }
}