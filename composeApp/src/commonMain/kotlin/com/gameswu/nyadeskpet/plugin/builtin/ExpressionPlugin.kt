package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.agent.*
import com.gameswu.nyadeskpet.agent.provider.ChatMessage
import com.gameswu.nyadeskpet.agent.provider.LLMRequest
import com.gameswu.nyadeskpet.plugin.*
import kotlinx.serialization.json.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 表情生成器插件 — 对齐原项目 agent-plugins/expression-generator
 *
 * 使用独立的 LLM 调用，根据对话文本生成 Live2D 参数控制指令。
 * 将表情/动作生成与对话文本生成完全分离。
 *
 * 工作流：
 * 1. 接收对话 LLM 产出的纯文本回复
 * 2. 构建表情专用系统提示词（含模型可用参数/表情/动作列表）
 * 3. 调用配置的 LLM Provider（或主 LLM）生成纯 JSON 参数指令
 * 4. 校验参数合法性（clamp 到 min/max 范围）
 * 5. 返回结构化的 Live2D 控制指令列表
 *
 * 暴露服务 API 供 BuiltinAgentService 调用：
 * - generateExpression(dialogueText, modelInfo) → List<Live2DCommandData>
 * - isEnabled() → Boolean
 */
class ExpressionPlugin : Plugin {

    override val manifest = PluginManifest(
        id = "builtin.expression",
        name = "expression-generator",
        version = "1.0.0",
        author = "gameswu",
        description = "LLM 驱动的 Live2D 表情生成器 — 使用独立 LLM 将对话文本转化为 Live2D 参数控制指令，实现自然灵动的表情动画",
        type = PluginType.BACKEND,
        autoActivate = true,
    )
    override var enabled: Boolean = true

    override val configSchema: PluginConfigSchema
        get() = PluginConfigSchema(
            fields = listOf(
                ConfigFieldDef(
                    key = "expressionProviderId",
                    type = ConfigFieldType.STRING,
                    description = "表情生成使用的 LLM Provider 实例 ID。留空或填写无效 ID 时自动使用主 LLM Provider。",
                    default = JsonPrimitive(""),
                ),
                ConfigFieldDef(
                    key = "temperature",
                    type = ConfigFieldType.FLOAT,
                    description = "表情生成 LLM 的温度参数。较高的值会产生更丰富多样的表情。",
                    default = JsonPrimitive(0.7),
                ),
                ConfigFieldDef(
                    key = "maxTokens",
                    type = ConfigFieldType.INT,
                    description = "表情生成 LLM 的最大输出 token 数。通常 300 即可满足 JSON 输出需求。",
                    default = JsonPrimitive(300),
                ),
                ConfigFieldDef(
                    key = "enabled",
                    type = ConfigFieldType.BOOL,
                    description = "是否启用 LLM 表情生成。关闭后将不生成表情指令，模型保持默认姿态。",
                    default = JsonPrimitive(true),
                ),
            )
        )

    private var ctx: PluginContext? = null

    // 配置项（对应 _conf_schema.json）
    private var expressionProviderId: String = ""
    private var temperature: Float = 0.7f
    private var maxTokens: Int = 300
    private var expressionEnabled: Boolean = true

    /** LLM 最大重试次数（对齐原项目 MAX_RETRIES = 1） */
    private val maxRetries = 1

    override fun onLoad(context: PluginContext) {
        ctx = context
        loadConfig()
        context.logInfo("表情生成器插件已初始化")
    }

    override fun onUnload() {
        ctx?.logInfo("表情生成器插件已停止")
        ctx = null
    }

    override fun onConfigChanged(config: Map<String, JsonElement>) {
        loadConfig()
    }

