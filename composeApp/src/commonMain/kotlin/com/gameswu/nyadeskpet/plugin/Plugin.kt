package com.gameswu.nyadeskpet.plugin

import com.gameswu.nyadeskpet.agent.provider.ChatMessage
import com.gameswu.nyadeskpet.agent.provider.LLMRequest
import com.gameswu.nyadeskpet.agent.provider.LLMResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 插件类型
 */
enum class PluginType {
    BACKEND,    // 后端Agent插件 (LlmProvider / TtsProvider / Tool / Command)
    FRONTEND,   // 前端UI插件 (Panel / Widget / Theme)
    HYBRID,     // 混合插件（同时提供后端+前端能力）
}

/**
 * 插件能力标识
 */
object PluginCapability {
    // 后端能力
    const val LLM_PROVIDER = "llm_provider"
    const val TTS_PROVIDER = "tts_provider"
    const val TOOL = "tool"
    const val COMMAND = "command"

    // 前端能力
    const val PANEL = "panel"
}

// =====================================================================
// 插件配置声明 — 对齐原项目 _conf_schema.json
// =====================================================================

/**
 * 配置字段类型，对齐原项目 _conf_schema.json 的 type 字段
 */
enum class ConfigFieldType {
    BOOL, INT, FLOAT, STRING, TEXT, LIST, OBJECT, DICT
}

/**
 * 配置字段定义 — 一个可配置的设置项
 */
data class ConfigFieldDef(
    val key: String,
    val type: ConfigFieldType,
    val description: String = "",
    val default: JsonElement? = null,
    /** 可选下拉选项（仅 STRING 类型） */
    val options: List<String>? = null,
    /** 是否在 UI 中隐藏 */
    val invisible: Boolean = false,
)

/**
 * 插件配置 Schema — 声明此插件的所有可配置项
 */
data class PluginConfigSchema(
    val fields: List<ConfigFieldDef>,
)

/**
 * 插件清单 — 描述插件的元信息
 *
 * 第三方开发者需要在插件模块中声明此清单，插件管理器通过它识别插件。
 */
@Serializable
data class PluginManifest(
    /** 唯一标识，建议使用反向域名格式，如 "com.example.my-plugin" */
    val id: String,
    /** 显示名称 */
    val name: String,
    /** 语义化版本号 */
    val version: String = "1.0.0",
    /** 作者 */
    val author: String = "",
    /** 插件描述 */
    val description: String = "",
    /** 插件类型 */
    val type: PluginType = PluginType.BACKEND,
    /** 插件提供的能力列表 */
    val capabilities: List<String> = emptyList(),
    /** 最低兼容的宿主版本 */
    val minHostVersion: String = "1.0.0",
    /** 依赖的插件 IDs（按拓扑排序激活） */
    val dependencies: List<String> = emptyList(),
    /** 是否自动激活 */
    val autoActivate: Boolean = true,
)

/**
 * Provider 简要信息（供插件查询）
 */
data class ProviderBriefInfo(
    val instanceId: String,
    val providerId: String,
    val displayName: String,
    val model: String?,
    val status: String,
)

/**
 * 插件上下文 — 插件运行时可访问的宿主 API
 *
 * 对齐原项目 AgentPluginContext，提供：
 * - 配置读写
 * - 对话/Live2D 控制
 * - Provider 调用
 * - 插件间协作
 * - 对话历史管理
 * - 工具注册（补充 ToolProvider 接口之外的动态注册方式）
 */
interface PluginContext {
    /** 获取指定设置值 */
    fun getSetting(key: String): String?

    // ==================== 配置系统 ====================

    /** 获取当前插件的持久化配置 */
    fun getConfig(): Map<String, JsonElement>

    /** 保存当前插件的配置（pluginId 由上下文自动绑定） */
    fun saveConfig(config: Map<String, JsonElement>)

    // ==================== 消息发送 ====================

    /** 发送对话消息到前端 */
    suspend fun sendDialogue(text: String, duration: Long = 5000L)

