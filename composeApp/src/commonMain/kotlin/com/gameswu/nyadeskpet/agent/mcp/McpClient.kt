package com.gameswu.nyadeskpet.agent.mcp

import com.gameswu.nyadeskpet.currentTimeMillis
import com.gameswu.nyadeskpet.util.DebugLog
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * MCP SSE 客户端 — 单个 MCP 服务器的连接
 *
 * 实现 MCP 2024-11-05 规范的 SSE 传输：
 * 1. GET {url} → 打开 SSE 流，接收 `endpoint` 事件获取消息端点
 * 2. POST {messageEndpoint} → 发送 JSON-RPC 请求
 * 3. 通过 SSE `message` 事件接收 JSON-RPC 响应
 *
 * 对齐原项目 MCPConnection（mcp-client.ts）
 */
class McpClient(private val config: McpServerConfig) {

    private val httpClient = HttpClient { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var messageEndpoint: String? = null
    private var _connected: Boolean = false
    private var _error: String? = null
    private var _lastConnectedAt: Long? = null
    private var _tools: List<McpToolDef> = emptyList()
    private var requestId = 0
    private var sseJob: Job? = null

    // 挂起中的 JSON-RPC 请求（id → CompletableDeferred）
    private val pendingRequests = mutableMapOf<Int, CompletableDeferred<JsonRpcResponse>>()

    val connected: Boolean get() = _connected
    val toolCount: Int get() = _tools.size
    val tools: List<McpToolDef> get() = _tools

    fun getStatus(): McpServerStatus = McpServerStatus(
        name = config.name,
        connected = _connected,
        toolCount = _tools.size,
        error = _error,
        lastConnectedAt = _lastConnectedAt,
    )

    /**
     * 连接到 MCP 服务器（SSE 传输）
     *
     * 流程：
     * 1. 打开 SSE 连接获取 message endpoint
     * 2. 发送 initialize 请求
     * 3. 发送 initialized 通知
     * 4. 发送 tools/list 请求发现工具
     */
    suspend fun connect() {
        if (_connected) return

        try {
            // 1. 打开 SSE 连接获取 endpoint
            openSseConnection()

            // 等待 endpoint 就绪（最多 10 秒）
            withTimeout(10_000) {
                while (messageEndpoint == null) {
                    delay(50)
                }
            }

            // 2. 发送 initialize
            val initParams = json.encodeToJsonElement(
                McpInitializeParams.serializer(),
                McpInitializeParams()
            )
            val initResponse = sendRequest("initialize", initParams)
            if (initResponse.error != null) {
                throw Exception("Initialize failed: ${initResponse.error.message}")
            }

            // 3. 发送 initialized 通知（无 id，不期望响应）
            sendNotification("notifications/initialized")

            // 4. 发现工具
            val toolsResponse = sendRequest("tools/list")
            if (toolsResponse.result != null) {
                val toolsList = json.decodeFromJsonElement(
                    McpToolsListResult.serializer(),
                    toolsResponse.result
                )
                _tools = toolsList.tools
            }

            _connected = true
            _error = null
            _lastConnectedAt = currentTimeMillis()
            DebugLog.d("McpClient") { "已连接: ${config.name}, 发现 ${_tools.size} 个工具" }

        } catch (e: Exception) {
            _connected = false
            _error = e.message
            DebugLog.d("McpClient") { "连接失败: ${config.name} - ${e.message}" }
            throw e
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        sseJob?.cancel()
        sseJob = null
        messageEndpoint = null
        _connected = false
        _tools = emptyList()
        pendingRequests.values.forEach {
            it.completeExceptionally(Exception("Disconnected"))
        }
        pendingRequests.clear()
        DebugLog.d("McpClient") { "已断开: ${config.name}" }
    }

    /**
     * 调用 MCP 工具（带重连机制）
     * 对齐原项目 MCPConnection.callTool
     */
    suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult {
        try {
            return doCallTool(name, arguments)
        } catch (_: Exception) {
            if (!_connected) {
                // 尝试重连
                DebugLog.d("McpClient") { "工具调用失败，尝试重连: ${config.name}" }
                try {
                    disconnect()
                    connect()
                    return doCallTool(name, arguments)
                } catch (reconnectError: Exception) {
                    return McpToolCallResult(
                        content = listOf(McpContentBlock(type = "text", text = "MCP 重连失败: ${reconnectError.message}")),
                        isError = true,
                    )
                }
            }
            return McpToolCallResult(
                content = listOf(McpContentBlock(type = "text", text = "工具调用失败: ${_error}")),
                isError = true,
            )
        }
    }

    private suspend fun doCallTool(name: String, arguments: JsonObject): McpToolCallResult {
        val params = json.encodeToJsonElement(
            McpToolCallParams.serializer(),
            McpToolCallParams(name = name, arguments = arguments)
        )
        val response = sendRequest("tools/call", params)

        if (response.error != null) {
            return McpToolCallResult(
                content = listOf(McpContentBlock(type = "text", text = response.error.message)),
                isError = true,
            )
        }

        return if (response.result != null) {
            json.decodeFromJsonElement(McpToolCallResult.serializer(), response.result)
        } else {
            McpToolCallResult(
                content = listOf(McpContentBlock(type = "text", text = "Empty response")),
                isError = true,
            )
        }
    }

    // ==================== SSE 传输层 ====================

    /**
     * 打开 SSE 连接（后台协程），解析 endpoint 和 message 事件
     */
    private fun openSseConnection() {
        sseJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val statement = httpClient.prepareGet(config.url) {
                    header("Accept", "text/event-stream")
                    header("Cache-Control", "no-cache")
                    for ((key, value) in config.headers) {
                        header(key, value)
                    }
                }

                statement.execute { response ->
                    if (!response.status.isSuccess()) {
                        _error = "SSE connection failed: HTTP ${response.status.value}"
                        return@execute
                    }

                    val channel: ByteReadChannel = response.bodyAsChannel()
                    val lineBuffer = StringBuilder()
                    var eventType = ""
                    var eventData = StringBuilder()

                    while (!channel.isClosedForRead && isActive) {
                        val byte = try {
                            channel.readByte()
                        } catch (_: Exception) {
                            break
                        }

                        val char = byte.toInt().toChar()
                        if (char == '\n') {
                            val line = lineBuffer.toString()
                            lineBuffer.clear()

                            when {
                                line.startsWith("event: ") -> {
                                    eventType = line.removePrefix("event: ").trim()
                                }
                                line.startsWith("data: ") -> {
                                    eventData.append(line.removePrefix("data: "))
                                }
                                line.isEmpty() -> {
                                    // 空行 = 一个 SSE 事件结束
                                    val data = eventData.toString().trim()
                                    eventData.clear()

                                    if (data.isNotEmpty()) {
                                        handleSseEvent(eventType, data)
                                    }
                                    eventType = ""
                                }
                            }
                        } else {
                            lineBuffer.append(char)
                        }
                    }
                }
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                // 正常取消
            } catch (e: Exception) {
                _connected = false
                _error = "SSE stream error: ${e.message}"
                DebugLog.d("McpClient") { "SSE 流错误: ${config.name} - ${e.message}" }
            }
        }
    }