    private fun loadConfig() {
        val config = ctx?.getConfig() ?: return
        expressionProviderId = config["expressionProviderId"]?.jsonPrimitive?.contentOrNull ?: ""
        temperature = config["temperature"]?.jsonPrimitive?.floatOrNull ?: 0.7f
        maxTokens = config["maxTokens"]?.jsonPrimitive?.intOrNull ?: 300
        expressionEnabled = config["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
    }

    // ==================== 服务 API ====================

    /** 是否启用表情生成 */
    fun isEnabled(): Boolean = enabled && expressionEnabled

    /**
     * 根据对话文本生成 Live2D 控制指令（对齐原项目 generateExpression）
     *
     * @param dialogueText 对话 LLM 产出的纯文本回复
     * @param modelInfo 当前 Live2D 模型信息
     * @return Live2D 控制指令列表
     */
    suspend fun generateExpression(dialogueText: String, modelInfo: ModelInfo?): List<Live2DCommandData> {
        if (!isEnabled() || dialogueText.isBlank()) return emptyList()

        // 没有模型信息时无法生成参数指令
        if (modelInfo == null) return emptyList()

        val context = ctx ?: return emptyList()

        val providerId = resolveProviderId()
        if (providerId == null) {
            context.logWarn("无可用的 LLM Provider，跳过表情生成")
            return emptyList()
        }

        val systemPrompt = buildExpressionSystemPrompt(modelInfo)
        val userPrompt = "对话文本: \"$dialogueText\""

        for (attempt in 0..maxRetries) {
            try {
                val response = context.callProvider(providerId, LLMRequest(
                    messages = listOf(ChatMessage(role = "user", content = userPrompt)),
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                ))

                val result = parseAndValidate(response.text, modelInfo)
                if (result.isEmpty() && attempt < maxRetries) {
                    context.logWarn("表情生成解析失败 (尝试 ${attempt + 1}/${maxRetries + 1})")
                    continue
                }
                context.logInfo("表情生成完成: ${result.size} 个有效指令")
                return result
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    context.logWarn("表情生成 LLM 调用失败 (尝试 ${attempt + 1}): ${e.message}")
                    continue
                }
                context.logError("表情生成最终失败: ${e.message}")
                return emptyList()
            }
        }

        return emptyList()
    }

    // ==================== 内部方法 ====================

    /**
     * 解析配置的 Provider ID，验证可用性，回退到主 LLM（对齐原项目 _resolveProviderId）
     */
    private fun resolveProviderId(): String? {
        val context = ctx ?: return null

        // 如果配置了特定 Provider，检查其是否存在且可用
        if (expressionProviderId.isNotBlank()) {
            val providers = context.getAllProviders()
            val target = providers.find { it.instanceId == expressionProviderId }
            if (target != null && target.status == "CONNECTED") {
                return expressionProviderId
            }
            context.logWarn("配置的表情 Provider \"$expressionProviderId\" 不可用，回退到主 LLM")
        }

        // 回退到主 LLM
        val primary = context.getPrimaryProviderInfo() ?: return null
        if (primary.status != "CONNECTED") return null
        // echo provider 不能用于生成
        if (primary.providerId == "echo") return null
        return "primary"
    }

    /**
     * 构建表情生成专用的系统提示词（对齐原项目 _buildExpressionSystemPrompt）
     */
    private fun buildExpressionSystemPrompt(modelInfo: ModelInfo): String {
        val sections = mutableListOf<String>()

        sections.add(
            "你是一个 Live2D 模型的表情控制器。你的任务是根据对话文本的情感和语义，生成合适的 Live2D 控制指令。\n\n" +
            "你必须且只能输出一个 JSON 对象，不要输出任何其他文字。"
        )

        // 模型能力
        val capabilities = mutableListOf("## 可用控制能力")

        // 表情列表（优先映射版）
        if (!modelInfo.mappedExpressions.isNullOrEmpty()) {
            val expList = modelInfo.mappedExpressions.joinToString("\n") { "  - ${it.alias}: ${it.description}" }
            capabilities.add("\n**预设表情**:\n$expList")
        } else if (modelInfo.expressions.isNotEmpty()) {
            capabilities.add("\n**预设表情**: ${modelInfo.expressions.joinToString(", ")}")
        }

        // 动作列表（优先映射版）
        if (!modelInfo.mappedMotions.isNullOrEmpty()) {
            val motionList = modelInfo.mappedMotions.joinToString("\n") { "  - ${it.alias}: ${it.description}" }
            capabilities.add("\n**可用动作**:\n$motionList")
        } else if (modelInfo.motions.isNotEmpty()) {
            val motionList = modelInfo.motions.entries.joinToString("\n") { (group, info) ->
                "  - $group（${info.count} 个变体）"
            }
            capabilities.add("\n**动作组**:\n$motionList")
        }

        // 参数列表（优先映射版）
        if (!modelInfo.mappedParameters.isNullOrEmpty()) {
            val paramList = modelInfo.mappedParameters.joinToString("\n") {
                "  - ${it.alias}: ${it.description}（${it.min} ~ ${it.max}，默认 ${it.default}）"
            }
            capabilities.add("\n**可控参数**（推荐优先使用）:\n$paramList")
        } else if (modelInfo.availableParameters.isNotEmpty()) {
            val paramList = modelInfo.availableParameters.joinToString("\n") {
                "  - ${it.id}: ${it.min} ~ ${it.max}（默认 ${it.default}）"
            }
            capabilities.add("\n**可控参数**（推荐优先使用）:\n$paramList")
        }

        sections.add(capabilities.joinToString(""))

        // 输出格式规范
        sections.add(
            """## 输出格式

输出一个 JSON 对象，格式如下：

```json
{
  "expression": "简短描述当前情感",
  "actions": [
    {
      "type": "parameter",
      "parameterId": "参数名称",
      "value": 数值
    },
    {
      "type": "expression",
      "expressionId": "表情名称"
    },
    {
      "type": "motion",
      "group": "动作名称"
    }
  ]
}
```

### 规则
- **parameterId / expressionId / group 必须使用上方列表中给出的名称**（动作使用可用动作列表中的名称）
- **parameter** 的 value 必须在对应参数的 min ~ max 范围内
- 过渡动画时长由系统根据参数变化幅度自动计算，你无需指定
- 多个 parameter 可以组合出丰富的表情（如歪头+眯眼+微笑）
- **优先使用 parameter** 组合控制，比预设 expression 更自然灵动
- expression 和 motion 仅在确实需要时使用
- 如果对话文本情感平淡，可以只输出少量参数或空 actions 数组
- 只输出 JSON，不要有任何多余文字"""
        )

        // 示例
        sections.add(buildExampleSection(modelInfo))

        return sections.joinToString("\n\n")
    }

