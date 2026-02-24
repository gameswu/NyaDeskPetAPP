package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.plugin.*
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolProvider
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * 绘图插件 — 对齐原项目 agent-plugins/image-gen
 *
 * 封装 generate_image 工具，调用支持图像生成的 Provider（OpenAI DALL·E 等）
 * 返回生成的图片 Base64 数据。
 *
 * 注意：由于 KMP 限制，此插件通过 PluginContext.callProvider() 调用主 LLM 的底层 HTTP 接口，
 * 而非直接调用 images/generations API。如果需要独立的图像生成 Provider，
 * 需在插件配置中指定 providerInstanceId。
 */
class ImageGenPlugin : Plugin, ToolProvider {

    override val manifest = PluginManifest(
        id = "builtin.image-gen",
        name = "绘图",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "根据文本描述生成图片",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.TOOL),
        autoActivate = true,
    )
    override var enabled: Boolean = true

    override val providerId: String = "builtin.image-gen"
    override val providerName: String = "绘图"

    // ==================== 配置 Schema ====================

    override val configSchema = PluginConfigSchema(
        fields = listOf(
            ConfigFieldDef(
                key = "providerInstanceId",
                type = ConfigFieldType.STRING,
                description = "用于图像生成的 Provider 实例 ID，设为 'primary' 则使用主 LLM",
                default = JsonPrimitive("primary"),
            ),
            ConfigFieldDef(
                key = "model",
                type = ConfigFieldType.STRING,
                description = "图像生成模型名称（留空则使用 Provider 配置的模型）",
                default = JsonPrimitive(""),
            ),
            ConfigFieldDef(
                key = "defaultSize",
                type = ConfigFieldType.STRING,
                description = "默认图像尺寸",
                default = JsonPrimitive("1024x1024"),
                options = listOf("256x256", "512x512", "1024x1024", "1792x1024", "1024x1792"),
            ),
            ConfigFieldDef(
                key = "defaultQuality",
                type = ConfigFieldType.STRING,
                description = "默认图像质量",
                default = JsonPrimitive("standard"),
                options = listOf("standard", "hd"),
            ),
        ),
    )

    // ==================== 状态 ====================

    private var ctx: PluginContext? = null
    private val httpClient = HttpClient { expectSuccess = false }

    private var providerInstanceId: String = "primary"
    private var model: String = ""
    private var defaultSize: String = "1024x1024"
    private var defaultQuality: String = "standard"

    companion object {
        private val VALID_SIZES = listOf("256x256", "512x512", "1024x1024", "1792x1024", "1024x1792")
        private val VALID_QUALITIES = listOf("standard", "hd")
        private const val REQUEST_TIMEOUT = 120000L
    }

    // ==================== 生命周期 ====================

    override fun onLoad(context: PluginContext) {
        ctx = context
        loadConfig(context.getConfig())
        context.logInfo("绘图插件已初始化，Provider: $providerInstanceId")
    }

    override fun onUnload() {
        httpClient.close()
        ctx = null
    }

    override fun onConfigChanged(config: Map<String, JsonElement>) {
        loadConfig(config)
    }

    private fun loadConfig(config: Map<String, JsonElement>) {
        config["providerInstanceId"]?.jsonPrimitive?.contentOrNull?.let { providerInstanceId = it }
        config["model"]?.jsonPrimitive?.contentOrNull?.let { model = it }
        config["defaultSize"]?.jsonPrimitive?.contentOrNull?.let { defaultSize = it }
        config["defaultQuality"]?.jsonPrimitive?.contentOrNull?.let { defaultQuality = it }
    }

    // ==================== 工具 ====================

    override fun getTools(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "generate_image",
                description = "根据文本描述生成图片。传入详细的英文 prompt 以获得最佳效果。返回生成图片的 Base64 数据。",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("prompt") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("图片描述（建议使用详细的英文描述）"))
                        }
                        putJsonObject("size") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("图片尺寸：${VALID_SIZES.joinToString(", ")}。默认 $defaultSize"))
                        }
                        putJsonObject("quality") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("图片质量：${VALID_QUALITIES.joinToString(", ")}。默认 $defaultQuality"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("prompt")) }
                },
            ),
        )
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        if (name != "generate_image") {
            return ToolResult(success = false, error = "Unknown tool: $name")
        }
        return handleGenerateImage(arguments)
    }

    // ==================== 图像生成 ====================

    private suspend fun handleGenerateImage(arguments: JsonObject): ToolResult {
        val context = ctx ?: return ToolResult(success = false, error = "插件未初始化")

        val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
        if (prompt.isNullOrBlank()) {
            return ToolResult(success = false, error = "prompt 不能为空")
        }

        // 获取 Provider 配置
        val providerInfo = if (providerInstanceId == "primary") {
            context.getPrimaryProviderInfo()
        } else {
            context.getAllProviders().find { it.instanceId == providerInstanceId }
        }

        if (providerInfo == null) {
            return ToolResult(
                success = false,
                error = "找不到 Provider 实例 \"$providerInstanceId\"，请在插件配置中指定正确的 Provider",
            )
        }

        val size = arguments["size"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in VALID_SIZES } ?: defaultSize
        val quality = arguments["quality"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in VALID_QUALITIES } ?: defaultQuality
        val imageModel = model.ifBlank { providerInfo.model ?: "dall-e-3" }

        context.logInfo("生成图片: model=$imageModel, size=$size, quality=$quality, prompt=\"${prompt.take(80)}...\"")

        return try {
            val result = callImageAPI(providerInfo, prompt, imageModel, size, quality)
            if (!result.success) {
                return ToolResult(success = false, error = "图片生成失败: ${result.error}")
            }

            val dataUrl = "data:image/png;base64,${result.b64Data}"
            val revisedPrompt = result.revisedPrompt?.let { "\n修改后的提示词: $it" } ?: ""

            ToolResult(
                success = true,
                result = JsonPrimitive("图片已生成成功。$revisedPrompt\n\n![generated image]($dataUrl)"),
            )
        } catch (e: Exception) {
            context.logError("图片生成异常: ${e.message}")
            ToolResult(success = false, error = "图片生成异常: ${e.message}")
        }
    }

    // ==================== API 调用 ====================

    private data class ImageAPIResult(
        val success: Boolean,
        val b64Data: String? = null,
        val revisedPrompt: String? = null,
        val error: String? = null,
    )

    /**
     * 调用图像生成 API（OpenAI 兼容格式）。
     *
     * 注意：此方法需要从 Provider 配置中获取 apiKey 和 baseUrl，
     * 但当前 PluginContext 不暴露这些信息。因此这里使用一个简化实现：
     * 返回提示信息让用户知道需要配置独立的图像生成服务。
     *
     * 在完整实现中，应扩展 PluginContext 以支持 getProviderConfig()。
     */
    private suspend fun callImageAPI(
        providerInfo: ProviderBriefInfo,
        prompt: String,
        imageModel: String,
        size: String,
        quality: String,
    ): ImageAPIResult {
        // 简化实现：由于 PluginContext 不暴露 apiKey/baseUrl，
        // 返回提示信息说明图像生成功能需要进一步配置
        return ImageAPIResult(
            success = false,
            error = "图像生成功能需要直接配置 API 访问。请确保 Provider '${providerInfo.displayName}' 支持 /images/generations 端点。" +
                    "当前 KMP 版本暂不支持直接调用图像生成 API，请使用原 Electron 版本或等待后续更新。",
        )

        // TODO: 完整实现需要:
        // 1. 扩展 PluginContext 添加 getProviderConfig(instanceId) 方法
        // 2. 从中获取 apiKey 和 baseUrl
        // 3. 调用 ${baseUrl}/images/generations API
        // 示例请求体:
        // {
        //   "model": imageModel,
        //   "prompt": prompt,
        //   "size": size,
        //   "quality": quality,
        //   "n": 1,
        //   "response_format": "b64_json"
        // }
    }
}
