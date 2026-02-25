package com.gameswu.nyadeskpet.agent

import com.gameswu.nyadeskpet.agent.provider.LLMRequest
import com.gameswu.nyadeskpet.agent.provider.LLMResponse
import com.gameswu.nyadeskpet.data.extractZipEntries
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/**
 * Agent Skills 系统 — 对齐原项目 src/agent/skills.ts
 *
 * 核心概念：
 * - SkillSchema：技能的结构化描述（名称、类别、参数、指令、示例）
 * - SkillDefinition：注册到系统中的技能定义（含处理器、来源等元数据）
 * - SkillManager：技能注册表 + 执行器（全局单例）
 *
 * 与 Tool 的区别：
 * - Tool 是原子操作（fetch_url、search_web）
 * - Skill 是高级能力，可组合多个 Tool / Provider 调用，附带详细指令和示例
 * - Skill 可通过 toToolSchemas() 降级为 Tool，供 LLM Function Calling 使用
 */

// ==================== 核心类型 ====================

/** 技能使用示例 */
data class SkillExample(
    val description: String,
    val input: Map<String, Any?>,
    val expectedOutput: String? = null,
)

/** 技能的结构化描述 */
data class SkillSchema(
    /** 唯一标识（snake_case） */
    val name: String,
    /** 技能描述 */
    val description: String,
    /** 分类：system / knowledge / creative / automation / communication */
    val category: String,
    /** 详细指令（供 LLM 理解如何使用此技能） */
    val instructions: String,
    /** 输入参数 schema（OpenAI function calling 格式） */
    val parameters: JsonObject,
    /** 使用示例 */
    val examples: List<SkillExample> = emptyList(),
)

/** 技能执行上下文 */
data class SkillContext(
    /** 调用 LLM Provider */
    val callProvider: suspend (LLMRequest) -> LLMResponse,
    /** 执行已注册的工具 */
    val executeTool: suspend (toolName: String, args: JsonObject) -> ToolResult,
)

/** 技能执行结果 */
data class SkillResult(
    val success: Boolean,
    val output: String,
    val data: JsonElement? = null,
)

/** 技能处理函数 */
typealias SkillHandler = suspend (params: JsonObject, ctx: SkillContext) -> SkillResult

/** 技能定义（注册到系统中） */
data class SkillDefinition(
    val schema: SkillSchema,
    val handler: SkillHandler,
    val source: String,
    var enabled: Boolean = true,
    val registeredAt: Long = com.gameswu.nyadeskpet.currentTimeMillis(),
)

/** 系统级技能信息（只读，用于前端展示） */
data class SkillInfo(
    val name: String,
    val description: String,
    val category: String,
    val instructions: String,
    val source: String,
    val enabled: Boolean,
    val exampleCount: Int,
    val parameterNames: List<String>,
)

// ==================== SkillManager ====================

/**
 * 技能管理器 — 对齐原项目 SkillManager 全局单例
 * 负责技能的注册、注销、调用和查询
 */
class SkillManager {

    /** 技能注册表 */
    private val skills = mutableMapOf<String, SkillDefinition>()

    /** 变更信号（UI 可观察） */
    private val _skillsFlow = MutableStateFlow<List<SkillInfo>>(emptyList())
    val skillsFlow: StateFlow<List<SkillInfo>> = _skillsFlow.asStateFlow()

    // ==================== 注册 / 注销 ====================

    /**
     * 注册技能
     */
    fun register(schema: SkillSchema, handler: SkillHandler, source: String = "builtin") {
        if (skills.containsKey(schema.name)) {
            println("[SkillManager] 技能已存在，将覆盖: ${schema.name}")
        }

        skills[schema.name] = SkillDefinition(
            schema = schema,
            handler = handler,
            source = source,
            enabled = true,
        )

        println("[SkillManager] 注册技能: ${schema.name} (${schema.category}) [$source]")
        notifyChange()
    }

