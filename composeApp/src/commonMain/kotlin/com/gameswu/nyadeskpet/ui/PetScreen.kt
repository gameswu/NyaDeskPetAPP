package com.gameswu.nyadeskpet.ui

import androidx.compose.animation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameswu.nyadeskpet.agent.AgentClient
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.dialogue.DialogueManager
import com.gameswu.nyadeskpet.live2d.Live2DManager
import com.gameswu.nyadeskpet.ui.chat.ChatViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs

@Composable
fun PetScreen(viewModel: ChatViewModel = koinViewModel()) {
    val dialogueManager: DialogueManager = koinInject()
    val live2dManager: Live2DManager = koinInject()
    val settingsRepo: SettingsRepository = koinInject()
    val agentClient: AgentClient = koinInject()
    val dialogueState by dialogueManager.state.collectAsState()
    val chatState by viewModel.uiState.collectAsState()
    val settings by settingsRepo.settings.collectAsState()
    val scope = rememberCoroutineScope()

    // 模型加载：监听 modelPath 变化并自动加载，然后提取并发送模型信息
    LaunchedEffect(settings.modelPath) {
        if (settings.modelPath.isNotBlank()) {
            live2dManager.loadModel(settings.modelPath)
            // 对齐原项目：模型加载后提取模型信息并发送给 Agent
            val modelInfo = live2dManager.extractModelInfo(settings.modelPath)
            if (modelInfo != null) {
                agentClient.sendModelInfo(modelInfo)
            }
        }
    }

    // 获取模型的 HitAreas
    val hitAreas = remember(settings.modelPath) {
        if (settings.modelPath.isNotBlank()) {
            live2dManager.getModelHitAreas(settings.modelPath)
        } else emptyList()
    }

    // 画布尺寸
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // ---- 用户变换状态: 缩放 + 平移 ----
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }   // 像素
    var offsetY by remember { mutableFloatStateOf(0f) }   // 像素

    Box(modifier = Modifier.fillMaxSize()) {
        // Live2D Canvas — 综合手势：拖拽 + 缩放 + 点击
        Live2DCanvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(hitAreas) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downPos = firstDown.position
                        var totalDrag = Offset.Zero
                        var didTransform = false // 是否发生了拖拽/缩放
                        var pastTouchSlop = false
                        val touchSlop = 18f // px

                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()

                            // 累计拖拽量，用于判断是否为点击
                            totalDrag += pan

                            if (!pastTouchSlop) {
                                if (abs(totalDrag.x) > touchSlop || abs(totalDrag.y) > touchSlop || zoom != 1f) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                didTransform = true

                                // 缩放
                                if (zoom != 1f) {
                                    scale = (scale * zoom).coerceIn(0.3f, 5f)
                                }

                                // 拖拽 — 累加像素偏移
                                offsetX += pan.x
                                offsetY += pan.y

                                // 转换为 NDC 传给 native
                                if (canvasSize.width > 0 && canvasSize.height > 0) {
                                    val ndcX = offsetX / canvasSize.width * 2f
                                    val ndcY = -(offsetY / canvasSize.height * 2f)
                                    live2dManager.setModelTransform(scale, ndcX, ndcY)
                                }

                                // 消费指针防止滚动穿透
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        // 如果没有发生拖拽/缩放 → 是点击
                        if (!didTransform) {
                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                val nx = downPos.x / canvasSize.width
                                val ny = downPos.y / canvasSize.height

                                val hitArea = if (hitAreas.isNotEmpty()) {
                                    val idx = (ny * hitAreas.size).toInt().coerceIn(0, hitAreas.lastIndex)
                                    hitAreas[idx]
                                } else {
                                    when {
                                        ny < 0.35f -> "Head"
                                        else -> "Body"
                                    }
                                }
                                scope.launch { agentClient.sendTapEvent(hitArea, nx, ny) }
                            }
                        }
                    }
                }
        )

        // 对话气泡 — 优先使用 DialogueManager，回退到 ChatViewModel subtitle
        val showDialogue = dialogueState.visible && dialogueState.text.isNotBlank()
        val showSubtitle = !showDialogue && chatState.isSubtitleVisible && chatState.subtitleText.isNotBlank()

        // 气泡位置：底部居中 — 对齐原项目 #dialogue-box { bottom:20px; left:50% }
        AnimatedVisibility(
            visible = showDialogue || showSubtitle,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp, start = 24.dp, end = 24.dp)
                .widthIn(max = 320.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = if (showDialogue) dialogueState.text else chatState.subtitleText,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // 对话进度条（绿色渐变，对齐原项目 #4CAF50 → #8BC34A）
                    if (showDialogue && dialogueState.progress < 1f) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { dialogueState.progress },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Color(0xFF4CAF50),
                            trackColor = Color.Transparent,
                        )
                    }
                }
            }
        }
    }
}
