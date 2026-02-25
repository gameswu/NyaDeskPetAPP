package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.agent.provider.ChatMessage
import com.gameswu.nyadeskpet.agent.provider.LLMRequest
import com.gameswu.nyadeskpet.plugin.*
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolProvider
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import kotlinx.serialization.json.*

/**
 * Planning 插件 — 对齐原项目 agent-plugins/planning/main.js
 *
 * 核心能力：
 * 1. 任务规划 — 将复杂目标分解为多步骤计划
 * 2. 计划执行 — 按依赖顺序逐步执行，汇总结果
 * 3. Sub-Agent — 为特定步骤创建独立上下文代理，支持工具调用
 *
 * 注册工具：
 * - create_plan: 根据目标创建任务计划
 * - execute_plan: 执行/恢复指定计划
 * - view_plan: 查看计划状态
 * - cancel_plan: 取消计划
 * - create_sub_agent: 创建 Sub-Agent 执行特定任务
 * - list_sub_agents: 列出所有 Sub-Agent
 */
class PlanningPlugin : Plugin, ToolProvider {

    override val manifest = PluginManifest(
        id = "builtin.planning",
        name = "Planning",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "任务规划插件 — 将复杂目标分解为多步骤计划并执行",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.TOOL),
        autoActivate = true,
    )

    override var enabled: Boolean = true

    override val configSchema = PluginConfigSchema(
        fields = listOf(
            ConfigFieldDef(key = "planMaxTokens", type = ConfigFieldType.INT, description = "计划生成最大 token 数", default = JsonPrimitive(2000)),
            ConfigFieldDef(key = "subAgentMaxTokens", type = ConfigFieldType.INT, description = "Sub-Agent 最大 token 数", default = JsonPrimitive(1500)),
            ConfigFieldDef(key = "maxPlanSteps", type = ConfigFieldType.INT, description = "计划最大步骤数", default = JsonPrimitive(10)),
            ConfigFieldDef(key = "maxSubAgents", type = ConfigFieldType.INT, description = "Sub-Agent 最大数量", default = JsonPrimitive(5)),
            ConfigFieldDef(key = "autoExecute", type = ConfigFieldType.BOOL, description = "创建计划后自动执行", default = JsonPrimitive(true)),
        )
    )

    override val providerId = "builtin.planning"
    override val providerName = "Planning"

    private var context: PluginContext? = null

    // ==================== 配置 ====================
    private var planMaxTokens = 2000
    private var subAgentMaxTokens = 1500
    private var maxPlanSteps = 10
    private var maxSubAgents = 5
    private var autoExecute = true

    // ==================== 状态 ====================
    private val plans = mutableMapOf<String, Plan>()
    private val subAgents = mutableMapOf<String, SubAgent>()
    private var planIdCounter = 0
    private var subAgentIdCounter = 0

    // ==================== 数据类型 ====================
    private data class PlanStep(
        val id: Int,
        val description: String,
        val dependencies: List<Int> = emptyList(),
        val needsSubAgent: Boolean = false,
        val subAgentTask: String = "",
        var status: String = "pending",
        var result: String? = null,
        var subAgentId: String? = null,
    )

    private data class Plan(
        val id: String,
        val title: String,
        val goal: String,
        val context: String = "",
        var status: String = "pending",
        val steps: MutableList<PlanStep>,
        val createdAt: Long = com.gameswu.nyadeskpet.currentTimeMillis(),
        var completedAt: Long? = null,
    )

    private data class SubAgent(
        val id: String,
        val name: String,
        val task: String,
        val systemPrompt: String,
        var status: String = "idle",
        val messages: MutableList<ChatMessage> = mutableListOf(),
        var result: String? = null,
        val createdAt: Long = com.gameswu.nyadeskpet.currentTimeMillis(),
        var completedAt: Long? = null,
    )

    // ==================== 常量 ====================
    companion object {
        private const val PLAN_SYSTEM_PROMPT = """你是一个任务规划助手。根据用户的目标，创建一个结构化的执行计划。

输出要求（严格 JSON 格式）：
{
  "title": "计划标题（简短描述）",
  "steps": [
    {
      "id": 1,
      "description": "步骤描述（具体、可执行的行动）",
      "dependencies": [],
      "needsSubAgent": false,
      "subAgentTask": ""
    }
  ]
}

规则：
1. 每个步骤必须是具体、可执行的行动，而非抽象描述
2. dependencies 填入依赖的步骤 id 数组（如 [1, 2] 表示依赖步骤 1 和 2）
3. 无依赖的步骤 dependencies 为空数组 []
4. 若步骤需要独立上下文处理（如复杂分析、长文本生成），将 needsSubAgent 设为 true 并填写 subAgentTask
5. 步骤数量不应超过限制
6. 只输出 JSON，不要包含任何其他文字"""

        private const val STEP_EXECUTION_PROMPT_TEMPLATE = """你正在执行一个任务计划的某个步骤。

计划目标：{goal}
当前步骤：{step}
前置步骤结果：
{previousResults}

请执行当前步骤并给出结果。如果需要使用工具，请调用适当的工具。完成后请给出步骤执行结果的总结。"""

        private const val SUB_AGENT_DEFAULT_PROMPT_TEMPLATE = """你是一个专注于特定任务的 Sub-Agent。你的任务是：

{task}

请专注于完成这个任务，给出完整、准确的结果。你可以使用可用的工具来辅助完成任务。"""
    }

    // ==================== 生命周期 ====================

    override fun onLoad(context: PluginContext) {
        this.context = context
        loadConfig(context.getConfig())
        context.logInfo("Planning 插件已初始化")
    }

    override fun onUnload() {
        plans.clear()
        subAgents.clear()
        context?.logInfo("Planning 插件已停止")
        context = null
    }

    override fun onConfigChanged(config: Map<String, JsonElement>) {
        loadConfig(config)
    }

    private fun loadConfig(config: Map<String, JsonElement>) {
        config["planMaxTokens"]?.jsonPrimitive?.intOrNull?.let { planMaxTokens = it }
        config["subAgentMaxTokens"]?.jsonPrimitive?.intOrNull?.let { subAgentMaxTokens = it }
        config["maxPlanSteps"]?.jsonPrimitive?.intOrNull?.let { maxPlanSteps = it }
        config["maxSubAgents"]?.jsonPrimitive?.intOrNull?.let { maxSubAgents = it }
        config["autoExecute"]?.jsonPrimitive?.booleanOrNull?.let { autoExecute = it }
    }

    // ==================== ToolProvider ====================

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "create_plan",
            description = "根据目标描述创建一个多步骤任务计划。LLM 会将复杂目标分解为可执行的步骤序列。",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {
                    putJsonObject("goal") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("任务目标描述")) }
                    putJsonObject("context") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("附加上下文信息（可选）")) }
                }
                putJsonArray("required") { add(JsonPrimitive("goal")) }
            },
        ),
        ToolDefinition(
            name = "execute_plan",
            description = "执行或恢复指定的任务计划。按步骤依赖顺序逐步执行。",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {
                    putJsonObject("planId") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("计划 ID")) }
                }
                putJsonArray("required") { add(JsonPrimitive("planId")) }
            },
        ),
        ToolDefinition(
            name = "view_plan",
            description = "查看指定计划的当前状态。不指定 planId 则列出所有计划。",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {
                    putJsonObject("planId") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("计划 ID（可选）")) }
                }
                putJsonArray("required") {}
            },
        ),
        ToolDefinition(
            name = "cancel_plan",
            description = "取消指定的任务计划，停止所有未完成的步骤。",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {
                    putJsonObject("planId") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("计划 ID")) }
                }
                putJsonArray("required") { add(JsonPrimitive("planId")) }
            },
        ),
        ToolDefinition(
            name = "create_sub_agent",
            description = "创建一个 Sub-Agent 来执行特定任务。Sub-Agent 拥有独立的对话上下文，可以使用工具。",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("Sub-Agent 名称")) }
                    putJsonObject("task") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("分配给 Sub-Agent 的任务描述")) }
                    putJsonObject("systemPrompt") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("自定义系统提示词（可选）")) }
                }
                putJsonArray("required") { add(JsonPrimitive("name")); add(JsonPrimitive("task")) }
            },
        ),
        ToolDefinition(
            name = "list_sub_agents",
            description = "列出所有已创建的 Sub-Agent 及其状态。",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {}
                putJsonArray("required") {}
            },
        ),
    )

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        return when (name) {
            "create_plan" -> handleCreatePlan(arguments)
            "execute_plan" -> handleExecutePlan(arguments)
            "view_plan" -> handleViewPlan(arguments)
            "cancel_plan" -> handleCancelPlan(arguments)
            "create_sub_agent" -> handleCreateSubAgent(arguments)
            "list_sub_agents" -> handleListSubAgents()
            else -> ToolResult(success = false, error = "未知工具: $name")
        }
    }

    // ==================== 服务 API ====================

    private suspend fun createPlan(goal: String, extraContext: String? = null): Result<Plan> {
        val ctx = context ?: return Result.failure(Exception("插件未初始化"))

        return try {
            val planPrompt = if (!extraContext.isNullOrBlank()) {
                "目标: $goal\n\n附加上下文: $extraContext\n\n步骤数量上限: $maxPlanSteps"
            } else {
                "目标: $goal\n\n步骤数量上限: $maxPlanSteps"
            }

            val response = ctx.callProvider("primary", LLMRequest(
                messages = listOf(ChatMessage(role = "user", content = planPrompt)),
                systemPrompt = PLAN_SYSTEM_PROMPT,
                maxTokens = planMaxTokens,
            ))

            val parsed = parsePlanResponse(response.text)
                ?: return Result.failure(Exception("LLM 返回的计划格式无效"))

            val planId = "plan_${com.gameswu.nyadeskpet.currentTimeMillis()}_${++planIdCounter}"
            val plan = Plan(
                id = planId,
                title = parsed.first.ifBlank { goal },
                goal = goal,
                context = extraContext ?: "",
                steps = parsed.second.take(maxPlanSteps).mapIndexed { i, step ->
                    PlanStep(
                        id = step["id"]?.jsonPrimitive?.intOrNull ?: (i + 1),
                        description = step["description"]?.jsonPrimitive?.contentOrNull ?: "步骤 ${i + 1}",
                        dependencies = step["dependencies"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList(),
                        needsSubAgent = step["needsSubAgent"]?.jsonPrimitive?.booleanOrNull ?: false,
                        subAgentTask = step["subAgentTask"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }.toMutableList(),
            )

            plans[planId] = plan
            ctx.logInfo("已创建计划 $planId: ${plan.title} (${plan.steps.size} 步)")
            Result.success(plan)
        } catch (e: Exception) {
            ctx.logError("创建计划失败: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun executePlan(planId: String): Result<List<Pair<Int, String>>> {
        val ctx = context ?: return Result.failure(Exception("插件未初始化"))
        val plan = plans[planId] ?: return Result.failure(Exception("计划不存在: $planId"))

        if (plan.status == "completed") {
            return Result.success(plan.steps.map { it.id to (it.result ?: "(无结果)") })
        }
        if (plan.status == "cancelled") {
            return Result.failure(Exception("计划已被取消"))
        }

        plan.status = "running"
        val results = mutableListOf<Pair<Int, String>>()

        try {
            val executionOrder = topologicalSort(plan.steps)

            for (stepId in executionOrder) {
                val step = plan.steps.find { it.id == stepId } ?: continue
                if (step.status == "completed") continue

                val depsCompleted = step.dependencies.all { depId ->
                    plan.steps.find { it.id == depId }?.status == "completed"
                }

                if (!depsCompleted) {
                    step.status = "skipped"
                    step.result = "依赖步骤未完成，已跳过"
                    results.add(step.id to step.result!!)
                    continue
                }

                step.status = "running"
                ctx.logInfo("执行计划 $planId 步骤 ${step.id}: ${step.description}")

                try {
                    val result = if (step.needsSubAgent && step.subAgentTask.isNotBlank()) {
                        executeStepWithSubAgent(plan, step)
                    } else {
                        executeStep(plan, step)
                    }
                    step.status = "completed"
                    step.result = result
                    results.add(step.id to result)
                } catch (e: Exception) {
                    step.status = "failed"
                    step.result = "执行失败: ${e.message}"
                    results.add(step.id to step.result!!)
                    ctx.logError("步骤 ${step.id} 执行失败: ${e.message}")
                }
            }

            val allCompleted = plan.steps.all { it.status == "completed" }
            val anyFailed = plan.steps.any { it.status == "failed" }

            if (allCompleted) {
                plan.status = "completed"
                plan.completedAt = com.gameswu.nyadeskpet.currentTimeMillis()
            } else if (anyFailed) {
                plan.status = "failed"
            }

            ctx.logInfo("计划 $planId 执行完毕: ${plan.status}")
            return Result.success(results)
        } catch (e: Exception) {
            plan.status = "failed"
            ctx.logError("计划 $planId 执行出错: ${e.message}")
            return Result.failure(e)
        }
    }

    fun createSubAgent(name: String, task: String, systemPrompt: String? = null): Result<String> {
        if (subAgents.size >= maxSubAgents) {
            return Result.failure(Exception("Sub-Agent 数量已达上限 ($maxSubAgents)"))
        }

        val agentId = "agent_${com.gameswu.nyadeskpet.currentTimeMillis()}_${++subAgentIdCounter}"
        val agent = SubAgent(
            id = agentId,
            name = name,
            task = task,
            systemPrompt = systemPrompt ?: SUB_AGENT_DEFAULT_PROMPT_TEMPLATE.replace("{task}", task),
        )

        subAgents[agentId] = agent
        context?.logInfo("已创建 Sub-Agent $agentId: $name")
        return Result.success(agentId)
    }

    suspend fun runSubAgent(agentId: String, input: String): Result<String> {
        val ctx = context ?: return Result.failure(Exception("插件未初始化"))
        val agent = subAgents[agentId] ?: return Result.failure(Exception("Sub-Agent 不存在: $agentId"))

        agent.status = "running"
        agent.messages.add(ChatMessage(role = "user", content = input))

        return try {
            val response = ctx.callProvider("primary", LLMRequest(
                messages = agent.messages.toList(),
                systemPrompt = agent.systemPrompt,
                maxTokens = subAgentMaxTokens,
            ))

            agent.messages.add(ChatMessage(role = "assistant", content = response.text))
            agent.status = "completed"
            agent.result = response.text
            agent.completedAt = com.gameswu.nyadeskpet.currentTimeMillis()

            ctx.logInfo("Sub-Agent $agentId 执行完成")
            Result.success(response.text)
        } catch (e: Exception) {
            agent.status = "failed"
            agent.result = "执行失败: ${e.message}"
            ctx.logError("Sub-Agent $agentId 执行失败: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== 工具处理器 ====================

    private suspend fun handleCreatePlan(args: JsonObject): ToolResult {
        val goal = args["goal"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(success = false, result = JsonPrimitive("错误: 缺少 goal 参数"))

        val extraContext = args["context"]?.jsonPrimitive?.contentOrNull

        val planResult = createPlan(goal, extraContext)
        if (planResult.isFailure) {
            return ToolResult(success = false, result = JsonPrimitive("创建计划失败: ${planResult.exceptionOrNull()?.message}"))
        }

        val plan = planResult.getOrThrow()
        val stepsText = plan.steps.joinToString("\n") { step ->
            "  ${step.id}. ${step.description}${if (step.dependencies.isNotEmpty()) " (依赖: ${step.dependencies.joinToString(", ")})" else ""}${if (step.needsSubAgent) " [Sub-Agent]" else ""}"
        }

        var content = "已创建计划: ${plan.title}\nID: ${plan.id}\n步骤 (${plan.steps.size}):\n$stepsText"

        if (autoExecute) {
            content += "\n\n正在自动执行计划..."
            val execResult = executePlan(plan.id)
            if (execResult.isSuccess) {
                val resultsText = execResult.getOrThrow().joinToString("\n") { (stepId, result) ->
                    val step = plan.steps.find { it.id == stepId }
                    "  步骤 $stepId: [${step?.status}] $result"
                }
                content += "\n\n执行完成:\n$resultsText"
            } else {
                content += "\n\n执行出错: ${execResult.exceptionOrNull()?.message}"
            }
        }

        return ToolResult(success = true, result = JsonPrimitive(content))
    }

    private suspend fun handleExecutePlan(args: JsonObject): ToolResult {
        val planId = args["planId"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(success = false, result = JsonPrimitive("错误: 缺少 planId 参数"))

        val result = executePlan(planId)
        if (result.isFailure) {
            return ToolResult(success = false, result = JsonPrimitive("执行计划失败: ${result.exceptionOrNull()?.message}"))
        }

        val plan = plans[planId]!!
        val resultsText = result.getOrThrow().joinToString("\n") { (stepId, text) ->
            val step = plan.steps.find { it.id == stepId }
            "  步骤 $stepId: [${step?.status}] $text"
        }
        return ToolResult(success = true, result = JsonPrimitive("计划执行完成:\n$resultsText"))
    }

    private fun handleViewPlan(args: JsonObject): ToolResult {
        val planId = args["planId"]?.jsonPrimitive?.contentOrNull

        if (planId == null) {
            if (plans.isEmpty()) {
                return ToolResult(success = true, result = JsonPrimitive("当前没有任何计划"))
            }
            val text = plans.values.joinToString("\n") { plan ->
                val completedSteps = plan.steps.count { it.status == "completed" }
                "- ${plan.id}: ${plan.title} [${plan.status}] ($completedSteps/${plan.steps.size} 步完成)"
            }
            return ToolResult(success = true, result = JsonPrimitive("所有计划 (${plans.size}):\n$text"))
        }

        val plan = plans[planId]
            ?: return ToolResult(success = false, result = JsonPrimitive("计划不存在: $planId"))

        val stepsText = plan.steps.joinToString("\n") { step ->
            buildString {
                append("  ${step.id}. [${step.status}] ${step.description}")
                step.result?.let { append("\n     结果: $it") }
                step.subAgentId?.let { append("\n     Sub-Agent: $it") }
            }
        }

        val elapsed = if (plan.completedAt != null) {
            "${((plan.completedAt!! - plan.createdAt) / 1000.0)}s"
        } else {
            "${((com.gameswu.nyadeskpet.currentTimeMillis() - plan.createdAt) / 1000.0)}s (进行中)"
        }

        return ToolResult(
            success = true,
            result = JsonPrimitive("计划: ${plan.title}\nID: ${plan.id}\n目标: ${plan.goal}\n状态: ${plan.status}\n耗时: $elapsed\n\n步骤:\n$stepsText"),
        )
    }

    private fun handleCancelPlan(args: JsonObject): ToolResult {
        val planId = args["planId"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(success = false, result = JsonPrimitive("错误: 缺少 planId 参数"))

        val plan = plans[planId]
            ?: return ToolResult(success = false, result = JsonPrimitive("计划不存在: $planId"))

        if (plan.status == "completed" || plan.status == "cancelled") {
            return ToolResult(success = false, result = JsonPrimitive("计划已是终态: ${plan.status}"))
        }

        plan.status = "cancelled"
        for (step in plan.steps) {
            if (step.status == "pending" || step.status == "running") {
                step.status = "skipped"
                step.result = "计划已取消"
            }
        }

        context?.logInfo("计划 $planId 已取消")
        return ToolResult(success = true, result = JsonPrimitive("计划 $planId 已取消"))
    }

    private suspend fun handleCreateSubAgent(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.contentOrNull
        val task = args["task"]?.jsonPrimitive?.contentOrNull
        if (name.isNullOrBlank() || task.isNullOrBlank()) {
            return ToolResult(success = false, result = JsonPrimitive("错误: 缺少 name 或 task 参数"))
        }

        val systemPrompt = args["systemPrompt"]?.jsonPrimitive?.contentOrNull
        val createResult = createSubAgent(name, task, systemPrompt)
        if (createResult.isFailure) {
            return ToolResult(success = false, result = JsonPrimitive("创建 Sub-Agent 失败: ${createResult.exceptionOrNull()?.message}"))
        }

        val agentId = createResult.getOrThrow()
        val runResult = runSubAgent(agentId, task)
        if (runResult.isFailure) {
            return ToolResult(success = false, result = JsonPrimitive("Sub-Agent $agentId 创建成功但执行失败: ${runResult.exceptionOrNull()?.message}"))
        }

        return ToolResult(
            success = true,
            result = JsonPrimitive("Sub-Agent \"$name\" ($agentId) 已创建并执行完成:\n\n${runResult.getOrThrow()}"),
        )
    }

    private fun handleListSubAgents(): ToolResult {
        if (subAgents.isEmpty()) {
            return ToolResult(success = true, result = JsonPrimitive("当前没有任何 Sub-Agent"))
        }

        val text = subAgents.values.joinToString("\n") { agent ->
            "- ${agent.id}: ${agent.name} [${agent.status}] — ${agent.task.take(80)}${if (agent.task.length > 80) "..." else ""}"
        }
        return ToolResult(
            success = true,
            result = JsonPrimitive("Sub-Agent 列表 (${subAgents.size}/$maxSubAgents):\n$text"),
        )
    }

    // ==================== 内部方法 ====================

    private fun parsePlanResponse(text: String): Pair<String, List<JsonObject>>? {
        // 尝试直接解析
        fun tryParse(json: String): Pair<String, List<JsonObject>>? {
            return try {
                val element = Json.parseToJsonElement(json).jsonObject
                val steps = element["steps"]?.jsonArray ?: return null
                val title = element["title"]?.jsonPrimitive?.contentOrNull ?: ""
                title to steps.map { it.jsonObject }
            } catch (_: Exception) {
                null
            }
        }

        tryParse(text)?.let { return it }

        // 尝试从 Markdown 代码块中提取
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        codeBlockRegex.find(text)?.groupValues?.get(1)?.trim()?.let { tryParse(it)?.let { p -> return p } }

        // 尝试提取 JSON 对象
        val jsonRegex = Regex("\\{[\\s\\S]*\"steps\"\\s*:\\s*\\[[\\s\\S]*][\\s\\S]*}")
        jsonRegex.find(text)?.value?.let { tryParse(it)?.let { p -> return p } }

        context?.logWarn("无法解析计划 JSON: ${text.take(200)}")
        return null
    }

    private suspend fun executeStep(plan: Plan, step: PlanStep): String {
        val ctx = context ?: throw Exception("插件未初始化")

        val previousResults = step.dependencies.mapNotNull { depId ->
            plan.steps.find { it.id == depId }?.let { dep ->
                "步骤 ${dep.id} (${dep.description}): ${dep.result ?: "(无结果)"}"
            }
        }.joinToString("\n").ifBlank { "(无前置步骤)" }

        val prompt = STEP_EXECUTION_PROMPT_TEMPLATE
            .replace("{goal}", plan.goal)
            .replace("{step}", step.description)
            .replace("{previousResults}", previousResults)

        val response = ctx.callProvider("primary", LLMRequest(
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            maxTokens = subAgentMaxTokens,
        ))

        return response.text
    }

    private suspend fun executeStepWithSubAgent(plan: Plan, step: PlanStep): String {
        val agentResult = createSubAgent(
            name = "plan-${plan.id}-step-${step.id}",
            task = step.subAgentTask.ifBlank { step.description },
        )

        if (agentResult.isFailure) {
            context?.logWarn("创建 Sub-Agent 失败，回退到直接执行: ${agentResult.exceptionOrNull()?.message}")
            return executeStep(plan, step)
        }

        val agentId = agentResult.getOrThrow()
        step.subAgentId = agentId

        val previousResults = step.dependencies.mapNotNull { depId ->
            plan.steps.find { it.id == depId }?.let { dep ->
                "步骤 ${dep.id} 结果: ${dep.result ?: "(无结果)"}"
            }
        }.joinToString("\n")

        val input = if (previousResults.isNotBlank()) {
            "请执行以下任务。前置步骤结果供参考:\n$previousResults\n\n任务: ${step.description}"
        } else {
            "请执行以下任务: ${step.description}"
        }

        val runResult = runSubAgent(agentId, input)
        return if (runResult.isSuccess) runResult.getOrThrow() else "Sub-Agent 执行失败: ${runResult.exceptionOrNull()?.message}"
    }

    private fun topologicalSort(steps: List<PlanStep>): List<Int> {
        val visited = mutableSetOf<Int>()
        val result = mutableListOf<Int>()
        val stepMap = steps.associateBy { it.id }

        fun visit(id: Int) {
            if (id in visited) return
            visited.add(id)
            stepMap[id]?.dependencies?.forEach { visit(it) }
            result.add(id)
        }

        steps.forEach { visit(it.id) }
        return result
    }
}