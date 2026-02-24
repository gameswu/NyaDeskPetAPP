/**
 * OpenAI 兼容 Provider
 * 对齐原 Electron 项目 src/agent/providers/openai.ts
 *
 * 支持 OpenAI API 及所有兼容接口：
 * - OpenAI (GPT-4o / GPT-4o-mini)
 * - DeepSeek / Moonshot / 智谱 / 硅基流动 等
 * - Ollama / vLLM / LM Studio 等本地部署
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
import kotlinx.serialization.json.Json

// ==================== OpenAI API 模型 ====================

@Serializable
internal data class OaiChatRequest(
    val model: String,
    val messages: List<OaiMessage>,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinitionSchema>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

@Serializable
internal data class OaiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OaiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class OaiToolCall(
    val id: String,
    val type: String = "function",
    val function: OaiFunction,
)

@Serializable
internal data class OaiFunction(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class OaiChatResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<OaiChoice> = emptyList(),
    val usage: OaiUsage? = null,
)

@Serializable
internal data class OaiChoice(
    val index: Int = 0,
    val message: OaiChoiceMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OaiChoiceMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OaiToolCall>? = null,
)

@Serializable
internal data class OaiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
internal data class OaiModelsResponse(
    val data: List<OaiModel> = emptyList(),
)

@Serializable
internal data class OaiModel(
    val id: String = "",
)

// --- Streaming ---

@Serializable
internal data class OaiChatChunk(
    val id: String = "",
    val choices: List<OaiChunkChoice> = emptyList(),
    val usage: OaiUsage? = null,
)

@Serializable
internal data class OaiChunkChoice(
    val index: Int = 0,
    val delta: OaiDelta = OaiDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OaiDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OaiToolCallChunk>? = null,
)

@Serializable
internal data class OaiToolCallChunk(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OaiFunctionChunk? = null,
)

@Serializable
internal data class OaiFunctionChunk(
    val name: String? = null,
    val arguments: String? = null,
)

// --- Error ---

@Serializable
internal data class OaiErrorResponse(
    val error: OaiError? = null,
)

@Serializable
internal data class OaiError(
    val message: String = "",
    val type: String? = null,
    val code: String? = null,
)

// ==================== OpenAI Provider 实现 ====================

/**
 * OpenAI 兼容 Provider
 * 对应原项目 OpenAIProvider class
 */
open class OpenAIProvider(config: ProviderConfig) : LLMProvider(config) {

    protected val httpClient = HttpClient { expectSuccess = false }
    protected var cachedModels: List<String> = emptyList()

    protected val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    override fun getMetadata(): ProviderMetadata = OPENAI_METADATA

    override suspend fun initialize() {
        initialized = true
    }

    override suspend fun terminate() {
        httpClient.close()
        initialized = false
    }

    // ==================== 消息转换 ====================

    /**
     * 将内部 ChatMessage 转换为 OpenAI API 消息格式
     */
    private fun convertMessages(messages: List<ChatMessage>, systemPrompt: String?): List<OaiMessage> {
        val result = mutableListOf<OaiMessage>()

        // 系统提示词
        if (!systemPrompt.isNullOrBlank()) {
            result.add(OaiMessage(role = "system", content = systemPrompt))
        }

        for (msg in messages) {
            // 工具结果消息
            if (msg.role == "tool") {
                result.add(OaiMessage(
                    role = "tool",
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                ))
                continue
            }

            // 助手消息带 tool_calls
            if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                result.add(OaiMessage(
                    role = "assistant",
                    content = msg.content.takeIf { it.isNotEmpty() },
                    reasoningContent = msg.reasoningContent,
                    toolCalls = msg.toolCalls.map { tc ->
                        OaiToolCall(
                            id = tc.id,
                            function = OaiFunction(name = tc.name, arguments = tc.arguments),
                        )
                    },
                ))
                continue
            }

            // 助手消息带 reasoning_content（DeepSeek thinking mode）
            if (msg.role == "assistant" && msg.reasoningContent != null) {
                result.add(OaiMessage(
                    role = msg.role,
                    content = msg.content,
                    reasoningContent = msg.reasoningContent,
                ))
                continue
            }

            // 普通消息
            result.add(OaiMessage(role = msg.role, content = msg.content))
        }

