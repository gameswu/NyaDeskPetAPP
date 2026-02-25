package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.agent.provider.ChatMessage
import com.gameswu.nyadeskpet.agent.provider.ChatMessageAttachment
import com.gameswu.nyadeskpet.agent.provider.LLMRequest
import com.gameswu.nyadeskpet.plugin.*
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolProvider
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import kotlinx.serialization.json.*

/**
 * 图片转述插件 — 对齐原项目 agent-plugins/image-transcriber/main.js
 *
 * 解决问题：当主 LLM 不支持图片模态（Vision）时，用户发送的图片无法被理解。
 *
 * 解决方案：使用一个支持 Vision 的辅助 Provider（如 GPT-4o、Claude 等）
 * 将图片转述为文字描述，然后将描述添加到对话上下文中，供主 LLM 使用。
 *
 * 功能：
 * 1. 注册 describe_image 工具 — LLM 可主动调用来描述缓存的图片
 * 2. transcribeImage() 服务 — 供 handler/core-agent 在 file_upload 时调用
 *
 * 配置：
 * - visionProviderId: 视觉 Provider 实例 ID（必须支持图片输入）
 * - autoTranscribe: 是否自动转述
 * - transcribePrompt: 转述提示词
 * - maxTokens: 最大回复 token
 */
class ImageTranscriberPlugin : Plugin, ToolProvider {

