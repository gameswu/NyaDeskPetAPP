package com.gameswu.nyadeskpet.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gameswu.nyadeskpet.agent.CommandDefinition
import com.gameswu.nyadeskpet.agent.ConnectionState
import com.gameswu.nyadeskpet.agent.ToolConfirmData
import com.gameswu.nyadeskpet.i18n.I18nManager
import com.mikepenz.markdown.m3.Markdown

@Composable
fun ConnectionBar(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50) to I18nManager.t("topBar.connected")
        ConnectionState.CONNECTING -> Color(0xFFFFC107) to I18nManager.t("topBar.connecting")
        ConnectionState.DISCONNECTED -> Color(0xFFF44336) to I18nManager.t("topBar.disconnected")
    }
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

@Composable
fun ChatBubble(
    text: String,
    isUser: Boolean,
    reasoning: String? = null,
    attachment: String? = null,
) {
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    // 对齐原项目: 用户消息 #4CAF50 绿色, AI消息 #f0f0f0 灰色
    val bgColor = if (isUser) Color(0xFF4CAF50) else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
    var showReasoning by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .background(bgColor, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .widthIn(max = 300.dp)
        ) {
            // 折叠的推理过程
            if (!reasoning.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { showReasoning = !showReasoning },
                    color = if (isUser) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = if (showReasoning) "▼ ${I18nManager.t("chat.reasoning")}" else "▶ ${I18nManager.t("chat.reasoning")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f)
                        )
                        AnimatedVisibility(visible = showReasoning) {
                            Text(
                                text = reasoning,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // 主文本 — 使用 Markdown 渲染（对齐原项目）
            if (isUser) {
                // 用户消息纯文本即可
                Text(text = text, color = contentColor)
            } else {
                // AI 消息用 Markdown 渲染
                Markdown(
                    content = text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 附件预览
            if (!attachment.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "📎 $attachment",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isUser) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ToolConfirmDialog(
    data: ToolConfirmData,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(I18nManager.t("chat.toolConfirmTitle")) },
        text = {
            Column {
                data.toolCalls.forEach { call ->
                    Text("Tool: ${call.name}", style = MaterialTheme.typography.titleSmall)
                    call.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Args: ${call.arguments}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = onApprove) { Text(I18nManager.t("chat.approve")) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(I18nManager.t("chat.reject")) }
        }
    )
}

/**
 * 命令建议弹出层
 */
@Composable
fun CommandSuggestions(
    suggestions: List<CommandDefinition>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            suggestions.forEach { cmd ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect("/${cmd.name} ") },
                    color = Color.Transparent
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("/${cmd.name}", style = MaterialTheme.typography.bodyMedium)
                        cmd.description.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInput(
    commands: List<CommandDefinition> = emptyList(),
    text: String = "",
    onTextChange: (String) -> Unit = {},
    onSend: (String) -> Unit,
    onMicClick: () -> Unit = {},
    onCameraClick: () -> Unit = {},
    onAttachClick: () -> Unit = {},
    isMicActive: Boolean = false,
    isCameraActive: Boolean = false,
) {
    // 命令建议
    val suggestions = remember(text, commands) {
        if (text.startsWith("/") && text.length > 1) {
            val query = text.removePrefix("/").lowercase()
            commands.filter { it.name.lowercase().startsWith(query) }.take(5)
        } else emptyList()
    }

    Column {
        CommandSuggestions(
            suggestions = suggestions,
            onSelect = { onTextChange(it) }
        )

        Surface(tonalElevation = 2.dp) {
            Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                // 功能按钮行：语音、摄像头、附件
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 语音输入
                    IconButton(onClick = onMicClick) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = I18nManager.t("chatWindow.voice"),
                            tint = if (isMicActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = I18nManager.t("chatWindow.voice"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMicActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.width(8.dp))

                    // 摄像头
                    IconButton(onClick = onCameraClick) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = I18nManager.t("chatWindow.camera"),
                            tint = if (isCameraActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = I18nManager.t("chatWindow.camera"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCameraActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.width(8.dp))

                    // 发送文件
                    IconButton(onClick = onAttachClick) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = I18nManager.t("chatWindow.attach"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = I18nManager.t("chatWindow.attach"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 输入框 + 发送按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(I18nManager.t("chat.inputPlaceholder")) },
                        singleLine = false,
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                onSend(text)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = I18nManager.t("chat.send"))
                    }
                }
            }
        }
    }
}