    /**
     * 构建示例部分（根据是否有映射表动态生成）（对齐原项目 _buildExampleSection）
     */
    private fun buildExampleSection(modelInfo: ModelInfo): String {
        val hasMapped = !modelInfo.mappedParameters.isNullOrEmpty() ||
                !modelInfo.mappedExpressions.isNullOrEmpty() ||
                !modelInfo.mappedMotions.isNullOrEmpty()

        // 如果有映射参数，使用前几个别名生成动态示例
        if (hasMapped && !modelInfo.mappedParameters.isNullOrEmpty() && modelInfo.mappedParameters.size >= 2) {
            val params = modelInfo.mappedParameters
            val sampleActions = params.take(4).joinToString(",\n") { p ->
                val range = p.max - p.min
                val sampleValue = ((p.default + range * 0.3f) * 100).roundToInt() / 100f
                val clamped = min(p.max, max(p.min, sampleValue))
                "    { \"type\": \"parameter\", \"parameterId\": \"${p.alias}\", \"value\": $clamped }"
            }

            return """## 示例

对话文本: "嘻嘻，有点困了喵~"
```json
{
  "expression": "困倦微笑",
  "actions": [
$sampleActions
  ]
}
```

对话文本: "好的，我知道了。"
```json
{
  "expression": "平静",
  "actions": []
}
```"""
        }

        // 无映射表时使用静态示例
        return """## 示例

对话文本: "嘻嘻，有点困了喵~"
```json
{
  "expression": "困倦微笑",
  "actions": [
    { "type": "parameter", "parameterId": "ParamAngleZ", "value": 15 },
    { "type": "parameter", "parameterId": "ParamEyeLOpen", "value": 0.3 },
    { "type": "parameter", "parameterId": "ParamEyeROpen", "value": 0.3 },
    { "type": "parameter", "parameterId": "ParamMouthForm", "value": 0.8 }
  ]
}
```

对话文本: "好的，我知道了。"
```json
{
  "expression": "平静",
  "actions": []
}
```"""
    }

    /**
     * 解析 LLM 输出的 JSON 并校验参数合法性（对齐原项目 _parseAndValidate）
     */
    private fun parseAndValidate(rawOutput: String, modelInfo: ModelInfo): List<Live2DCommandData> {
        val context = ctx ?: return emptyList()
        var jsonStr = rawOutput.trim()

        // 如果包含 markdown 代码块，提取其中的 JSON
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val codeBlockMatch = codeBlockRegex.find(jsonStr)
        if (codeBlockMatch != null) {
            jsonStr = codeBlockMatch.groupValues[1].trim()
        }

        // 尝试找到 JSON 对象的起止位置
        val firstBrace = jsonStr.indexOf('{')
        val lastBrace = jsonStr.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            jsonStr = jsonStr.substring(firstBrace, lastBrace + 1)
        }

        val parsed: JsonObject
        try {
            parsed = Json.parseToJsonElement(jsonStr).jsonObject
        } catch (e: Exception) {
            context.logWarn("表情生成器: JSON 解析失败: ${e.message}")
            return emptyList()
        }

        val rawActions = parsed["actions"]?.jsonArray ?: return emptyList()

        // === 构建查找表 ===

