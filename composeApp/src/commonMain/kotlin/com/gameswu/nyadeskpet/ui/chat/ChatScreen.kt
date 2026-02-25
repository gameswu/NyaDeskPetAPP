package com.gameswu.nyadeskpet.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gameswu.nyadeskpet.agent.Attachment
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.i18n.I18nManager
import com.gameswu.nyadeskpet.ui.rememberCameraCaptureLauncher
import com.gameswu.nyadeskpet.ui.rememberDualModeVoiceInput
import com.gameswu.nyadeskpet.ui.rememberFilePickerLauncher
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val settingsRepo: SettingsRepository = koinInject()
    val settings by settingsRepo.settings.collectAsState()

    // 语音输入 — 双模式（系统识别器 / Whisper API）
    // 如果 ASR API Key 为空，尝试复用主 LLM Provider 的 API Key
    val effectiveAsrApiKey = settings.asrApiKey.ifBlank {
        val primaryId = settings.primaryLlmInstanceId
        settings.llmProviderInstances
            .firstOrNull { it.instanceId == primaryId }
            ?.config?.apiKey ?: ""
    }
    val voiceInput = rememberDualModeVoiceInput(
        asrMode = settings.asrMode,
        asrApiKey = effectiveAsrApiKey,
        asrBaseUrl = settings.asrBaseUrl,
        asrModel = settings.asrModel,
        asrLanguage = settings.asrLanguage,
        onResult = { text ->
            viewModel.onInputChanged(text)
            if (settings.micAutoSend && text.isNotBlank()) {
                viewModel.sendMessage(text)
            }
        },
        onPartialResult = { viewModel.onInputChanged(it) },
        onError = { /* 语音识别错误，静默处理 */ },
        locale = settings.locale,
    )

    // 摄像头拍照
    val cameraCaptureLauncher = rememberCameraCaptureLauncher { result ->
        if (result != null && result.bytes != null) {
            val base64Data = Base64.encode(result.bytes)
            val attachment = Attachment(
                type = "image",
                data = base64Data,
                name = result.name,
            )
            viewModel.sendMessageWithAttachment(
                text = result.name,
                attachment = attachment,
            )
        }
    }

    // 文件选择器 — 选择文件后以附件形式发送
    val filePickerLauncher = rememberFilePickerLauncher(
        mimeTypes = listOf("*/*"),
        onResult = { result ->
            if (result != null && result.bytes != null) {
                val isImage = result.mimeType?.startsWith("image/") == true
                val base64Data = Base64.encode(result.bytes)
                val attachment = Attachment(
                    type = if (isImage) "image" else "file",
                    data = base64Data,
                    name = result.name,
                )
                // 发送带附件的消息
                viewModel.sendMessageWithAttachment(
                    text = result.name,
                    attachment = attachment,
                )
            }
        }
    )

    Column(Modifier.fillMaxSize()) {
        // 对话管理头栏
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 对话列表切换按钮
                IconButton(onClick = { viewModel.toggleConversationList() }) {
                    Icon(Icons.Default.Menu, contentDescription = "Conversations")
                }
                // 当前对话标题
                val currentConv = uiState.conversations.find { it.id == uiState.currentConversationId }
                Text(
                    text = currentConv?.title ?: I18nManager.t("chat.newConversation"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                // 新对话按钮
                IconButton(onClick = { viewModel.newConversation() }) {
                    Icon(Icons.Default.Add, contentDescription = I18nManager.t("chat.newConversation"))
                }
            }
        }

        // 对话列表下拉面板
        AnimatedVisibility(visible = uiState.showConversationList) {
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    if (uiState.conversations.isEmpty()) {
                        Text(
                            text = I18nManager.t("chat.noMessages"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        uiState.conversations.forEach { conv ->
                            val isSelected = conv.id == uiState.currentConversationId
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = conv.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = remember(conv.updatedAt) {
                                            val elapsed = com.gameswu.nyadeskpet.currentTimeMillis() - conv.updatedAt
                                            val minutes = elapsed / 60_000
                                            val hours = minutes / 60
                                            val days = hours / 24
                                            when {
                                                days > 0 -> "${days}d ago"
                                                hours > 0 -> "${hours}h ago"
                                                minutes > 0 -> "${minutes}m ago"
                                                else -> "just now"
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = I18nManager.t("chat.deleteConversation"),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.switchConversation(conv.id) },
                                colors = if (isSelected) {
                                    ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    )
                                } else {
                                    ListItemDefaults.colors()
                                },
                            )
                        }
                    }
                }
            }
        }

        // 连接状态栏
        ConnectionBar(uiState.connectionState)

        // 消息列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            reverseLayout = true
        ) {
            // 流式输出中的临时消息
            if (uiState.isStreaming) {
                item {
                    ChatBubble(
                        text = uiState.streamingText.ifBlank { "..." },
                        isUser = false
                    )
                }
            }
            items(uiState.messages.reversed()) { msg ->
                ChatBubble(
                    text = msg.text,
                    isUser = msg.isUser,
                    reasoning = msg.reasoning,
                    attachment = msg.attachment?.data
                )
            }
            // 空状态
            if (uiState.messages.isEmpty() && !uiState.isStreaming) {
                item {
                    Text(
                        text = I18nManager.t("chat.noMessages"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = androidx.compose.ui.Modifier.padding(
                            horizontal = androidx.compose.ui.unit.Dp(16f),
                            vertical = androidx.compose.ui.unit.Dp(32f)
                        )
                    )
                }
            }
        }

        // 工具确认弹窗
        uiState.toolConfirm?.let { data ->
            ToolConfirmDialog(
                data = data,
                onApprove = { viewModel.respondToolConfirm(data.confirmId, true) },
                onReject = { viewModel.respondToolConfirm(data.confirmId, false) }
            )
        }

        // 输入框（含命令建议 + 语音/摄像头/附件按钮）
        ChatInput(
            commands = uiState.commandDefinitions,
            text = uiState.inputText,
            onTextChange = { viewModel.onInputChanged(it) },
            onSend = { viewModel.sendMessage(it) },
            onMicClick = {
                if (voiceInput.isListening) voiceInput.stopListening()
                else voiceInput.startListening()
            },
            onCameraClick = { cameraCaptureLauncher() },
            onAttachClick = { filePickerLauncher() },
            isMicActive = voiceInput.isListening,
            isCameraActive = false,
        )
    }
}
