package com.gameswu.nyadeskpet.agent

import kotlinx.serialization.*
import kotlinx.serialization.json.*

// ===== 顶层消息 =====
@Serializable
data class BackendMessage(
    val type: String,
    val data: JsonElement? = null,     // 不同 type 对应不同结构，先解为 JsonElement
    val text: String? = null,
    val timestamp: Long? = null,
    val responseId: String? = null,
    val priority: Int? = null,
    val attachment: Attachment? = null
)

@Serializable
data class Attachment(
    val type: String,              // "image" | "file"
    val url: String? = null,
    val data: String? = null,      // Base64
    val source: String? = null,
    val name: String? = null
)

// ===== 对话 =====
@Serializable
data class DialogueData(
    val text: String,
    val duration: Long? = null,
    val reasoningContent: String? = null,
    val attachment: Attachment? = null
)

@Serializable
data class DialogueStreamStartData(val streamId: String)

@Serializable
data class DialogueStreamChunkData(
    val streamId: String,
    val delta: String? = null,
    val reasoningDelta: String? = null
)

@Serializable
data class DialogueStreamEndData(
    val streamId: String,
    val fullText: String? = null,
    val duration: Long? = null
)

// ===== Live2D 控制 =====
@Serializable
data class Live2DCommandData(
    val command: String,           // "motion" | "expression" | "parameter"
    val group: String? = null,
    val index: Int? = null,
    val priority: Int? = null,
    val expressionId: String? = null,
    val parameterId: String? = null,
    val value: Float? = null,
    val weight: Float? = null,
    val parameters: List<ParameterSet>? = null
)

@Serializable
data class ParameterSet(
    val id: String,
    val value: Float,
    val blend: Float? = null,
    val duration: Long? = null
)

// ===== 音频流 =====
@Serializable
data class AudioStreamStartData(
    val mimeType: String,
    val totalDuration: Long? = null,
    val text: String? = null,
    val timeline: List<TimelineItem>? = null
)

@Serializable
data class AudioChunkData(
    val chunk: String,      // Base64
    val sequence: Int
)

@Serializable
data class AudioStreamEndData(val complete: Boolean = true)

@Serializable
data class TimelineItem(
    val timing: JsonPrimitive,     // String("start") 或 Int(25)
    val action: String,
    val group: String? = null,
    val index: Int? = null,
    val expressionId: String? = null,
    val parameters: List<ParameterSet>? = null
)

// ===== 同步指令 =====
@Serializable
data class SyncCommandData(val actions: List<SyncAction>)

@Serializable
data class SyncAction(
    val type: String,              // "motion" | "expression" | "dialogue" | "parameter"
    val waitComplete: Boolean? = null,
    val group: String? = null,
    val index: Int? = null,
    val expressionId: String? = null,
    val text: String? = null,
    val duration: Long? = null,
    val parameters: List<ParameterSet>? = null,
    val parameterId: String? = null,
    val value: Float? = null,
    val weight: Float? = null
)

// ===== 工具确认 =====
@Serializable
data class ToolConfirmData(
    val confirmId: String,
    val toolCalls: List<ToolCallInfo>,
    val timeout: Long
)

@Serializable
data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val source: String,
    val description: String? = null
)

@Serializable
data class ToolConfirmResponseData(
    val confirmId: String,
    val approved: Boolean,
    val remember: Boolean? = null
)

// ===== 指令 =====
@Serializable
data class CommandDefinition(
    val name: String,
    val description: String,
    val params: List<CommandParam>? = null,
    val category: String? = null,
    val enabled: Boolean? = null
)

@Serializable
data class CommandParam(
    val name: String,
    val description: String,
    val type: String,
    val required: Boolean? = null,
    @SerialName("default") val defaultValue: JsonElement? = null,
    val choices: List<CommandChoice>? = null
)

@Serializable
data class CommandChoice(val name: String, val value: JsonPrimitive)

@Serializable
data class CommandsRegisterData(val commands: List<CommandDefinition>)

@Serializable
data class CommandResponseData(
    val command: String,
    val success: Boolean,
    val text: String? = null,
    val error: String? = null
)

// ===== 模型信息 =====
@Serializable
data class ModelInfo(
    val available: Boolean,
    val modelPath: String,
    val dimensions: Dimensions,
    val motions: Map<String, MotionGroup>,
    val expressions: List<String>,
    val hitAreas: List<String>,
    val availableParameters: List<ParameterInfo>,
    val parameters: ScaleInfo,
    val mappedParameters: List<MappedParameter>? = null,
    val mappedExpressions: List<MappedExpression>? = null,
    val mappedMotions: List<MappedMotion>? = null
)

@Serializable data class Dimensions(val width: Int, val height: Int)
@Serializable data class MotionGroup(val count: Int, val files: List<String>)
@Serializable data class ParameterInfo(val id: String, val value: Float, val min: Float, val max: Float, val default: Float)
@Serializable data class ScaleInfo(val canScale: Boolean, val currentScale: Float, val userScale: Float, val baseScale: Float)
@Serializable data class MappedParameter(val id: String, val alias: String, val description: String, val min: Float, val max: Float, val default: Float)
@Serializable data class MappedExpression(val id: String, val alias: String, val description: String)
@Serializable data class MappedMotion(val group: String, val index: Int, val alias: String, val description: String)

// ===== 角色/插件 =====
@Serializable
data class CharacterInfo(
    val useCustom: Boolean,
    val name: String? = null,
    val personality: String? = null
)

// ===== 触碰事件 =====
@Serializable
data class TapEventData(
    val hitArea: String,
    val position: TapPosition,
    val timestamp: Long
)

@Serializable
data class TapPosition(val x: Float, val y: Float)

// ===== 文件上传 =====
@Serializable
data class FileUploadData(
    val fileName: String,
    val fileType: String,
    val fileSize: Long,
    val fileData: String,   // Base64
    val timestamp: Long
)

// ===== 插件 =====
@Serializable
data class PluginStatusData(
    val plugins: List<PluginEntry>
) {
    @Serializable
    data class PluginEntry(val pluginId: String, val pluginName: String, val capabilities: List<String>)
}

@Serializable
data class PluginInvokeData(
    val requestId: String,
    val pluginId: String,
    val action: String,
    val params: JsonObject? = null,
    val timeout: Long? = null
)

@Serializable
data class PluginResponseData(
    val pluginId: String,
    val requestId: String,
    val success: Boolean,
    val action: String,
    val result: JsonElement? = null,
    val error: String? = null,
    val timestamp: Long? = null
)

// ===== 工具状态 =====
@Serializable
data class ToolStatusData(
    val iteration: Int,
    val calls: List<ToolCallRef>,
    val results: List<ToolResult>
) {
    @Serializable data class ToolCallRef(val name: String, val id: String)
    @Serializable data class ToolResult(val id: String, val success: Boolean)
}