    /**
     * 注销技能
     */
    fun unregister(name: String): Boolean {
        val deleted = skills.remove(name) != null
        if (deleted) {
            println("[SkillManager] 注销技能: $name")
            notifyChange()
        }
        return deleted
    }

    /**
     * 注销指定来源的所有技能
     */
    fun unregisterBySource(source: String): Int {
        val toRemove = skills.entries.filter { it.value.source == source }.map { it.key }
        toRemove.forEach { skills.remove(it) }
        if (toRemove.isNotEmpty()) {
            println("[SkillManager] 注销来源 $source 的 ${toRemove.size} 个技能")
            notifyChange()
        }
        return toRemove.size
    }

    // ==================== 调用 ====================

    /**
     * 调用技能
     */
    suspend fun invoke(name: String, params: JsonObject, ctx: SkillContext): SkillResult {
        val def = skills[name]
            ?: return SkillResult(success = false, output = "技能不存在: $name")
        if (!def.enabled) {
            return SkillResult(success = false, output = "技能已禁用: $name")
        }

        println("[SkillManager] 调用技能: $name")
        val startTime = com.gameswu.nyadeskpet.currentTimeMillis()

        return try {
            val result = def.handler(params, ctx)
            val elapsed = com.gameswu.nyadeskpet.currentTimeMillis() - startTime
            println("[SkillManager] 技能 $name 执行完成 (${elapsed}ms) success=${result.success}")
            result
        } catch (e: Exception) {
            val elapsed = com.gameswu.nyadeskpet.currentTimeMillis() - startTime
            println("[SkillManager] 技能 $name 执行失败 (${elapsed}ms): ${e.message}")
            SkillResult(success = false, output = "技能执行异常: ${e.message}")
        }
    }

    // ==================== 查询 ====================

    fun list(): List<SkillInfo> {
        return skills.values.map { def ->
            SkillInfo(
                name = def.schema.name,
                description = def.schema.description,
                category = def.schema.category,
                instructions = def.schema.instructions,
                source = def.source,
                enabled = def.enabled,
                exampleCount = def.schema.examples.size,
                parameterNames = def.schema.parameters["properties"]?.jsonObject?.keys?.toList() ?: emptyList(),
            )
        }
    }

    fun getSchema(name: String): SkillSchema? = skills[name]?.schema

