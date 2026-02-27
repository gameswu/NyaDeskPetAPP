/**
 * LLM Provider 抽象层
 *
 * 对齐原 Electron 项目 src/agent/provider.ts 的设计：
 * - 策略模式：LLMProvider 抽象基类，统一接口
 * - 注册表模式：ProviderRegistry 单例，工厂函数注册
 * - 配置声明：ProviderMetadata + ProviderConfigField，可驱动动态 UI
 * - 生命周期：initialize() → chat/chatStream → terminate()
 *
 * 扩展指南：
 * 1. 继承 LLMProvider
 * 2. 实现 getMetadata() 和 chat()，可选覆盖 chatStream()
 * 3. 调用 registerProvider() 注册到全局注册表
 */
package com.gameswu.nyadeskpet.agent.provider

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ==================== Provider 配置 ====================

/**
 * Provider 实例配置
 * 对应原项目 ProviderConfig — 通用键值对 + 固定字段
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val timeout: Int? = null,
    val proxy: String? = null,
    /** 扩展字段（如 stream、supportsVision 等） */
    val extra: Map<String, String> = emptyMap(),
)

/**
 * Provider 配置字段声明 — 驱动设置 UI 动态表单
 * 对应原项目 ProviderConfigField
 */
@Serializable
data class ProviderConfigField(
    val key: String,
    val label: String,
    /** "string" | "password" | "number" | "select" | "boolean" */
    val type: String,
    val required: Boolean = false,
    val default: String? = null,
    val placeholder: String? = null,
    val options: List<String>? = null,
    val description: String? = null,
)

/**
 * Provider 元信息 — 描述 Provider 类型的静态信息
 * 对应原项目 ProviderMetadata
 */
@Serializable
data class ProviderMetadata(
    val id: String,
    val name: String,
    val description: String = "",
    val configSchema: List<ProviderConfigField> = emptyList(),
)

// ==================== ChatMessage ====================

/**
 * 对话消息
 * 对应原项目 ChatMessage
 */
@Serializable
data class ChatMessage(
    val role: String,   // "system" | "user" | "assistant" | "tool"
    val content: String = "",
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCallInfo>? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val images: List<ChatMessageImage>? = null,
    val isCommand: Boolean = false,
    /** 多模态附件（图片/文件）— 对齐原项目 ChatMessage.attachment */
    val attachment: ChatMessageAttachment? = null,
)

/**
 * 消息附件（多模态）
 * 对应原项目 ChatMessage.attachment
 */
@Serializable
data class ChatMessageAttachment(
    val type: String,              // "image" | "file"
    val data: String? = null,      // Base64 编码数据
    val url: String? = null,       // URL 引用
    val mimeType: String? = null,  // MIME 类型
    val fileName: String? = null,  // 文件名
)

@Serializable
data class ChatMessageImage(
    val data: String,
    val mimeType: String = "image/png",
)

// ==================== Tool Calling ====================

@Serializable
data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolDefinitionSchema(
    val type: String = "function",
    val function: ToolFunctionDef,
)

@Serializable
data class ToolFunctionDef(
    val name: String,
    val description: String = "",
    val parameters: JsonObject? = null,
)

// ==================== 用量 ====================

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

// ==================== LLM 请求 / 响应 ====================

/**
 * LLM 请求
 * 对应原项目 LLMRequest
 */
data class LLMRequest(
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val sessionId: String? = null,
    val model: String? = null,
    val tools: List<ToolDefinitionSchema>? = null,
    /** "none" | "auto" | "required" */
    val toolChoice: String? = null,
)

/**
 * LLM 完整响应
 * 对应原项目 LLMResponse
 */
data class LLMResponse(
    val text: String = "",
    val usage: TokenUsage? = null,
    val model: String? = null,
    val finishReason: String? = null,
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCallInfo>? = null,
)

/**
 * LLM 流式块
 * 对应原项目 LLMStreamChunk
 */
data class LLMStreamChunk(
    val delta: String = "",
    val done: Boolean = false,
    val usage: TokenUsage? = null,
    val reasoningDelta: String? = null,
    val finishReason: String? = null,
    val toolCallDeltas: List<ToolCallDelta>? = null,
)

data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

// ==================== LLM Provider 抽象基类 ====================

/**
 * LLM Provider 抽象基类
 * 对应原项目 LLMProvider class
 *
 * 子类需实现:
 * - getMetadata()
 * - chat(request) → LLMResponse
 *
 * 可选覆盖:
 * - chatStream()   — 流式输出
 * - initialize()   — 初始化 HTTP 客户端
 * - terminate()    — 销毁资源
 * - test()         — 连接测试
 * - getModels()    — 获取可用模型列表
 */
abstract class LLMProvider(protected val config: ProviderConfig) {
    protected var initialized: Boolean = false
    protected var modelName: String = config.model ?: ""

    /** 获取 Provider 元信息 */
    abstract fun getMetadata(): ProviderMetadata

