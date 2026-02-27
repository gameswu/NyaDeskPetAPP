/**
 * Anthropic（Claude）Provider
 * 对齐原 Electron 项目 src/agent/providers/anthropic.ts
 *
 * 使用 Anthropic Messages API 原生格式，非 OpenAI 兼容
 *
 * 与 OpenAI API 的关键差异：
 * - 认证：使用 x-api-key 头（非 Bearer Token）
 * - 系统提示词：单独的 system 字段（非 messages 数组中的 system 角色）
 * - 消息角色：仅 user/assistant（工具结果以 user 角色 + tool_result 内容块发送）
 * - 工具定义：{ name, description, input_schema }（非 { type: "function", function: {...} }）
 * - 响应格式：content 为块数组 [{type:"text",...}, {type:"tool_use",...}]
 * - 用量字段：input_tokens / output_tokens（非 prompt_tokens / completion_tokens）
 * - 停止原因：stop_reason（非 finish_reason），end_turn / tool_use / max_tokens
 * - 流式事件：message_start → content_block_start → content_block_delta → content_block_stop → message_delta → message_stop
 */
package com.gameswu.nyadeskpet.agent.provider.providers

import com.gameswu.nyadeskpet.agent.provider.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ==================== Anthropic API 类型 ====================

@Serializable
internal data class AnthropicRequestBody(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val temperature: Float? = null,
    val stream: Boolean = false,
    val tools: List<AnthropicToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: JsonObject? = null,
)

@Serializable
internal data class AnthropicMessage(
    val role: String, // "user" | "assistant"
    val content: JsonElement, // String 或 ContentBlock 数组
)