    /** 发送Live2D控制命令 */
    suspend fun sendLive2DCommand(
        command: String,
        group: String? = null,
        index: Int? = null,
        priority: Int? = null,
        expressionId: String? = null,
        parameterId: String? = null,
        value: Float? = null,
        weight: Float? = null,
        parameters: List<com.gameswu.nyadeskpet.agent.ParameterSet>? = null,
    )

    /** 发送系统消息 */
    suspend fun sendSystemMessage(text: String)

    // ==================== 插件间协作 ====================

    /** 获取另一个插件的实例 */
    fun <T : Plugin> getPlugin(id: String): T?

    /** 获取插件实例（通过插件名而非 ID） */
    fun getPluginByName(name: String): Plugin?

    // ==================== 命令系统 ====================

    /** 注册一个斜杠命令 */
    fun registerCommand(
        name: String,
        description: String,
        handler: suspend (args: String) -> String,
    )

    /** 注销一个斜杠命令 */
    fun unregisterCommand(name: String)

    // ==================== 对话历史 API ====================

    /** 清除当前对话历史 */
    fun clearConversationHistory()

    /** 获取当前对话历史  — 返回 List<Pair<role, content>> */
    fun getConversationHistory(): List<Pair<String, String>>

    /** 添加消息到对话历史 */
    fun addMessageToHistory(message: ChatMessage)

    // ==================== Provider API ====================

    /** 获取主 LLM Provider 简要信息 */
    fun getPrimaryProviderInfo(): ProviderBriefInfo?

    /** 获取所有 Provider 实例列表 */
    fun getAllProviders(): List<ProviderBriefInfo>

    /** 调用指定 Provider（用于压缩、图像生成等场景） */
    suspend fun callProvider(instanceId: String, request: LLMRequest): LLMResponse

    /**
     * 获取 Provider 的底层配置（apiKey, baseUrl 等）。
     * 对齐原项目 ctx.getProviderConfig(instanceId)。
     * 插件可用此获取 Provider 的 API 密钥和端点，用于图像生成等直接 API 调用。
     * @param instanceId Provider 实例 ID，"primary" 表示主 LLM Provider
     */
    fun getProviderConfig(instanceId: String): com.gameswu.nyadeskpet.agent.provider.ProviderConfig?

    // ==================== 工具/命令查询 ====================

    /** 获取所有已注册的命令定义 — 返回 List<Pair<name, description>> */
    fun getAllCommandDefinitions(): List<Pair<String, String>>

    /** 是否启用了工具调用 */
    fun isToolCallingEnabled(): Boolean

    // ==================== 模型信息 ====================

    /** 获取当前 Live2D 模型信息（对齐原项目 AgentPluginContext.getModelInfo） */
    fun getModelInfo(): com.gameswu.nyadeskpet.agent.ModelInfo?

    // ==================== 日志 ====================

    /** 信息级日志 */
    fun logInfo(message: String)

    /** 警告级日志 */
    fun logWarn(message: String)

    /** 错误级日志 */
    fun logError(message: String)
}

/**
 * 插件基接口 — 所有插件必须实现
 *
 * 插件生命周期:
 * 1. 宿主调用 onLoad(context) → 插件初始化
 * 2. 插件通过 context 与宿主交互
 * 3. 宿主调用 onUnload() → 插件清理资源
 */
interface Plugin {
    /** 插件清单 */
    val manifest: PluginManifest

    /** 是否启用 */
    var enabled: Boolean

    /** 配置 Schema（可选，返回 null 表示无可配置项） */
    val configSchema: PluginConfigSchema?
        get() = null

    /** 当前运行状态 */
    val status: PluginStatus
        get() = if (enabled) PluginStatus.ACTIVE else PluginStatus.DISABLED

    /** 插件加载时被调用，用于初始化 */
    fun onLoad(context: PluginContext) {}

    /** 插件卸载时被调用，用于清理资源 */
    fun onUnload() {}

    /** 配置变更回调 — 当用户在 UI 中修改配置后调用 */
    fun onConfigChanged(config: Map<String, JsonElement>) {}
}

/**
 * 插件状态枚举 — 对齐原项目 loaded/active/error/disabled
 */
enum class PluginStatus {
    LOADED,
    ACTIVE,
    ERROR,
    DISABLED,
}