    /** 非流式对话 */
    abstract suspend fun chat(request: LLMRequest): LLMResponse

    /**
     * 流式对话 — 默认回退到非流式
     * @param onChunk 每收到一个数据块时的回调
     */
    open suspend fun chatStream(request: LLMRequest, onChunk: suspend (LLMStreamChunk) -> Unit) {
        val response = chat(request)
        // 先发一个 done=false 的内容 chunk，确保 handleStreamingChat() 能收集到内容
        if (response.text.isNotEmpty()) {
            onChunk(LLMStreamChunk(delta = response.text, done = false, usage = response.usage))
        }
        onChunk(LLMStreamChunk(delta = "", done = true, usage = response.usage))
    }

    /** 初始化 Provider（如创建 HTTP 客户端） */
    open suspend fun initialize() {
        initialized = true
    }

    /** 销毁 Provider */
    open suspend fun terminate() {
        initialized = false
    }

    /** 连接测试 */
    open suspend fun test(): TestResult {
        return try {
            if (!initialized) initialize()
            val models = getModels()
            if (models.isEmpty()) {
                TestResult(false, "API 返回的模型列表为空")
            } else {
                TestResult(true, model = modelName.ifBlank { models.first() })
            }
        } catch (e: Exception) {
            TestResult(false, e.message)
        }
    }

    /** 获取可用模型列表 */
    open suspend fun getModels(): List<String> = emptyList()

    fun setModel(model: String) {
        modelName = model
    }

    fun getModel(): String = modelName

    /**
     * 创建已配置超时和代理的 HttpClient
     */
    protected fun buildHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient =
        buildProviderHttpClient(config, block)

    /** 类型安全的配置值读取 */
    protected fun <T> getConfigValue(key: String, defaultValue: T): T =
        getProviderConfigValue(config, key, defaultValue)
}

data class TestResult(
    val success: Boolean,
    val error: String? = null,
    val model: String? = null,
)

// ==================== Provider 注册表 ====================

typealias ProviderFactory = (ProviderConfig) -> LLMProvider

/**
 * Provider 注册表（全局单例）
 * 对应原项目 ProviderRegistry / providerRegistry
 */
object ProviderRegistry {
    private data class RegistryEntry(
        val metadata: ProviderMetadata,
        val factory: ProviderFactory,
    )

    private val entries = mutableMapOf<String, RegistryEntry>()

    fun register(metadata: ProviderMetadata, factory: ProviderFactory) {
        entries[metadata.id] = RegistryEntry(metadata, factory)
    }

    /** 创建 Provider 实例 */
    fun create(id: String, config: ProviderConfig): LLMProvider? {
        val entry = entries[id] ?: return null
        return entry.factory(config)
    }

    /** 获取所有已注册的 Provider 类型元信息 */
    fun getAll(): List<ProviderMetadata> = entries.values.map { it.metadata }

    fun get(id: String): ProviderMetadata? = entries[id]?.metadata

    fun has(id: String): Boolean = entries.containsKey(id)
}

/** 注册 Provider 的便捷函数 */
fun registerProvider(metadata: ProviderMetadata, factory: ProviderFactory) {
    ProviderRegistry.register(metadata, factory)
}

// ==================== Provider 能力字段 ====================

/**
 * 通用能力声明字段 — 附加到每个 Provider 的 configSchema 末尾
 * 对应原项目 PROVIDER_CAPABILITY_FIELDS
 */
val PROVIDER_CAPABILITY_FIELDS = listOf(
    ProviderConfigField(
        key = "supportsText", label = "文本对话", type = "boolean",
        default = "true", description = "是否支持文本对话"
    ),
    ProviderConfigField(
        key = "supportsVision", label = "图片识别", type = "boolean",
        default = "false", description = "是否支持图片输入（Vision）"
    ),
    ProviderConfigField(
        key = "supportsFile", label = "文件处理", type = "boolean",
        default = "false", description = "是否支持文件上传"
    ),
    ProviderConfigField(
        key = "supportsToolCalling", label = "工具调用", type = "boolean",
        default = "true", description = "是否支持 Function Calling"
    ),
)

// ==================== Provider 实例管理 ====================

/**
 * Provider 实例配置（持久化到设置中）
 * 对应原项目 ProviderInstanceConfig
 */
@Serializable
data class ProviderInstanceConfig(
    /** 实例唯一 ID (uuid) */
    val instanceId: String,
    /** Provider 类型 ID (如 "openai", "deepseek") */
    val providerId: String,
    /** 用户自定义的显示名称 */
    val displayName: String,
    /** Provider 配置参数 */
    val config: ProviderConfig,
    /** 是否启用 */
    val enabled: Boolean = false,
)

/**
 * Provider 实例运行时状态
 * 对应原项目 ProviderInstanceInfo
 */
data class ProviderInstanceInfo(
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

enum class ProviderStatus {
    IDLE, CONNECTING, CONNECTED, ERROR
}