@Serializable
internal data class AnthropicToolDefinition(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

@Serializable
internal data class AnthropicResponse(
    val id: String = "",
    val type: String = "message",
    val role: String = "assistant",
    val content: List<AnthropicContentBlock> = emptyList(),
    val model: String = "",
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
internal data class AnthropicContentBlock(
    val type: String, // "text" | "tool_use"
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

@Serializable
internal data class AnthropicErrorResponse(
    val error: AnthropicErrorDetail? = null,
)

@Serializable
internal data class AnthropicErrorDetail(
    val type: String = "",
    val message: String = "",
)

// ==================== Anthropic Provider 实现 ====================

class AnthropicProvider(config: ProviderConfig) : LLMProvider(
    config.let {
        it.copy(model = it.model ?: "claude-sonnet-4-20250514")
    }
) {
    companion object {
        private const val API_VERSION = "2023-06-01"
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    }

    private val httpClient = buildHttpClient()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun getMetadata(): ProviderMetadata = ANTHROPIC_METADATA

    override suspend fun initialize() {
        initialized = true
    }

    override suspend fun terminate() {
        httpClient.close()
        initialized = false
    }

    // ==================== 消息转换 ====================

    /**
     * 转换内部 ChatMessage 为 Anthropic 格式
     * Anthropic 仅支持 user/assistant 角色，system 单独字段，tool 结果以 user + tool_result 发送
     */
    private fun convertMessages(messages: List<ChatMessage>): List<AnthropicMessage> {
        val result = mutableListOf<AnthropicMessage>()

        for (msg in messages) {
            if (msg.role == "system") continue // system 通过独立字段传递

            if (msg.role == "tool") {
                // 工具结果 → user 角色 + tool_result 内容块
                val toolResultBlock = buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.toolCallId ?: "")
                    put("content", msg.content)
                }
                val contentArray = buildJsonArray { add(toolResultBlock) }

                // 如果上一条也是 user（多个工具结果），合并到同一条
                val lastMsg = result.lastOrNull()
                if (lastMsg != null && lastMsg.role == "user" && lastMsg.content is JsonArray) {
                    val merged = buildJsonArray {
                        lastMsg.content.jsonArray.forEach { add(it) }
                        add(toolResultBlock)
                    }
                    result[result.lastIndex] = AnthropicMessage(role = "user", content = merged)
                } else {
                    result.add(AnthropicMessage(role = "user", content = contentArray))
                }
                continue
            }

            if (msg.role == "assistant") {
                val blocks = buildJsonArray {
                    if (msg.content.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    msg.toolCalls?.forEach { tc ->
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            val inputObj = try {
                                json.parseToJsonElement(tc.arguments).jsonObject
                            } catch (_: Exception) {
                                buildJsonObject { put("raw", tc.arguments) }
                            }
                            put("input", inputObj)
                        })
                    }
                }
                if (blocks.isNotEmpty()) {
                    result.add(AnthropicMessage(role = "assistant", content = blocks))
                } else {
                    result.add(AnthropicMessage(role = "assistant", content = JsonPrimitive(msg.content)))
                }
                continue
            }

            // user 消息
            if (msg.images != null && msg.images.isNotEmpty()) {
                // 多模态消息（Vision）
                val blocks = buildJsonArray {
                    for (img in msg.images) {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", img.mimeType)
                                put("data", img.data)
                            })
                        })
                    }
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                result.add(AnthropicMessage(role = "user", content = blocks))
            } else {
                result.add(AnthropicMessage(role = "user", content = JsonPrimitive(msg.content)))
            }
        }

        return result
    }

    /**
     * 转换 OpenAI 格式的工具定义为 Anthropic 格式
     */
    private fun convertTools(tools: List<ToolDefinitionSchema>): List<AnthropicToolDefinition> {
        return tools.map { tool ->
            AnthropicToolDefinition(
                name = tool.function.name,
                description = tool.function.description,
                inputSchema = tool.function.parameters ?: buildJsonObject {},
            )
        }
    }

    /**
     * 转换工具选择策略
     */
    private fun convertToolChoice(choice: String?): JsonObject? {
        return when (choice) {
            "auto" -> buildJsonObject { put("type", "auto") }
            "required" -> buildJsonObject { put("type", "any") }
            "none" -> null // Anthropic 不支持 none，直接不传 tools
            else -> buildJsonObject { put("type", "auto") }
        }
    }

    /**
     * 转换 Anthropic 停止原因为内部格式
     */
    private fun convertStopReason(stopReason: String?): String? {
        return when (stopReason) {
            "end_turn" -> "stop"
            "tool_use" -> "tool_calls"
            "max_tokens" -> "length"
            "stop_sequence" -> "stop"
            else -> stopReason
        }
    }

    /**
     * 从响应 content 块中提取文本和工具调用
     */
    private fun parseResponseContent(content: List<AnthropicContentBlock>): Pair<String, List<ToolCallInfo>?> {
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCallInfo>()

        for (block in content) {
            when (block.type) {
                "text" -> block.text?.let { textParts.add(it) }
                "tool_use" -> {
                    if (block.id != null && block.name != null) {
                        toolCalls.add(ToolCallInfo(
                            id = block.id,
                            name = block.name,
                            arguments = block.input?.toString() ?: "{}",
                        ))
                    }
                }
            }
        }

        return Pair(textParts.joinToString(""), toolCalls.takeIf { it.isNotEmpty() })
    }

    // ==================== 非流式聊天 ====================

    override suspend fun chat(request: LLMRequest): LLMResponse {
        val baseUrl = getConfigValue("baseUrl", DEFAULT_BASE_URL)
        val apiKey = getConfigValue<String?>("apiKey", null)
        val model = request.model ?: modelName

        val requestBody = AnthropicRequestBody(
            model = model,
            messages = convertMessages(request.messages),
            system = request.systemPrompt,
            maxTokens = request.maxTokens ?: DEFAULT_MAX_TOKENS,
            temperature = request.temperature,
            stream = false,
            tools = request.tools?.takeIf { it.isNotEmpty() }?.let { convertTools(it) },
            toolChoice = if (request.tools?.isNotEmpty() == true) convertToolChoice(request.toolChoice) else null,
        )

        return try {
            val response = httpClient.post("$baseUrl/v1/messages") {
                apiKey?.let { header("x-api-key", it) }
                header("anthropic-version", API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AnthropicRequestBody.serializer(), requestBody))
            }

            val bodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val errorResp = try {
                    json.decodeFromString(AnthropicErrorResponse.serializer(), bodyText)
                } catch (_: Exception) { null }

                val errorMsg = when (response.status.value) {
                    401 -> "Anthropic API Key 无效或已过期"
                    429 -> "请求频率过高或配额已用尽"
                    529 -> "Anthropic API 暂时过载，请稍后重试"
                    in 500..599 -> "Anthropic 服务暂时不可用 (${response.status.value})"
                    else -> errorResp?.error?.message ?: "HTTP ${response.status.value}: $bodyText"
                }
                return LLMResponse(text = errorMsg, finishReason = "error")
            }

            val chatResp = json.decodeFromString(AnthropicResponse.serializer(), bodyText)
            val (text, toolCalls) = parseResponseContent(chatResp.content)

            LLMResponse(
                text = text,
                usage = chatResp.usage?.let {
                    TokenUsage(
                        promptTokens = it.inputTokens,
                        completionTokens = it.outputTokens,
                        totalTokens = it.inputTokens + it.outputTokens,
                    )
                },
                model = chatResp.model,
                finishReason = convertStopReason(chatResp.stopReason),
                toolCalls = toolCalls,
            )
        } catch (e: Exception) {
            LLMResponse(text = "请求失败: ${e.message}", finishReason = "error")
        }
    }

    // ==================== 流式聊天 ====================

    override suspend fun chatStream(request: LLMRequest, onChunk: suspend (LLMStreamChunk) -> Unit) {
        val baseUrl = getConfigValue("baseUrl", DEFAULT_BASE_URL)
        val apiKey = getConfigValue<String?>("apiKey", null)
        val model = request.model ?: modelName

        val requestBody = AnthropicRequestBody(
            model = model,
            messages = convertMessages(request.messages),
            system = request.systemPrompt,
            maxTokens = request.maxTokens ?: DEFAULT_MAX_TOKENS,
            temperature = request.temperature,
            stream = true,
            tools = request.tools?.takeIf { it.isNotEmpty() }?.let { convertTools(it) },
            toolChoice = if (request.tools?.isNotEmpty() == true) convertToolChoice(request.toolChoice) else null,
        )

        try {
            val statement = httpClient.preparePost("$baseUrl/v1/messages") {
                apiKey?.let { header("x-api-key", it) }
                header("anthropic-version", API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AnthropicRequestBody.serializer(), requestBody))
            }

            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    val bodyText = response.bodyAsText()
                    val errorResp = try {
                        json.decodeFromString(AnthropicErrorResponse.serializer(), bodyText)
                    } catch (_: Exception) { null }

                    val errorMsg = when (response.status.value) {
                        401 -> "Anthropic API Key 无效或已过期"
                        429 -> "请求频率过高或配额已用尽"
                        529 -> "Anthropic API 暂时过载，请稍后重试"
                        else -> errorResp?.error?.message ?: "HTTP ${response.status.value}: $bodyText"
                    }
                    onChunk(LLMStreamChunk(delta = errorMsg, done = true))
                    return@execute
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                var inputTokens = 0
                var outputTokens = 0

                // 追踪活跃的工具调用
                val activeToolCalls = mutableMapOf<Int, Triple<String, String, StringBuilder>>() // index → (id, name, jsonAccumulator)

                // 使用 readLine 正确处理多字节 UTF-8 字符（如中文）
                while (!channel.isClosedForRead) {
                    val line = (try { channel.readLine() } catch (_: Exception) { null })?.trim() ?: break

                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ")
                        try {
                            val event = json.parseToJsonElement(data).jsonObject
                            val eventType = event["type"]?.jsonPrimitive?.contentOrNull ?: ""

                            when (eventType) {
                                "message_start" -> {
                                    val usage = event["message"]?.jsonObject?.get("usage")?.jsonObject
                                    inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                }

                                "content_block_start" -> {
                                    val contentBlock = event["content_block"]?.jsonObject
                                    val blockType = contentBlock?.get("type")?.jsonPrimitive?.contentOrNull
                                    val index = event["index"]?.jsonPrimitive?.intOrNull

                                    if (blockType == "tool_use" && index != null) {
                                        val blockId = contentBlock["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val blockName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                        activeToolCalls[index] = Triple(blockId, blockName, StringBuilder())
                                        onChunk(LLMStreamChunk(
                                            delta = "",
                                            done = false,
                                            toolCallDeltas = listOf(ToolCallDelta(
                                                index = index,
                                                id = blockId,
                                                name = blockName,
                                                arguments = "",
                                            )),
                                        ))
                                    }
                                }

                                "content_block_delta" -> {
                                    val delta = event["delta"]?.jsonObject
                                    val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull
                                    val index = event["index"]?.jsonPrimitive?.intOrNull

                                    when (deltaType) {
                                        "text_delta" -> {
                                            val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                            onChunk(LLMStreamChunk(delta = text, done = false))
                                        }
                                        "input_json_delta" -> {
                                            val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                                            if (index != null) {
                                                activeToolCalls[index]?.third?.append(partialJson)
                                                onChunk(LLMStreamChunk(
                                                    delta = "",
                                                    done = false,
                                                    toolCallDeltas = listOf(ToolCallDelta(
                                                        index = index,
                                                        arguments = partialJson,
                                                    )),
                                                ))
                                            }
                                        }
                                    }
                                }

                                "message_delta" -> {
                                    val usage = event["usage"]?.jsonObject
                                    if (usage != null) {
                                        outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: outputTokens
                                    }
                                    val stopReason = event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                                    if (stopReason != null) {
                                        onChunk(LLMStreamChunk(
                                            delta = "",
                                            done = false,
                                            finishReason = convertStopReason(stopReason),
                                        ))
                                    }
                                }

                                "message_stop" -> {
                                    onChunk(LLMStreamChunk(
                                        delta = "",
                                        done = true,
                                        usage = TokenUsage(
                                            promptTokens = inputTokens,
                                            completionTokens = outputTokens,
                                            totalTokens = inputTokens + outputTokens,
                                        ),
                                    ))
                                    return@execute
                                }
                            }
                        } catch (_: Exception) {
                            // 忽略解析错误
                        }
                    }
                }

                onChunk(LLMStreamChunk(delta = "", done = true))
            }
        } catch (e: Exception) {
            onChunk(LLMStreamChunk(delta = "流式请求失败: ${e.message}", done = true))
        }
    }

    // ==================== 模型列表 ====================

    override suspend fun getModels(): List<String> {
        // Anthropic 没有 /models 端点，返回硬编码列表
        return listOf(
            "claude-sonnet-4-20250514",
            "claude-opus-4-20250514",
            "claude-haiku-3-5-20241022",
        )
    }

    // ==================== 连接测试 ====================

    override suspend fun test(): TestResult {
        return try {
            if (!initialized) initialize()
            val response = chat(LLMRequest(
                messages = listOf(ChatMessage(role = "user", content = "Hi")),
                maxTokens = 10,
            ))
            if (response.finishReason == "error") {
                TestResult(false, response.text)
            } else {
                TestResult(true, model = response.model)
            }
        } catch (e: Exception) {
            TestResult(false, e.message)
        }
    }
}

