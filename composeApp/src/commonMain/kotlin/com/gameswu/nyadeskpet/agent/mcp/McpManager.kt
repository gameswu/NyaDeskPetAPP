package com.gameswu.nyadeskpet.agent.mcp

import com.gameswu.nyadeskpet.plugin.PluginManager
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolProvider
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import com.gameswu.nyadeskpet.util.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/**
 * MCP 管理器 — 管理多个 MCP 服务器连接
 *
 * 对齐原项目 MCPManager（mcp-client.ts）:
 * - 管理 MCP 服务器配置（增删改）
 * - 管理连接生命周期（连接/断开/自动启动）
 * - 通过 [McpToolProvider] 将 MCP 工具注册到 [PluginManager]
 * - 暴露 StateFlow 供 UI 响应式更新
 *
 * 数据链路：
 * McpManager → McpClient.tools → McpToolProvider(ToolProvider) → PluginManager._toolProviders
 * → getAllTools() → getToolSchemas() → LLMRequest.tools → LLM
 * → executeToolCalls() → pluginManager.executeTool() → McpToolProvider.executeTool()
 * → McpClient.callTool() → MCP server
 */
class McpManager(
    private val pluginManager: PluginManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 活跃的 MCP 连接
    private val connections = mutableMapOf<String, McpClient>()

    // 每个 MCP 服务器对应的 ToolProvider（注册到 PluginManager）
    private val toolProviders = mutableMapOf<String, McpToolProvider>()

    // ===== 响应式状态（供 UI 观察）=====

    private val _serverConfigs = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val serverConfigs: StateFlow<List<McpServerConfig>> = _serverConfigs.asStateFlow()

    private val _serverStatuses = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val serverStatuses: StateFlow<Map<String, McpServerStatus>> = _serverStatuses.asStateFlow()

    // 加载配置回调（由外部设置，从 AppSettings 读写）
    var onConfigsChanged: ((List<McpServerConfig>) -> Unit)? = null

    /**
     * 初始化：加载配置并自动连接
     */
    fun initialize(configs: List<McpServerConfig>) {
        _serverConfigs.value = configs
        updateStatuses()

        // 自动连接标记了 autoStart 的服务器
        scope.launch {
            for (config in configs) {
                if (config.autoStart && config.enabled) {
                    try {
                        connectServer(config.name)
                    } catch (e: Exception) {
                        DebugLog.w("McpManager") { "自动连接失败: ${config.name} - ${e.message}" }
                    }
                }
            }
        }
    }

    /**
     * 关闭所有连接
     */
    fun terminate() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        toolProviders.forEach { (_, tp) -> unregisterToolProvider(tp) }
        toolProviders.clear()
        scope.cancel()
    }

    // ==================== 服务器管理 ====================

    /**
     * 连接到指定服务器
     */
    suspend fun connectServer(name: String) {
        val config = _serverConfigs.value.find { it.name == name }
            ?: throw Exception("未找到 MCP 服务器配置: $name")

        // 断开旧连接
        disconnectServer(name)

        val client = McpClient(config)
        client.connect()
        connections[name] = client

        // 创建 ToolProvider 并注册到 PluginManager
        val provider = McpToolProvider(name, client)
        toolProviders[name] = provider
        registerToolProvider(provider)

        updateStatuses()
    }

    /**
     * 断开指定服务器
     */
    fun disconnectServer(name: String) {
        connections[name]?.disconnect()
        connections.remove(name)

        toolProviders[name]?.let { unregisterToolProvider(it) }
        toolProviders.remove(name)

        updateStatuses()
    }

    /**
     * 添加服务器配置（如已存在同名则更新）
     */
    fun addServerConfig(config: McpServerConfig) {
        val current = _serverConfigs.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.name == config.name }
        if (existingIndex >= 0) {
            current[existingIndex] = config
        } else {
            current.add(config)
        }
        _serverConfigs.value = current
        onConfigsChanged?.invoke(current)
        updateStatuses()
    }

    /**
     * 删除服务器配置
     */
    fun removeServerConfig(name: String) {
        disconnectServer(name)
        _serverConfigs.value = _serverConfigs.value.filter { it.name != name }
        onConfigsChanged?.invoke(_serverConfigs.value)
        updateStatuses()
    }

    /**
     * 更新服务器配置（保持连接状态不变）
     */
    fun updateServerConfig(oldName: String, newConfig: McpServerConfig) {
        val wasConnected = connections.containsKey(oldName)
        if (wasConnected) {
            disconnectServer(oldName)
        }

        val current = _serverConfigs.value.toMutableList()
        val index = current.indexOfFirst { it.name == oldName }
        if (index >= 0) {
            current[index] = newConfig
        } else {
            current.add(newConfig)
        }
        _serverConfigs.value = current
        onConfigsChanged?.invoke(current)
        updateStatuses()
    }

    // ==================== PluginManager 集成 ====================

    /**
     * 将 MCP ToolProvider 注册到 PluginManager._toolProviders
     * 使 MCP 工具自动进入 getAllTools() → getToolSchemas() → LLM 工具列表
     */
    private fun registerToolProvider(provider: McpToolProvider) {
        pluginManager.registerToolProvider(provider)
    }

    private fun unregisterToolProvider(provider: McpToolProvider) {
        pluginManager.unregisterToolProvider(provider.providerId)
    }

    // ==================== 内部方法 ====================

    private fun updateStatuses() {
        val statuses = mutableMapOf<String, McpServerStatus>()
        for (config in _serverConfigs.value) {
            val conn = connections[config.name]
            statuses[config.name] = conn?.getStatus() ?: McpServerStatus(
                name = config.name,
                connected = false,
            )
        }
        _serverStatuses.value = statuses
    }
}

// =====================================================================
// MCP ToolProvider — 将 MCP 服务器的工具暴露为 PluginManager ToolProvider
//
// 数据链路：
// McpClient.tools (McpToolDef) → McpToolProvider.getTools() (ToolDefinition)
// → PluginManager._toolProviders → getAllTools() → getToolSchemas()
// → LLMRequest.tools → LLM API
//
// 执行链路：
// LLM tool_call → executeToolCalls() → pluginManager.executeTool()
// → McpToolProvider.executeTool() → McpClient.callTool() → MCP server
// =====================================================================

class McpToolProvider(
    private val serverName: String,
    private val client: McpClient,
) : ToolProvider {

    override val providerId: String = "mcp.$serverName"
    override val providerName: String = "MCP: $serverName"

    /**
     * 将 MCP 工具定义转换为 PluginManager 的 ToolDefinition
     */
    override fun getTools(): List<ToolDefinition> {
        return client.tools.map { mcpTool ->
            ToolDefinition(
                name = mcpTool.name,
                description = mcpTool.description,
                parameters = mcpTool.inputSchema,
            )
        }
    }

    /**
     * 执行 MCP 工具调用，将结果转换为 ToolResult
     */
    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        return try {
            val result = client.callTool(name, arguments)

            val textContent = result.content
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")

            ToolResult(
                success = !result.isError,
                result = JsonPrimitive(textContent),
                error = if (result.isError) textContent else null,
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                error = "MCP tool call failed: ${e.message}",
            )
        }
    }
}