        // 参数：真实 ID → 范围
        val paramMap = mutableMapOf<String, ParamRange>()
        for (p in modelInfo.availableParameters) {
            paramMap[p.id] = ParamRange(min = p.min, max = p.max, default = p.default)
        }

        // 参数：别名 → { realId, min, max, default }
        val paramAliasMap = mutableMapOf<String, ParamAliasEntry>()
        if (modelInfo.mappedParameters != null) {
            for (mp in modelInfo.mappedParameters) {
                paramAliasMap[mp.alias] = ParamAliasEntry(id = mp.id, min = mp.min, max = mp.max, default = mp.default)
            }
        }

        // 表情：有效集合 + 别名映射
        val validExpressions = modelInfo.expressions.toSet()
        val expressionAliasMap = mutableMapOf<String, String>()
        if (modelInfo.mappedExpressions != null) {
            for (me in modelInfo.mappedExpressions) {
                expressionAliasMap[me.alias] = me.id
            }
        }

        // 动作：有效组集合 + 别名 → {group, index} 映射
        val validMotionGroups = modelInfo.motions.keys.toSet()
        val motionAliasMap = mutableMapOf<String, MotionAliasEntry>()
        if (modelInfo.mappedMotions != null) {
            for (mm in modelInfo.mappedMotions) {
                motionAliasMap[mm.alias] = MotionAliasEntry(group = mm.group, index = mm.index)
            }
        }

        // === 校验并解析每个动作 ===

        val validatedActions = mutableListOf<Live2DCommandData>()

        for (actionElement in rawActions) {
            val action = actionElement.jsonObject
            val type = action["type"]?.jsonPrimitive?.contentOrNull ?: continue

            when (type) {
                "parameter" -> {
                    val parameterId = action["parameterId"]?.jsonPrimitive?.contentOrNull ?: continue
                    val value = action["value"]?.jsonPrimitive?.floatOrNull ?: continue

                    // 解析参数：先查真实 ID，再查别名
                    var realId = parameterId
                    var paramInfo = paramMap[realId]
                    if (paramInfo == null) {
                        val aliased = paramAliasMap[parameterId]
                        if (aliased != null) {
                            realId = aliased.id
                            paramInfo = ParamRange(min = aliased.min, max = aliased.max, default = aliased.default)
                        }
                    }
                    if (paramInfo == null) {
                        context.logWarn("表情生成器: 未知参数 \"$parameterId\"，已跳过")
                        continue
                    }

                    val clampedValue = max(paramInfo.min, min(paramInfo.max, value))
                    validatedActions.add(Live2DCommandData(
                        command = "parameter",
                        parameterId = realId,  // 始终输出真实 ID
                        value = clampedValue,
                        weight = 1.0f,
                    ))
                }

                "expression" -> {
                    val expressionId = action["expressionId"]?.jsonPrimitive?.contentOrNull ?: continue

                    // 解析表情：先查真实 ID，再查别名
                    var realExpId = expressionId
                    if (realExpId !in validExpressions) {
                        val aliased = expressionAliasMap[expressionId]
                        if (aliased != null) {
                            realExpId = aliased
                        }
                    }
                    if (realExpId !in validExpressions) {
                        context.logWarn("表情生成器: 未知表情 \"$expressionId\"，已跳过")
                        continue
                    }

                    validatedActions.add(Live2DCommandData(
                        command = "expression",
                        expressionId = realExpId,  // 始终输出真实 ID
                    ))
                }

                "motion" -> {
                    val group = action["group"]?.jsonPrimitive?.contentOrNull ?: continue

                    // 解析动作：先查别名（→ 精确的 group+index），再查真实组名
                    var realGroup = group
                    var realIndex = action["index"]?.jsonPrimitive?.intOrNull ?: 0

                    val aliased = motionAliasMap[group]
                    if (aliased != null) {
                        realGroup = aliased.group
                        realIndex = aliased.index
                    }

                    if (realGroup !in validMotionGroups) {
                        context.logWarn("表情生成器: 未知动作 \"$group\"，已跳过")
                        continue
                    }

                    validatedActions.add(Live2DCommandData(
                        command = "motion",
                        group = realGroup,  // 始终输出真实组名
                        index = realIndex,
                        priority = 2,
                    ))
                }
            }
        }

        return validatedActions
    }

    // ==================== 内部数据类 ====================

    private data class ParamRange(val min: Float, val max: Float, val default: Float)
    private data class ParamAliasEntry(val id: String, val min: Float, val max: Float, val default: Float)
    private data class MotionAliasEntry(val group: String, val index: Int)
}