// ==================== Provider 元信息 ====================

val ANTHROPIC_METADATA = ProviderMetadata(
    id = "anthropic",
    name = "Anthropic（Claude）",
    description = "Anthropic 官方 API，Claude Sonnet/Opus/Haiku 系列模型，业界领先的推理和代码能力，支持 Vision 和 Function Calling",
    configSchema = listOf(
        ProviderConfigField(
            key = "apiKey", label = "API Key", type = "password",
            required = true, placeholder = "sk-ant-...",
            description = "从 Anthropic 控制台获取的 API 密钥（https://console.anthropic.com/settings/keys）",
        ),
        ProviderConfigField(
            key = "baseUrl", label = "API Base URL", type = "string",
            default = "https://api.anthropic.com",
            description = "Anthropic API 地址。如需代理可修改此地址",
        ),
        ProviderConfigField(
            key = "model", label = "模型", type = "string",
            default = "claude-sonnet-4-20250514", placeholder = "claude-sonnet-4-20250514",
            description = "模型 ID，如 claude-sonnet-4-20250514、claude-opus-4-20250514、claude-haiku-3-5-20241022",
        ),
        ProviderConfigField(
            key = "timeout", label = "超时时间（秒）", type = "number",
            default = "120", description = "请求超时时间，Opus 模型建议适当增大",
        ),
        ProviderConfigField(
            key = "proxy", label = "代理地址", type = "string",
            placeholder = "http://127.0.0.1:7890",
            description = "HTTP/HTTPS 代理（如需翻墙访问）",
        ),
        ProviderConfigField(
            key = "stream", label = "流式输出", type = "boolean",
            default = "false",
            description = "启用后 LLM 回复将逐字流式显示，提升响应速度体验",
        ),
    ) + PROVIDER_CAPABILITY_FIELDS,
)