        return result
    }

    private fun convertUsage(usage: OaiUsage?): TokenUsage? {
        if (usage == null) return null
        return TokenUsage(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
        )
    }

    // ==================== 非流式聊天 ====================

    override suspend fun chat(request: LLMRequest): LLMResponse {
        val baseUrl = getConfigValue("baseUrl", "https://api.openai.com/v1")
        val apiKey = getConfigValue<String?>("apiKey", null)
        val model = request.model ?: modelName

        val requestBody = json.encodeToString(
            OaiChatRequest.serializer(),
            OaiChatRequest(
                model = model,
                messages = convertMessages(request.messages, request.systemPrompt),
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                stream = false,
                tools = request.tools?.takeIf { it.isNotEmpty() },
                toolChoice = request.toolChoice,
            ),
        )

        return try {
            val response = httpClient.post("$baseUrl/chat/completions") {
                apiKey?.let { header("Authorization", "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            val bodyText = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val errorResp = try {
                    json.decodeFromString(OaiErrorResponse.serializer(), bodyText)
                } catch (_: Exception) { null }
                return LLMResponse(
                    text = errorResp?.error?.message ?: "HTTP ${response.status.value}: $bodyText",
                    finishReason = "error",
                )
            }

            val chatResp = json.decodeFromString(OaiChatResponse.serializer(), bodyText)
            val choice = chatResp.choices.firstOrNull()
            val message = choice?.message

            LLMResponse(
                text = message?.content ?: "",
                usage = convertUsage(chatResp.usage),
                model = chatResp.model,
                finishReason = choice?.finishReason,
                reasoningContent = message?.reasoningContent,
                toolCalls = message?.toolCalls?.map { tc ->
                    ToolCallInfo(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)
                },
            )
        } catch (e: Exception) {
            LLMResponse(text = "请求失败: ${e.message}", finishReason = "error")
        }
    }

    // ==================== 流式聊天 ====================

    override suspend fun chatStream(request: LLMRequest, onChunk: suspend (LLMStreamChunk) -> Unit) {
        val baseUrl = getConfigValue("baseUrl", "https://api.openai.com/v1")
        val apiKey = getConfigValue<String?>("apiKey", null)
        val model = request.model ?: modelName

        val requestBody = json.encodeToString(
            OaiChatRequest.serializer(),
            OaiChatRequest(
                model = model,
                messages = convertMessages(request.messages, request.systemPrompt),
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                stream = true,
                tools = request.tools?.takeIf { it.isNotEmpty() },
                toolChoice = request.toolChoice,
            ),
        )

        try {
            val statement = httpClient.preparePost("$baseUrl/chat/completions") {
                apiKey?.let { header("Authorization", "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    val bodyText = response.bodyAsText()
                    val errorResp = try {
                        json.decodeFromString(OaiErrorResponse.serializer(), bodyText)
                    } catch (_: Exception) { null }
                    onChunk(LLMStreamChunk(
                        delta = errorResp?.error?.message ?: "HTTP ${response.status.value}: $bodyText",
                        done = true,
                    ))
                    return@execute
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                val lineBuffer = StringBuilder()

                while (!channel.isClosedForRead) {
                    val byte = try {
                        channel.readByte()
                    } catch (_: Exception) {
                        break
                    }

                    val char = byte.toInt().toChar()
                    if (char == '\n') {
                        val line = lineBuffer.toString()
                        lineBuffer.clear()

                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") {
                                onChunk(LLMStreamChunk(delta = "", done = true))
                                return@execute
                            }

                            try {
                                val chunk = json.decodeFromString(OaiChatChunk.serializer(), data)
                                val delta = chunk.choices.firstOrNull()?.delta
                                val choiceFinishReason = chunk.choices.firstOrNull()?.finishReason

                                if (delta != null) {
                                    val toolCallDeltas = delta.toolCalls?.map { tc ->
                                        ToolCallDelta(
                                            index = tc.index,
                                            id = tc.id,
                                            name = tc.function?.name,
                                            arguments = tc.function?.arguments,
                                        )
                                    }

                                    onChunk(LLMStreamChunk(
                                        delta = delta.content ?: "",
                                        done = false,
                                        reasoningDelta = delta.reasoningContent,
                                        finishReason = choiceFinishReason,
                                        toolCallDeltas = toolCallDeltas,
                                    ))
                                }

                                // 最后一个 chunk 可能包含 usage
                                if (chunk.usage != null) {
                                    onChunk(LLMStreamChunk(
                                        delta = "",
                                        done = true,
                                        usage = convertUsage(chunk.usage),
                                    ))
                                    return@execute
                                }
                            } catch (_: Exception) {
                                // 忽略解析错误
                            }
                        }
                    } else {
                        lineBuffer.append(char)
                    }
                }

                // 流结束
                onChunk(LLMStreamChunk(delta = "", done = true))
            }
        } catch (e: Exception) {
            onChunk(LLMStreamChunk(delta = "流式请求失败: ${e.message}", done = true))
        }
    }

    // ==================== 模型列表 ====================

    override suspend fun getModels(): List<String> {
        if (cachedModels.isNotEmpty()) return cachedModels

        val baseUrl = getConfigValue("baseUrl", "https://api.openai.com/v1")
        val apiKey = getConfigValue<String?>("apiKey", null)

        val response = httpClient.get("$baseUrl/models") {
            apiKey?.let { header("Authorization", "Bearer $it") }
        }

        val bodyText = response.bodyAsText()
        val modelsResp = json.decodeFromString(OaiModelsResponse.serializer(), bodyText)
        cachedModels = modelsResp.data.map { it.id }.sorted()
        return cachedModels
    }

    // ==================== 连接测试 ====================

    override suspend fun test(): TestResult {
        return try {
            if (!initialized) initialize()
            val models = getModels()
            if (models.isEmpty()) {
                TestResult(false, "API 返回的模型列表为空")
            } else {
                val testModel = modelName.ifBlank { models.first() }
                TestResult(true, model = testModel)
            }
        } catch (e: Exception) {
            TestResult(false, e.message)
        }
    }
}

// ==================== Provider 元信息 ====================

val OPENAI_METADATA = ProviderMetadata(
    id = "openai",
    name = "OpenAI / 兼容 API",
    description = "支持 OpenAI API 及所有兼容接口（如 DeepSeek, Moonshot, Groq, 硅基流动等）",
    configSchema = listOf(
        ProviderConfigField(
            key = "apiKey", label = "API Key", type = "password",
            required = true, placeholder = "sk-...",
            description = "API 密钥",
        ),
        ProviderConfigField(
            key = "baseUrl", label = "API Base URL", type = "string",
            default = "https://api.openai.com/v1",
            placeholder = "https://api.openai.com/v1",
            description = "兼容 API 地址（如 https://api.deepseek.com）",
        ),
        ProviderConfigField(
            key = "model", label = "模型", type = "string",
            default = "gpt-4o-mini", placeholder = "gpt-4o-mini",
            description = "要使用的模型名称",
        ),
        ProviderConfigField(
            key = "timeout", label = "超时时间（秒）", type = "number",
            default = "60", description = "请求超时时间",
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
