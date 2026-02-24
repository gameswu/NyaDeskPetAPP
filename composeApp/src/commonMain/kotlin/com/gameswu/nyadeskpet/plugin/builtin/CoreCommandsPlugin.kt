package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.plugin.*

/**
 * 核心指令插件 — 对齐原项目中分散在 handler / core-agent 中的基础指令
 *
 * 注册 `/clear`, `/history`, `/model`, `/help` 指令。
 * 通过 PluginContext 的扩展 API 访问对话管理器和 Provider 信息。
 */
class CoreCommandsPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "builtin.core-commands",
        name = "核心指令",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "提供 /clear, /history, /model, /help 等基础斜杠指令",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.COMMAND),
    )
    override var enabled: Boolean = true

    override fun onLoad(context: PluginContext) {
        context.registerCommand("clear", "清除对话历史") {
            context.clearConversationHistory()
            "✅ 对话历史已清除"
        }

        context.registerCommand("history", "查看对话历史") {
            val history = context.getConversationHistory()
            if (history.isEmpty()) {
                "对话历史为空"
            } else {
                history.mapIndexed { i, msg ->
                    "${i + 1}. [${msg.first}] ${msg.second.take(50)}"
                }.joinToString("\n")
            }
        }

        context.registerCommand("model", "查看/切换当前模型") {
            val info = context.getPrimaryProviderInfo()
            if (info != null) {
                buildString {
                    appendLine("主 LLM Provider: ${info.displayName}")
                    appendLine("类型: ${info.providerId}")
                    appendLine("模型: ${info.model ?: "未设置"}")
                    appendLine("状态: ${info.status}")
                }
            } else {
                "未配置 LLM Provider"
            }
        }

        context.registerCommand("help", "显示帮助信息") {
            val allCommands = context.getAllCommandDefinitions()
            if (allCommands.isEmpty()) {
                "暂无可用指令"
            } else {
                allCommands.joinToString("\n") { "/${it.first} — ${it.second}" }
            }
        }
    }
}
