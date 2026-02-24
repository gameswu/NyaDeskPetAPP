package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.getAppVersion
import com.gameswu.nyadeskpet.plugin.*

/**
 * 项目信息插件 — 对齐原项目 agent-plugins/info
 *
 * 注册 `/info` 指令，输出项目基本信息。
 */
class InfoPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "builtin.info",
        name = "项目信息",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "显示项目名称、版本、作者等信息",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.COMMAND),
    )
    override var enabled: Boolean = true

    override fun onLoad(context: PluginContext) {
        context.registerCommand("info", "显示项目信息") {
            buildString {
                appendLine("🐱 NyaDeskPet")
                appendLine("版本: ${getAppVersion()}")
                appendLine("作者: gameswu")
                appendLine("仓库: https://github.com/gameswu/NyaDeskPetAPP")
                appendLine("描述: 基于 Live2D + AI Agent 的跨平台桌宠应用")
            }
        }
    }
}
