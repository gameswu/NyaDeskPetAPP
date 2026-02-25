package com.gameswu.nyadeskpet.agent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// =====================================================================
// MCP 服务器配置与状态 — 仅支持 SSE 网络传输（移动端无 stdio）
// 对齐原项目 MCPServerConfig / MCPServerStatus
// =====================================================================

/** MCP 服务器配置（持久化保存到 AppSettings） */
@Serializable
data class McpServerConfig(
    /** 服务器唯一名称 */
    val name: String,
    /** SSE 传输的 URL（如 http://localhost:3001/sse） */
    val url: String,
    /** 描述 */
    val description: String = "",
    /** 是否自动连接 */
    val autoStart: Boolean = false,
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 自定义请求头（如 Authorization） */
    val headers: Map<String, String> = emptyMap(),
)

/** MCP 服务器运行时状态 */
data class McpServerStatus(
    val name: String,
    val connected: Boolean,
    val toolCount: Int = 0,
    val error: String? = null,
    val lastConnectedAt: Long? = null,
)

// =====================================================================
// MCP JSON-RPC 协议消息 — 对齐 MCP 2024-11-05 规范
// =====================================================================

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/** MCP initialize 请求参数 */
@Serializable
data class McpInitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: McpClientCapabilities = McpClientCapabilities(),
    val clientInfo: McpClientInfo = McpClientInfo(),
)

@Serializable
data class McpClientCapabilities(
    val roots: JsonObject? = null,
    val sampling: JsonObject? = null,
)

@Serializable
data class McpClientInfo(
    val name: String = "NyaDeskPet",
    val version: String = "1.0.0",
)

/** MCP tool 定义（从 tools/list 响应中解析） */
@Serializable
data class McpToolDef(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject? = null,
)

/** MCP tools/list 响应 */
@Serializable
data class McpToolsListResult(
    val tools: List<McpToolDef> = emptyList(),
)

/** MCP tools/call 参数 */
@Serializable
data class McpToolCallParams(
    val name: String,
    val arguments: JsonObject? = null,
)

/** MCP tools/call 响应中的内容块 */
@Serializable
data class McpContentBlock(
    val type: String,
    val text: String? = null,
)

/** MCP tools/call 响应 */
@Serializable
data class McpToolCallResult(
    val content: List<McpContentBlock> = emptyList(),
    val isError: Boolean = false,
)
