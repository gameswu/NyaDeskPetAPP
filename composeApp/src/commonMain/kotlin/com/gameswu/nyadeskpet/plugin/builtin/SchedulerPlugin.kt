package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.currentTimeMillis
import com.gameswu.nyadeskpet.plugin.*
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolProvider
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * 任务调度插件 — 对齐原项目 agent-plugins/scheduler
 *
 * 功能：
 * - `schedule_task` — 创建定时任务（一次性延迟执行）
 * - `list_tasks` — 列出所有调度任务
 * - `cancel_task` — 取消指定任务
 *
 * 简化版：仅支持延迟一次性任务，不支持周期性、持久化（原项目 800 行）。
 */
class SchedulerPlugin : Plugin, ToolProvider {

    override val manifest = PluginManifest(
        id = "builtin.scheduler",
        name = "任务调度",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "创建定时任务，让 AI 在指定时间后执行操作",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.TOOL, PluginCapability.COMMAND),
        autoActivate = true,
    )
    override var enabled: Boolean = true

    // ==================== 配置 Schema ====================

    override val configSchema = PluginConfigSchema(
        fields = listOf(
            ConfigFieldDef(
                key = "maxTasks",
                type = ConfigFieldType.INT,
                description = "允许的最大调度任务数量。",
                default = JsonPrimitive(20),
            ),
            ConfigFieldDef(
                key = "tickInterval",
                type = ConfigFieldType.INT,
                description = "调度器检查间隔（秒）。降低此值可提高定时精度但增加资源消耗。",
                default = JsonPrimitive(30),
            ),
            ConfigFieldDef(
                key = "taskMaxTokens",
                type = ConfigFieldType.INT,
                description = "调度任务执行时 LLM 的最大 token 数。",
                default = JsonPrimitive(1500),
            ),
        ),
    )

    override val providerId: String = "builtin.scheduler"
    override val providerName: String = "任务调度"

    private var ctx: PluginContext? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var maxTasks: Int = 20

    data class ScheduledTask(
        val id: String,
        val name: String,
        val action: String,
        val delayMs: Long,
        val createdAt: Long = currentTimeMillis(),
        var cancelled: Boolean = false,
        var completed: Boolean = false,
        var job: Job? = null,
    )

    private val tasks = mutableMapOf<String, ScheduledTask>()
    private var nextId = 1

    override fun onLoad(context: PluginContext) {
        ctx = context
        // 读取配置
        val config = context.getConfig()
        config["maxTasks"]?.jsonPrimitive?.intOrNull?.let { maxTasks = it }

        context.registerCommand("tasks", "列出当前所有调度任务") {
            val active = tasks.values.filter { !it.cancelled && !it.completed }
            if (active.isEmpty()) {
                "暂无活跃的调度任务"
            } else {
                active.joinToString("\n") { t ->
                    val elapsed = currentTimeMillis() - t.createdAt
                    val remaining = ((t.delayMs - elapsed) / 1000).coerceAtLeast(0)
                    "- [${t.id}] ${t.name}: \"${t.action.take(40)}\" (${remaining}s 后执行)"
                }
            }
        }
    }

    override fun onUnload() {
        scope.cancel()
        tasks.clear()
        ctx = null
    }

    override fun getTools(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "schedule_task",
                description = "创建一个延迟执行的定时任务。delay 格式: '30s', '5m', '2h', '1d'",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("name") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("任务名称"))
                        }
                        putJsonObject("action") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("到期后要执行的描述（将作为消息发送给 AI）"))
                        }
                        putJsonObject("delay") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("延迟时间，如 '30s', '5m', '2h', '1d'"))
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("name"))
                        add(JsonPrimitive("action"))
                        add(JsonPrimitive("delay"))
                    }
                },
            ),
            ToolDefinition(
                name = "list_tasks",
                description = "列出所有活跃的调度任务",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {}
                },
            ),
            ToolDefinition(
                name = "cancel_task",
                description = "取消一个调度任务",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("taskId") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("任务 ID"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("taskId")) }
                },
            ),
        )
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        val context = ctx ?: return ToolResult(success = false, error = "插件未初始化")
        return when (name) {
            "schedule_task" -> {
                val activeTasks = tasks.values.count { !it.cancelled && !it.completed }
                if (activeTasks >= maxTasks) {
                    return ToolResult(success = false, error = "已达到最大任务数限制 ($maxTasks)")
                }
                val taskName = arguments["name"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult(success = false, error = "缺少 name 参数")
                val action = arguments["action"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult(success = false, error = "缺少 action 参数")
                val delayStr = arguments["delay"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult(success = false, error = "缺少 delay 参数")

                val delayMs = parseDelay(delayStr)
                    ?: return ToolResult(success = false, error = "无效的 delay 格式: $delayStr，请使用 '30s', '5m', '2h', '1d'")

                val taskId = "task-${nextId++}"
                val task = ScheduledTask(
                    id = taskId,
                    name = taskName,
                    action = action,
                    delayMs = delayMs,
                )
                task.job = scope.launch {
                    delay(delayMs)
                    if (!task.cancelled) {
                        task.completed = true
                        context.sendSystemMessage("⏰ 定时任务 [${task.name}] 到期: ${task.action}")
                    }
                }
                tasks[taskId] = task

                ToolResult(
                    success = true,
                    result = JsonPrimitive("已创建定时任务 [$taskId] \"$taskName\"，将在 ${delayStr} 后执行"),
                )
            }
            "list_tasks" -> {
                val active = tasks.values.filter { !it.cancelled && !it.completed }
                if (active.isEmpty()) {
                    ToolResult(success = true, result = JsonPrimitive("暂无活跃的调度任务"))
                } else {
                    val text = active.joinToString("\n") { t ->
                        val elapsed = currentTimeMillis() - t.createdAt
                        val remaining = ((t.delayMs - elapsed) / 1000).coerceAtLeast(0)
                        "- [${t.id}] ${t.name}: \"${t.action.take(40)}\" (${remaining}s 后执行)"
                    }
                    ToolResult(success = true, result = JsonPrimitive(text))
                }
            }
            "cancel_task" -> {
                val taskId = arguments["taskId"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult(success = false, error = "缺少 taskId 参数")
                val task = tasks[taskId]
                    ?: return ToolResult(success = false, error = "任务不存在: $taskId")
                task.cancelled = true
                task.job?.cancel()
                ToolResult(success = true, result = JsonPrimitive("已取消任务 [$taskId] \"${task.name}\""))
            }
            else -> ToolResult(success = false, error = "Unknown tool: $name")
        }
    }

    private fun parseDelay(s: String): Long? {
        val trimmed = s.trim().lowercase()
        val regex = Regex("^(\\d+)\\s*(s|sec|m|min|h|hour|d|day)s?\$")
        val match = regex.matchEntire(trimmed) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2]
        return when (unit) {
            "s", "sec" -> value * 1000
            "m", "min" -> value * 60 * 1000
            "h", "hour" -> value * 60 * 60 * 1000
            "d", "day" -> value * 24 * 60 * 60 * 1000
            else -> null
        }
    }
}
