package com.gameswu.nyadeskpet.plugin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// =====================================================================
// Tool Provider — 工具提供者接口
// =====================================================================

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
    val requireConfirm: Boolean = false,
)

interface ToolProvider {
    val providerId: String
    val providerName: String
    fun getTools(): List<ToolDefinition>
    suspend fun executeTool(name: String, arguments: JsonObject): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
)

// =====================================================================
// Frontend Plugin — 前端插件接口
// =====================================================================

interface PanelPlugin {
    val panelId: String
    val panelTitle: String
    fun getPanelDescription(): String
}