    /**
     * 处理 SSE 事件
     * - endpoint: 设置 POST 消息端点
     * - message: 解析 JSON-RPC 响应并完成挂起请求
     */
    private fun handleSseEvent(eventType: String, data: String) {
        when (eventType) {
            "endpoint" -> {
                // data 内容是相对或绝对 URL 路径
                messageEndpoint = resolveEndpoint(data)
                DebugLog.d("McpClient") { "获取到消息端点: $messageEndpoint" }
            }
            "message" -> {
                try {
                    val response = json.decodeFromString(JsonRpcResponse.serializer(), data)
                    val id = response.id
                    if (id != null) {
                        pendingRequests.remove(id)?.complete(response)
                    }
                } catch (e: Exception) {
                    DebugLog.d("McpClient") { "解析 JSON-RPC 响应失败: ${e.message}" }
                }
            }
        }
    }

    /**
     * 将 endpoint 补全为绝对 URL
     */
    private fun resolveEndpoint(endpoint: String): String {
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint
        }
        // 从 config.url 提取基础 URL
        val baseUrl = config.url.let { url ->
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return@let url
            val pathStart = url.indexOf('/', schemeEnd + 3)
            if (pathStart < 0) url else url.substring(0, pathStart)
        }
        return if (endpoint.startsWith("/")) {
            "$baseUrl$endpoint"
        } else {
            "$baseUrl/$endpoint"
        }
    }

    // ==================== JSON-RPC 请求 ====================

    /**
     * 发送 JSON-RPC 请求并等待响应（最长 30 秒）
     */
    private suspend fun sendRequest(method: String, params: JsonElement? = null): JsonRpcResponse {
        val endpoint = messageEndpoint ?: throw Exception("Message endpoint not available")
        val id = ++requestId

        val request = JsonRpcRequest(
            id = id,
            method = method,
            params = params,
        )

        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[id] = deferred

        try {
            val response = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                for ((key, value) in config.headers) {
                    header(key, value)
                }
                setBody(json.encodeToString(JsonRpcRequest.serializer(), request))
            }

            if (!response.status.isSuccess()) {
                pendingRequests.remove(id)
                val bodyText = response.bodyAsText()
                throw Exception("HTTP ${response.status.value}: $bodyText")
            }

            // 有些 MCP 服务端直接在 POST 响应中返回结果（而不是通过 SSE）
            val bodyText = response.bodyAsText()
            if (bodyText.isNotBlank()) {
                try {
                    val directResponse = json.decodeFromString(JsonRpcResponse.serializer(), bodyText)
                    if (directResponse.id == id) {
                        pendingRequests.remove(id)
                        return directResponse
                    }
                } catch (_: Exception) {
                    // 不是 JSON-RPC 响应，继续等待 SSE
                }
            }

            // 等待 SSE 中的响应
            return withTimeout(30_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            throw Exception("Request timeout: $method")
        } catch (e: Exception) {
            pendingRequests.remove(id)
            throw e
        }
    }

    /**
     * 发送 JSON-RPC 通知（无 id，不期望响应）
     */
    private suspend fun sendNotification(method: String, params: JsonElement? = null) {
        val endpoint = messageEndpoint ?: return

        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
            if (params != null) put("params", params)
        }

        httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            for ((key, value) in config.headers) {
                header(key, value)
            }
            setBody(body.toString())
        }
    }

    fun close() {
        disconnect()
        httpClient.close()
    }
}