    override val manifest = PluginManifest(
        id = "builtin.image-transcriber",
        name = "Image Transcriber",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "图片转述插件 — 使用视觉 Provider 将图片描述为文字",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.TOOL),
        autoActivate = false,  // 需要用户手动激活并配置 Provider
    )

    override var enabled: Boolean = false

    override val configSchema = PluginConfigSchema(
        fields = listOf(
            ConfigFieldDef(
                key = "visionProviderId",
                type = ConfigFieldType.STRING,
                description = "视觉 Provider 实例 ID（必须支持图片输入，如 GPT-4o、Claude）",
            ),
            ConfigFieldDef(
                key = "transcribePrompt",
                type = ConfigFieldType.TEXT,
                description = "转述提示词",
                default = JsonPrimitive("请详细描述这张图片的内容，包括画面中的主要元素、文字、颜色、布局等。用中文回复。"),
            ),
            ConfigFieldDef(
                key = "maxTokens",
                type = ConfigFieldType.INT,
                description = "最大回复 token 数",
                default = JsonPrimitive(500),
            ),
            ConfigFieldDef(
                key = "autoTranscribe",
                type = ConfigFieldType.BOOL,
                description = "收到图片时是否自动转述",
                default = JsonPrimitive(true),
            ),
        )
    )

    override val providerId = "builtin.image-transcriber"
    override val providerName = "Image Transcriber"

    private var context: PluginContext? = null

    /** 视觉 Provider 实例 ID */
    private var visionProviderId = ""
    /** 转述提示词 */
    private var transcribePrompt = "请详细描述这张图片的内容，包括画面中的主要元素、文字、颜色、布局等。用中文回复。"
    /** 最大 token 数 */
    private var maxTokens = 500
    /** 是否自动转述 */
    private var autoTranscribe = true

    /**
     * 最近接收的图片缓存（供 describe_image 工具使用）
     */
    private var lastImage: CachedImage? = null

    private data class CachedImage(
        val data: String,       // Base64 编码的图片数据
        val mimeType: String,
        val fileName: String?,
    )

    override fun onLoad(context: PluginContext) {
        this.context = context
        val config = context.getConfig()
        loadConfig(config)

        if (visionProviderId.isNotBlank()) {
            context.logInfo("图片转述插件已初始化 (provider: $visionProviderId, auto: $autoTranscribe)")
        } else {
            context.logWarn("图片转述插件已初始化，但未配置 visionProviderId，图片转述功能将不可用")
        }
    }

    override fun onUnload() {
        lastImage = null
        context?.logInfo("图片转述插件已停止")
        context = null
    }

    override fun onConfigChanged(config: Map<String, JsonElement>) {
        loadConfig(config)
    }

    private fun loadConfig(config: Map<String, JsonElement>) {
        config["visionProviderId"]?.jsonPrimitive?.contentOrNull?.let { visionProviderId = it }
        config["transcribePrompt"]?.jsonPrimitive?.contentOrNull?.let { transcribePrompt = it }
        config["maxTokens"]?.jsonPrimitive?.intOrNull?.let { maxTokens = it }
        config["autoTranscribe"]?.jsonPrimitive?.booleanOrNull?.let { autoTranscribe = it }
    }

    // ==================== 服务 API ====================

    /**
     * 转述图片为文字描述
     *
     * @param base64Data Base64 编码的图片数据
     * @param mimeType 图片 MIME 类型（如 image/jpeg）
     * @param prompt 自定义提示词（可选）
     * @return 描述文字 or null（失败）
     */
    suspend fun transcribeImage(base64Data: String, mimeType: String, prompt: String? = null): TranscribeResult {
        val ctx = context ?: return TranscribeResult(false, error = "插件未初始化")

        if (visionProviderId.isBlank()) {
            return TranscribeResult(false, error = "未配置视觉 Provider (visionProviderId)")
        }

        // 验证 Provider 状态
        val providers = ctx.getAllProviders()
        val visionProvider = providers.find { it.instanceId == visionProviderId }
        if (visionProvider == null) {
            return TranscribeResult(false, error = "视觉 Provider 不存在: $visionProviderId")
        }
        if (visionProvider.status != "CONNECTED") {
            return TranscribeResult(false, error = "视觉 Provider 未连接: ${visionProvider.displayName} (${visionProvider.status})")
        }

        return try {
            val dataSize = base64Data.length / 1024
            ctx.logInfo("调用视觉 Provider 转述图片 ($mimeType, ${dataSize}KB)")

            val response = ctx.callProvider(visionProviderId, LLMRequest(
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = prompt ?: transcribePrompt,
                        attachment = ChatMessageAttachment(
                            type = "image",
                            data = base64Data,
                            mimeType = mimeType,
                        ),
                    )
                ),
                maxTokens = maxTokens,
            ))

            val description = response.text
            ctx.logInfo("图片转述完成: \"${description.take(80)}...\"")

            TranscribeResult(true, description = description)
        } catch (e: Exception) {
            ctx.logError("图片转述失败: ${e.message}")
            TranscribeResult(false, error = e.message)
        }
    }

    /**
     * 是否已配置且可用
     */
    fun isAvailable(): Boolean {
        if (visionProviderId.isBlank()) return false
        val providers = context?.getAllProviders() ?: return false
        val vp = providers.find { it.instanceId == visionProviderId }
        return vp != null && vp.status == "CONNECTED"
    }

    /**
     * 缓存图片（供 describe_image 工具使用）
     */
    fun cacheImage(base64Data: String, mimeType: String, fileName: String?) {
        lastImage = CachedImage(data = base64Data, mimeType = mimeType, fileName = fileName)
    }

    /**
     * 是否开启自动转述
     */
    fun isAutoTranscribeEnabled(): Boolean = autoTranscribe && isAvailable()

    // ==================== ToolProvider ====================

    override fun getTools(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "describe_image",
                description = "描述最近收到的图片内容。当用户提到了图片或你需要了解图片内容时使用。需要先有图片被上传。",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("detail") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("关注的重点。例如\"文字内容\"、\"人物表情\"、\"整体布局\"等。留空则进行通用描述。"))
                        }
                    }
                    putJsonArray("required") {}
                },
            )
        )
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        if (name != "describe_image") {
            return ToolResult(success = false, error = "未知工具: $name")
        }

        val cached = lastImage
        if (cached == null) {
            return ToolResult(
                success = false,
                result = JsonPrimitive("当前没有缓存的图片。请等用户上传图片后再试。"),
            )
        }

        val detail = arguments["detail"]?.jsonPrimitive?.contentOrNull
        val prompt = if (!detail.isNullOrBlank()) {
            "$transcribePrompt\n\n请特别关注: $detail"
        } else {
            transcribePrompt
        }

        val result = transcribeImage(cached.data, cached.mimeType, prompt)

        return if (result.success && result.description != null) {
            ToolResult(
                success = true,
                result = JsonPrimitive("图片描述（${cached.fileName ?: "未知文件"}）:\n\n${result.description}"),
            )
        } else {
            ToolResult(
                success = false,
                result = JsonPrimitive("图片描述失败: ${result.error}"),
            )
        }
    }

    // ==================== 结果类型 ====================

    data class TranscribeResult(
        val success: Boolean,
        val description: String? = null,
        val error: String? = null,
    )
}
