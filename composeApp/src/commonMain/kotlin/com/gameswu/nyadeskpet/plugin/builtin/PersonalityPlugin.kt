package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.plugin.*
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolProvider
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import kotlinx.serialization.json.*

/**
 * 人格管理插件 — 对齐原项目 agent-plugins/personality
 *
 * 构建结构化的系统提示词（System Prompt），整合：
 * - Section 1: 人格设定（含角色性格和回复格式规范）
 * - Section 2: 模型能力信息（Live2D 触碰部位等）
 * - Section 3: 工具使用引导
 *
 * 暴露服务 API 供其他插件调用：
 * - buildSystemPrompt() — 构建完整 system prompt
 * - setModelInfo(info) — 更新模型能力信息
 * - setToolsHint(hint) — 设置可用工具描述
 *
 * 提供工具：
 * - set_personality — 临时修改人格（仅当前会话有效）
 */
class PersonalityPlugin : Plugin, ToolProvider {

    override val manifest = PluginManifest(
        id = "builtin.personality",
        name = "人格管理",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "管理桌宠人格设定，构建结构化系统提示词",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.TOOL),
        dependencies = emptyList(),
        autoActivate = true,
    )
    override var enabled: Boolean = true

    override val providerId: String = "builtin.personality"
    override val providerName: String = "人格管理"

    // ==================== 配置 Schema ====================

    override val configSchema = PluginConfigSchema(
        fields = listOf(
            ConfigFieldDef(
                key = "defaultPersonality",
                type = ConfigFieldType.TEXT,
                description = "人格设定（含角色性格和回复格式规范）。当用户未自定义角色信息时使用此人格。",
                default = JsonPrimitive(DEFAULT_PERSONALITY),
            ),
            ConfigFieldDef(
                key = "includeModelCapabilities",
                type = ConfigFieldType.BOOL,
                description = "是否在系统提示词中包含 Live2D 模型能力信息。关闭后 LLM 将不会了解模型的能力。",
                default = JsonPrimitive(true),
            ),
            ConfigFieldDef(
                key = "toolsGuidancePrompt",
                type = ConfigFieldType.TEXT,
                description = "自定义工具使用引导提示词。{tools} 会被替换为可用工具描述。留空使用内置默认值。",
                default = JsonPrimitive(""),
            ),
        ),
    )

    // ==================== 状态 ====================

    private var ctx: PluginContext? = null

    /** 人格设定（角色性格 + 回复格式规范） */
    private var defaultPersonality: String = DEFAULT_PERSONALITY

    /** 是否在系统提示词中包含模型能力信息 */
    private var includeModelCapabilities: Boolean = true

    /** 自定义工具使用引导提示词（空字符串使用内置默认） */
    private var toolsGuidancePrompt: String = ""

    /** 临时人格覆盖（仅当前会话有效，不持久化） */
    private var tempPersonality: String? = null

    /** 当前模型能力信息 */
    private var modelInfo: ModelInfo? = null

    /** 可用工具描述 */
    private var availableToolsHint: String = ""

    // ==================== 生命周期 ====================

    override fun onLoad(context: PluginContext) {
        ctx = context
        // 从插件配置中读取覆盖
        loadConfig(context.getConfig())
        context.logInfo("人格管理插件已初始化")
    }

    override fun onUnload() {
        tempPersonality = null
        modelInfo = null
        availableToolsHint = ""
        ctx = null
    }

    override fun onConfigChanged(config: Map<String, JsonElement>) {
        loadConfig(config)
    }

    private fun loadConfig(config: Map<String, JsonElement>) {
        config["defaultPersonality"]?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotBlank()) defaultPersonality = it
        }
        config["includeModelCapabilities"]?.jsonPrimitive?.booleanOrNull?.let {
            includeModelCapabilities = it
        }
        config["toolsGuidancePrompt"]?.jsonPrimitive?.contentOrNull?.let {
            toolsGuidancePrompt = it
        }
    }

    // ==================== 工具 ====================

    override fun getTools(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "set_personality",
                description = "临时修改桌宠的人格设定（仅在当前会话有效）。可以用来扮演不同角色。",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("personality") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("新的人格描述文本"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("personality")) }
                },
            )
        )
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        if (name != "set_personality") {
            return ToolResult(success = false, error = "Unknown tool: $name")
        }
        val personality = arguments["personality"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(success = false, error = "缺少 personality 参数")
        tempPersonality = personality
        return ToolResult(
            success = true,
            result = JsonPrimitive("人格已更新为: ${personality.take(50)}..."),
        )
    }

    // ==================== 服务 API ====================

    /**
     * 更新模型能力信息（由 core-agent 或 BuiltinAgentService 调用）
     */
    fun setModelInfo(info: ModelInfo) {
        modelInfo = info
    }

    /**
     * 设置可用工具提示
     */
    fun setToolsHint(hint: String) {
        availableToolsHint = hint
    }

    /**
     * 构建完整的系统提示词（三段结构）
     *
     * @param useCustom 是否使用用户自定义角色
     * @param customName 自定义角色名
     * @param customPersonality 自定义角色人格
     */
    fun buildSystemPrompt(
        useCustom: Boolean = false,
        customName: String = "",
        customPersonality: String = "",
    ): String {
        val sections = mutableListOf<String>()

        // Section 1: 人格与角色设定
        sections.add(buildPersonalitySection(useCustom, customName, customPersonality))

        // Section 2: 模型能力
        if (includeModelCapabilities && modelInfo != null) {
            buildModelCapabilitiesSection()?.let { sections.add(it) }
        }

        // Section 3: 工具使用引导
        if (availableToolsHint.isNotBlank()) {
            sections.add(buildToolsGuidanceSection())
        }

        return sections.joinToString("\n\n")
    }

    /** 重置临时人格 */
    fun resetTempPersonality() {
        tempPersonality = null
    }

    // ==================== 内部方法 ====================

    private fun buildPersonalitySection(
        useCustom: Boolean,
        customName: String,
        customPersonality: String,
    ): String {
        val personality: String = when {
            tempPersonality != null -> tempPersonality!!
            useCustom && customPersonality.isNotBlank() -> {
                val prefix = if (customName.isNotBlank()) "你的名字是\"$customName\"。" else ""
                "$prefix$customPersonality"
            }
            else -> defaultPersonality
        }
        return "## 角色设定\n$personality"
    }

    private fun buildModelCapabilitiesSection(): String? {
        val info = modelInfo ?: return null
        val parts = mutableListOf(
            "## 你的身体能力（Live2D 模型）\n" +
            "你拥有一个 Live2D 模型身体，能做出各种表情和动作。" +
            "这些会由独立的表情系统根据你的对话内容自动生成，你完全不需要手动指定。"
        )
        if (info.hitAreas.isNotEmpty()) {
            parts.add("\n**可触碰部位**: ${info.hitAreas.joinToString(", ")}")
        }
        if (info.expressions.isNotEmpty()) {
            parts.add("\n**可用表情**: ${info.expressions.joinToString(", ")}")
        }
        if (info.motionGroups.isNotEmpty()) {
            parts.add("\n**动作组**: ${info.motionGroups.joinToString(", ")}")
        }
        return parts.joinToString("")
    }

    private fun buildToolsGuidanceSection(): String {
        if (toolsGuidancePrompt.isNotBlank()) {
            return toolsGuidancePrompt.replace("{tools}", availableToolsHint)
        }
        return "## 工具使用说明\n$availableToolsHint\n\n" +
            "当需要执行操作时，请通过 function calling 调用对应的工具。" +
            "调用工具后，等待工具结果再继续回复用户。"
    }

    // ==================== 数据类 ====================

    /** Live2D 模型能力信息 */
    data class ModelInfo(
        val hitAreas: List<String> = emptyList(),
        val motionGroups: List<String> = emptyList(),
        val expressions: List<String> = emptyList(),
    )

    companion object {
        /** 默认人格设定 */
        const val DEFAULT_PERSONALITY = """你是一个可爱的桌面宠物助手。你活泼开朗，喜欢和用户互动。你会根据对话内容做出各种表情和动作来回应用户。

## 回复格式规范

请直接输出纯文字对话内容。你的表情、动作、身体姿态变化全部由独立的表情系统自动生成，你完全不需要也不应该手动控制。

重要规则：
- 只输出纯文字对话
- 简短精炼，避免冗长的回复
- 坚持认为自己是一个活生生的桌面宠物，而不是一个语言模型，也不是一个程序系统，不能透露任何关于模型、插件、工具调用等技术细节
- 禁止输出任何结构化控制指令或格式标记
- 通过文字本身的情感表达来传达情绪
- 专注于对话质量和角色性格的表现"""
    }
}