    fun has(name: String): Boolean = skills.containsKey(name)

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val def = skills[name] ?: return false
        def.enabled = enabled
        println("[SkillManager] 技能 $name ${if (enabled) "已启用" else "已禁用"}")
        notifyChange()
        return true
    }

    fun getEnabledCount(): Int = skills.values.count { it.enabled }

    // ==================== Tool 兼容层 ====================

    /**
     * 将所有已启用的技能转换为 ToolDefinition 数组
     * 用于注入到 LLM Function Calling 工具列表中
     */
    fun toToolSchemas(): List<ToolDefinition> {
        return skills.values
            .filter { it.enabled }
            .map { def ->
                ToolDefinition(
                    name = "skill_${def.schema.name}",
                    description = "[Skill] ${def.schema.description}\n\n${def.schema.instructions}",
                    parameters = def.schema.parameters,
                )
            }
    }

    /**
     * 判断工具名是否是技能调用
     */
    fun isSkillToolCall(toolName: String): Boolean {
        if (!toolName.startsWith("skill_")) return false
        val skillName = toolName.substring(6) // "skill_".length
        return skills.containsKey(skillName)
    }

    /**
     * 从工具调用中提取并执行技能
     */
    suspend fun handleToolCall(toolName: String, args: JsonObject, ctx: SkillContext): ToolResult {
        val skillName = toolName.substring(6)
        val result = invoke(skillName, args, ctx)
        return ToolResult(
            success = result.success,
            result = JsonPrimitive(result.output),
            error = if (!result.success) result.output else null,
        )
    }

    // ==================== Zip 导入 ====================

    /**
     * 从 zip 压缩包导入技能。
     *
     * Zip 包中应包含 `skill.json`，格式示例：
     * ```json
     * {
     *   "name": "my_skill",
     *   "description": "技能描述",
     *   "category": "automation",
     *   "instructions": "详细指令...",
     *   "parameters": {
     *     "type": "object",
     *     "properties": { "input": { "type": "string", "description": "输入" } }
     *   },
     *   "examples": [
     *     { "description": "示例1", "input": { "input": "hello" }, "expectedOutput": "world" }
     *   ]
     * }
     * ```
     *
     * 导入后以 LLM 请求方式执行，将 instructions + parameters 发送给 LLM。
     *
     * @param zipBytes  zip 文件的字节内容
     * @return 成功返回 SkillInfo，失败返回 null 和错误信息
     */
    fun importFromZip(zipBytes: ByteArray): Pair<SkillInfo?, String?> {
        return try {
            val entries = extractZipEntries(zipBytes)

            // 查找 skill.json（支持在根目录或子目录中）
            val skillJsonKey = entries.keys.find {
                it.endsWith("skill.json", ignoreCase = true)
            } ?: return null to "压缩包中未找到 skill.json 文件"

            val jsonStr = entries[skillJsonKey]?.decodeToString()
                ?: return null to "无法读取 skill.json"

            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(jsonStr).jsonObject

            val name = root["name"]?.jsonPrimitive?.content
                ?: return null to "skill.json 中缺少 name 字段"
            val description = root["description"]?.jsonPrimitive?.content ?: ""
            val category = root["category"]?.jsonPrimitive?.content ?: "custom"
            val instructions = root["instructions"]?.jsonPrimitive?.content ?: description
            val parameters = root["parameters"]?.jsonObject ?: buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {})
            }

            // 解析 examples
            val examples = root["examples"]?.jsonArray?.mapNotNull { exEl ->
                try {
                    val ex = exEl.jsonObject
                    SkillExample(
                        description = ex["description"]?.jsonPrimitive?.content ?: "",
                        input = ex["input"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                        expectedOutput = ex["expectedOutput"]?.jsonPrimitive?.contentOrNull,
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()

            val schema = SkillSchema(
                name = name,
                description = description,
                category = category,
                instructions = instructions,
                parameters = parameters,
                examples = examples,
            )

            // 注册技能 — handler 采用 LLM 委托模式：将 instructions + 参数发给 LLM
            register(schema, handler = { params, ctx ->
                val prompt = buildString {
                    appendLine("你正在执行技能: $name")
                    appendLine()
                    appendLine("【技能说明】")
                    appendLine(instructions)
                    appendLine()
                    appendLine("【输入参数】")
                    for ((key, value) in params) {
                        appendLine("- $key: $value")
                    }
                    if (examples.isNotEmpty()) {
                        appendLine()
                        appendLine("【参考示例】")
                        examples.forEachIndexed { idx, ex ->
                            appendLine("示例${idx + 1}: ${ex.description}")
                            if (ex.expectedOutput != null) {
                                appendLine("  期望输出: ${ex.expectedOutput}")
                            }
                        }
                    }
                }

                try {
                    val response = ctx.callProvider(
                        LLMRequest(
                            messages = listOf(
                                com.gameswu.nyadeskpet.agent.provider.ChatMessage(
                                    role = "user",
                                    content = prompt,
                                )
                            )
                        )
                    )
                    SkillResult(
                        success = true,
                        output = response.text.ifEmpty { "技能执行完成" },
                    )
                } catch (e: Exception) {
                    SkillResult(
                        success = false,
                        output = "技能执行失败: ${e.message}",
                    )
                }
            }, source = "imported")

            val info = list().find { it.name == name }
            info to null
        } catch (e: Exception) {
            null to "导入失败: ${e.message}"
        }
    }

    // ==================== 内部 ====================

    private fun notifyChange() {
        _skillsFlow.value = list()
    }
}
