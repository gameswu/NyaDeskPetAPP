package com.gameswu.nyadeskpet.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gameswu.nyadeskpet.agent.*
import com.gameswu.nyadeskpet.agent.mcp.McpManager
import com.gameswu.nyadeskpet.agent.mcp.McpServerConfig
import com.gameswu.nyadeskpet.agent.provider.*
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.i18n.I18nManager
import com.gameswu.nyadeskpet.plugin.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.koin.compose.koinInject
import kotlin.random.Random

/**
 * Agent 管理面板 — 5 标签页（概览 / 工具 / 插件 / 指令 / 技能）
 * 对齐原 Electron 项目 agent-panel 的 UI 结构
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPanelScreen() {
    val agentClient: AgentClient = koinInject()
    val connState by agentClient.connectionState.collectAsState()
    val commands by agentClient.commandRegistrations.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        TabItem(I18nManager.t("agent.tabs.overview"), Icons.Default.Dashboard),
        TabItem(I18nManager.t("agent.tabs.tools"), Icons.Default.Build),
        TabItem(I18nManager.t("agent.tabs.plugins"), Icons.Default.Extension),
        TabItem(I18nManager.t("agent.tabs.commands"), Icons.Default.Terminal),
        TabItem(I18nManager.t("agent.tabs.skills"), Icons.Default.AutoAwesome),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.title, maxLines = 1) },
                        icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.size(18.dp)) },
                    )
                }
            }

            when (selectedTab) {
                0 -> OverviewTab(connState, agentClient, snackbarHostState)
                1 -> ToolsTab()
                2 -> PluginsTab()
                3 -> CommandsTab(commands)
                4 -> SkillsTab()
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

private data class TabItem(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

// ==================== 概览标签页 ====================

@Composable
private fun OverviewTab(connState: ConnectionState, agentClient: AgentClient, snackbarHostState: SnackbarHostState) {
    val settingsRepo: SettingsRepository = koinInject()
    val builtinAgent: BuiltinAgentService = koinInject()
    val settings by settingsRepo.settings.collectAsState()
    val instances by builtinAgent.providerInstancesFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingInstance by remember { mutableStateOf<ProviderInstanceInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ===== 服务状态卡片 =====
        SectionHeader(I18nManager.t("agent.serverStatus"), Icons.Default.Dns)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusRow(I18nManager.t("agent.status")) {
                    val (icon, text, color) = when (connState) {
                        ConnectionState.CONNECTED -> Triple(Icons.Default.CheckCircle, I18nManager.t("agent.running"), MaterialTheme.colorScheme.primary)
                        ConnectionState.CONNECTING -> Triple(Icons.Default.Refresh, I18nManager.t("topBar.connecting"), MaterialTheme.colorScheme.tertiary)
                        ConnectionState.DISCONNECTED -> Triple(Icons.Default.Cancel, I18nManager.t("agent.stopped"), MaterialTheme.colorScheme.error)
                    }
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
                }
                StatusRow(I18nManager.t("agent.address")) {
                    val addr = if (agentClient.isBuiltinMode) "内置模式" else settings.wsUrl
                    Text(addr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusRow("模式") {
                    Text(
                        if (agentClient.isBuiltinMode) "Builtin" else "Custom",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 启动 / 停止按钮
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connState == ConnectionState.DISCONNECTED) {
                Button(onClick = { agentClient.connect(settings.wsUrl) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18nManager.t("agent.start"))
                }
            } else {
                OutlinedButton(onClick = { agentClient.disconnect() }) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18nManager.t("agent.stop"))
                }
            }
        }

        // ===== LLM Provider 实例区 =====
        SectionHeader(I18nManager.t("agent.providers"), Icons.Default.Psychology)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18nManager.t("agent.addProvider"))
                    }
                }

                if (instances.isEmpty()) {
                    EmptyHint(I18nManager.t("agent.noProviders"))
                } else {
                    instances.forEach { info ->
                        ProviderInstanceCard(
                            info = info,
                            onSetPrimary = {
                                builtinAgent.setPrimaryProvider(info.instanceId)
                            },
                            onToggleEnabled = {
                                coroutineScope.launch {
                                    if (info.enabled) {
                                        builtinAgent.disableProviderInstance(info.instanceId)
                                    } else {
                                        builtinAgent.enableProviderInstance(info.instanceId)
                                    }
                                }
                            },
                            onEdit = { editingInstance = info },
                            onDelete = {
                                builtinAgent.removeProviderInstance(info.instanceId)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = "已删除 \"${info.displayName}\"",
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // ===== TTS Provider 区 =====
        SectionHeader(I18nManager.t("agent.ttsProviders"), Icons.Default.VolumeUp)
        var showAddTtsDialog by remember { mutableStateOf(false) }
        val ttsInstances by builtinAgent.ttsInstancesFlow.collectAsState()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = { showAddTtsDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18nManager.t("agent.addTts"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (ttsInstances.isEmpty()) {
                    EmptyHint(I18nManager.t("agent.noProviders"))
                } else {
                    ttsInstances.forEach { cfg ->
                        val isPrimary = cfg.instanceId == builtinAgent.primaryTtsId
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isPrimary)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(cfg.displayName, style = MaterialTheme.typography.titleSmall)
                                        if (isPrimary) {
                                            AssistChip(onClick = {}, label = { Text("主要", style = MaterialTheme.typography.labelSmall) })
                                        }
                                    }
                                    Text(
                                        TTSProviderRegistry.get(cfg.providerId)?.name ?: cfg.providerId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (!isPrimary) {
                                        IconButton(onClick = { builtinAgent.setPrimaryTts(cfg.instanceId) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Star, contentDescription = "设为主要", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(
                                        onClick = { builtinAgent.removeTtsInstance(cfg.instanceId) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ===== 添加 TTS Provider 对话框 =====
        if (showAddTtsDialog) {
            AddTtsProviderDialog(
                onDismiss = { showAddTtsDialog = false },
                onSave = { ttsConfig ->
                    builtinAgent.addTtsInstance(ttsConfig)
                    showAddTtsDialog = false
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "TTS \"${ttsConfig.displayName}\" 已添加",
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
            )
        }
    }

    // ===== 添加 LLM Provider 对话框 =====
    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onSave = { instanceConfig ->
                builtinAgent.addProviderInstance(instanceConfig)
                showAddDialog = false
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Provider \"${instanceConfig.displayName}\" 已添加",
                        duration = SnackbarDuration.Short,
                    )
                    // 如果标记了 enabled，自动初始化
                    if (instanceConfig.enabled) {
                        builtinAgent.enableProviderInstance(instanceConfig.instanceId)
                    }
                }
            },
        )
    }

    // ===== 编辑 LLM Provider 对话框 =====
    editingInstance?.let { info ->
        EditProviderDialog(
            info = info,
            onDismiss = { editingInstance = null },
            onSave = { newConfig ->
                builtinAgent.updateProviderInstance(info.instanceId, newConfig)
                editingInstance = null
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Provider \"${newConfig.displayName}\" 已更新",
                        duration = SnackbarDuration.Short,
                    )
                }
            },
        )
    }
}

// ==================== Provider 实例卡片 ====================

@Composable
private fun ProviderInstanceCard(
    info: ProviderInstanceInfo,
    onSetPrimary: () -> Unit,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusColor = when (info.status) {
        ProviderStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        ProviderStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ProviderStatus.ERROR -> MaterialTheme.colorScheme.error
        ProviderStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (info.status) {
        ProviderStatus.CONNECTED -> "已连接"
        ProviderStatus.CONNECTING -> "连接中..."
        ProviderStatus.ERROR -> "错误"
        ProviderStatus.IDLE -> "未连接"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (info.isPrimary)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(info.displayName, style = MaterialTheme.typography.titleSmall)
                        if (info.isPrimary) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Primary",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(
                        "${info.metadata?.name ?: info.providerId} · ${info.config.model ?: "未设模型"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(8.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                        )
                        if (info.error != null) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                info.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                            )
                        }
                    }
                    // API Key 掩码
                    val apiKeyDisplay = info.config.apiKey?.let { key ->
                        if (key.isNotBlank()) "API Key: ****${key.takeLast(4)}" else "API Key: 未设置"
                    } ?: "API Key: 未设置"
                    Text(
                        apiKeyDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (info.config.apiKey.isNullOrBlank())
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 操作按钮
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        if (!info.isPrimary) {
                            IconButton(onClick = onSetPrimary) {
                                Icon(Icons.Default.StarBorder, "Set Primary", modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete, "Delete",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    // 启用/禁用开关
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (info.enabled) "已启用" else "已禁用",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = info.enabled,
                            onCheckedChange = { onToggleEnabled() },
                        )
                    }
                }
            }
        }
    }
}

// ==================== 添加 Provider 对话框 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onSave: (ProviderInstanceConfig) -> Unit,
) {
    val availableTypes = remember { ProviderRegistry.getAll() }
    var selectedTypeIndex by remember { mutableIntStateOf(0) }
    var displayName by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var enableOnCreate by remember { mutableStateOf(true) }
    var typeExpanded by remember { mutableStateOf(false) }

    // 当切换 Provider 类型时，更新默认值
    val selectedType = availableTypes.getOrNull(selectedTypeIndex)
    LaunchedEffect(selectedTypeIndex) {
        if (selectedType != null) {
            if (displayName.isBlank()) displayName = selectedType.name
            val schema = selectedType.configSchema
            baseUrl = schema.find { it.key == "baseUrl" }?.default ?: ""
            model = schema.find { it.key == "model" }?.default ?: ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 LLM Provider") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Provider 类型选择 — 下拉框（对齐原项目 <select>）
                Text("Provider 类型", style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = selectedType?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        availableTypes.forEachIndexed { index, meta ->
                            DropdownMenuItem(
                                text = { Text(meta.name) },
                                onClick = {
                                    selectedTypeIndex = index
                                    displayName = meta.name
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                // 描述
                selectedType?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()

                // 显示名称
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名称") },
                    placeholder = { Text("My Provider") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )

                // Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API Base URL") },
                    placeholder = { Text(selectedType?.configSchema?.find { it.key == "baseUrl" }?.placeholder ?: "https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Model
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    placeholder = { Text(selectedType?.configSchema?.find { it.key == "model" }?.placeholder ?: "gpt-4o-mini") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 创建后立即启用
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("创建后自动启用", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enableOnCreate, onCheckedChange = { enableOnCreate = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val providerId = selectedType?.id ?: "openai"
                    val instanceId = generateInstanceId()
                    onSave(
                        ProviderInstanceConfig(
                            instanceId = instanceId,
                            providerId = providerId,
                            displayName = displayName.ifBlank { selectedType?.name ?: "Unnamed" },
                            config = ProviderConfig(
                                id = instanceId,
                                name = displayName.ifBlank { selectedType?.name ?: "Unnamed" },
                                apiKey = apiKey.takeIf { it.isNotBlank() },
                                baseUrl = baseUrl.takeIf { it.isNotBlank() },
                                model = model.takeIf { it.isNotBlank() },
                            ),
                            enabled = enableOnCreate,
                        )
                    )
                },
                enabled = displayName.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ==================== 编辑 Provider 对话框 ====================

@Composable
private fun EditProviderDialog(
    info: ProviderInstanceInfo,
    onDismiss: () -> Unit,
    onSave: (ProviderInstanceConfig) -> Unit,
) {
    var displayName by remember { mutableStateOf(info.displayName) }
    var apiKey by remember { mutableStateOf(info.config.apiKey ?: "") }
    var baseUrl by remember { mutableStateOf(info.config.baseUrl ?: "") }
    var model by remember { mutableStateOf(info.config.model ?: "") }
    var showApiKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 Provider") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Provider 类型（不可修改）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("类型: ", style = MaterialTheme.typography.labelMedium)
                    Text(
                        info.metadata?.name ?: info.providerId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ProviderInstanceConfig(
                            instanceId = info.instanceId,
                            providerId = info.providerId,
                            displayName = displayName.ifBlank { info.displayName },
                            config = ProviderConfig(
                                id = info.config.id,
                                name = displayName.ifBlank { info.displayName },
                                apiKey = apiKey.takeIf { it.isNotBlank() },
                                baseUrl = baseUrl.takeIf { it.isNotBlank() },
                                model = model.takeIf { it.isNotBlank() },
                            ),
                            enabled = info.enabled,
                        )
                    )
                },
                enabled = displayName.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ==================== 添加 TTS Provider 对话框 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTtsProviderDialog(
    onDismiss: () -> Unit,
    onSave: (TTSProviderInstanceConfig) -> Unit,
) {
    val availableTypes = remember { TTSProviderRegistry.getAll() }
    var selectedTypeIndex by remember { mutableIntStateOf(0) }
    var displayName by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }

    val selectedType = availableTypes.getOrNull(selectedTypeIndex)
    LaunchedEffect(selectedTypeIndex) {
        if (selectedType != null) {
            if (displayName.isBlank()) displayName = selectedType.name
            val schema = selectedType.configSchema
            baseUrl = schema.find { it.key == "baseUrl" }?.default ?: ""
            model = schema.find { it.key == "model" }?.default ?: ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 TTS Provider") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // TTS Provider 类型选择 — 下拉框
                Text("Provider 类型", style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = selectedType?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        availableTypes.forEachIndexed { index, meta ->
                            DropdownMenuItem(
                                text = { Text(meta.name) },
                                onClick = {
                                    selectedTypeIndex = index
                                    displayName = meta.name
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                selectedType?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()

                // 显示名称
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )

                // Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API Base URL") },
                    placeholder = { Text(selectedType?.configSchema?.find { it.key == "baseUrl" }?.placeholder ?: "") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Model / Voice
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型 / 语音") },
                    placeholder = { Text(selectedType?.configSchema?.find { it.key == "model" }?.placeholder ?: "") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val providerId = selectedType?.id ?: return@TextButton
                    val instanceId = generateInstanceId()
                    onSave(
                        TTSProviderInstanceConfig(
                            instanceId = instanceId,
                            providerId = providerId,
                            displayName = displayName.ifBlank { selectedType.name },
                            config = ProviderConfig(
                                id = instanceId,
                                name = displayName.ifBlank { selectedType.name },
                                apiKey = apiKey.takeIf { it.isNotBlank() },
                                baseUrl = baseUrl.takeIf { it.isNotBlank() },
                                model = model.takeIf { it.isNotBlank() },
                            ),
                            enabled = true,
                        ),
                    )
                },
                enabled = displayName.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun generateInstanceId(): String = buildString {
    append("inst-")
    repeat(12) { append("0123456789abcdef"[Random.nextInt(16)]) }
}

// ==================== 工具标签页 ====================

@Composable
private fun ToolsTab() {
    val pluginManager: PluginManager = koinInject()
    val toolProviders by pluginManager.toolProviders.collectAsState()
    val toolEnabledVersion by pluginManager.toolEnabledVersion.collectAsState()

    // 使用 getAllToolsWithSource 获取工具及其来源插件（不过滤禁用工具，供 UI 展示）
    val toolsWithSource = remember(toolProviders, toolEnabledVersion) {
        pluginManager.getAllToolsWithSource()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(I18nManager.t("agent.tools.title"), Icons.Default.Build)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val enabledCount = toolsWithSource.count { pluginManager.isToolEnabled(it.first.name) }
                    Text(
                        I18nManager.t("agent.tools.count").replace("%d", "${toolsWithSource.size}") +
                            if (enabledCount < toolsWithSource.size) "（${enabledCount} 个已启用）" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { /* 刷新工具列表 */ }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }

                if (toolsWithSource.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    EmptyHint(I18nManager.t("agent.tools.empty"))
                } else {
                    Spacer(Modifier.height(8.dp))
                    toolsWithSource.forEach { (tool, _) ->
                        val isEnabled = pluginManager.isToolEnabled(tool.name)
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                tool.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (isEnabled)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            )
                                            if (tool.requireConfirm) {
                                                AssistChip(
                                                    onClick = {},
                                                    label = {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(12.dp))
                                                            Text("需确认", style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                        Text(
                                            tool.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        // 显示参数信息
                                        tool.parameters?.let { params ->
                                            val properties = params["properties"]
                                            if (properties != null) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    "参数: ${properties.jsonObject.keys.joinToString(", ")}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                )
                                            }
                                        }
                                    }
                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { enabled ->
                                            pluginManager.setToolEnabled(tool.name, enabled)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionHeader(I18nManager.t("agent.mcp.title"), Icons.Default.Cable)
        McpServerSection()
    }
}

// ==================== MCP 服务器管理 ====================

/**
 * MCP 服务器管理区域 — 对齐原项目 MCP 管理 UI
 *
 * 功能：
 * - 服务器列表（状态指示灯、工具数量、连接/断开/删除按钮）
 * - 添加新服务器对话框（名称、URL、描述、自动连接、请求头）
 */
@Composable
private fun McpServerSection() {
    val mcpManager: McpManager = koinInject()
    val serverConfigs by mcpManager.serverConfigs.collectAsState()
    val serverStatuses by mcpManager.serverStatuses.collectAsState()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var connectingServer by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (serverConfigs.isEmpty()) {
                EmptyHint(I18nManager.t("agent.mcp.noServers"))
                Spacer(Modifier.height(12.dp))
            } else {
                serverConfigs.forEach { config ->
                    val status = serverStatuses[config.name]
                    val isConnected = status?.connected == true
                    val isConnecting = connectingServer == config.name

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // 第一行：名称 + 状态指示 + 操作按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // 状态指示灯
                                    val statusColor = when {
                                        isConnecting -> MaterialTheme.colorScheme.tertiary
                                        isConnected -> MaterialTheme.colorScheme.primary
                                        status?.error != null -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                    Surface(
                                        modifier = Modifier.size(10.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = statusColor,
                                    ) {}

                                    Text(
                                        config.name,
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    // 工具数量标签
                                    if (isConnected && (status?.toolCount ?: 0) > 0) {
                                        AssistChip(
                                            onClick = {},
                                            label = {
                                                Text(
                                                    "${status?.toolCount} ${I18nManager.t("agent.mcp.toolCount")}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                }

                                // 操作按钮
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (isConnecting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else if (isConnected) {
                                        // 断开按钮
                                        IconButton(
                                            onClick = {
                                                mcpManager.disconnectServer(config.name)
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.LinkOff,
                                                contentDescription = I18nManager.t("agent.mcp.disconnect"),
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    } else {
                                        // 连接按钮
                                        IconButton(
                                            onClick = {
                                                connectingServer = config.name
                                                scope.launch {
                                                    try {
                                                        mcpManager.connectServer(config.name)
                                                    } catch (_: Exception) {
                                                        // 错误已在 status 中体现
                                                    } finally {
                                                        connectingServer = null
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Link,
                                                contentDescription = I18nManager.t("agent.mcp.connect"),
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }

                                    // 删除按钮
                                    IconButton(
                                        onClick = { mcpManager.removeServerConfig(config.name) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = I18nManager.t("agent.mcp.delete"),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }

                            // URL + 描述
                            Text(
                                config.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (config.description.isNotBlank()) {
                                Text(
                                    config.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }

                            // 错误信息
                            status?.error?.let { error ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // 添加按钮
            FilledTonalButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18nManager.t("agent.mcp.add"))
            }
        }
    }

    // 添加/编辑对话框
    if (showAddDialog || editingServer != null) {
        McpAddServerDialog(
            initial = editingServer,
            onDismiss = {
                showAddDialog = false
                editingServer = null
            },
            onSave = { config ->
                if (editingServer != null) {
                    mcpManager.updateServerConfig(editingServer!!.name, config)
                } else {
                    mcpManager.addServerConfig(config)
                }
                showAddDialog = false
                editingServer = null
            },
        )
    }
}

/**
 * MCP 添加/编辑服务器对话框
 * 对齐原项目 MCP 添加表单（仅保留 SSE 网络传输相关字段）
 */
@Composable
private fun McpAddServerDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var autoStart by remember { mutableStateOf(initial?.autoStart ?: false) }
    var headersText by remember {
        mutableStateOf(
            initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial != null) I18nManager.t("agent.mcp.edit")
                else I18nManager.t("agent.mcp.add")
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(I18nManager.t("agent.mcp.name")) },
                    placeholder = { Text("my-mcp-server") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(I18nManager.t("agent.mcp.url")) },
                    placeholder = { Text("http://localhost:3001/sse") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(I18nManager.t("agent.mcp.description")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text(I18nManager.t("agent.mcp.headers")) },
                    placeholder = { Text("Authorization: Bearer xxx") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = autoStart, onCheckedChange = { autoStart = it })
                    Text(
                        I18nManager.t("agent.mcp.autoStart"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || url.isBlank()) return@TextButton

                    // 解析 headers（每行 "Key: Value" 格式）
                    val headers = headersText.lines()
                        .filter { it.contains(":") }
                        .associate {
                            val idx = it.indexOf(":")
                            it.substring(0, idx).trim() to it.substring(idx + 1).trim()
                        }

                    onSave(McpServerConfig(
                        name = name.trim(),
                        url = url.trim(),
                        description = description.trim(),
                        autoStart = autoStart,
                        enabled = true,
                        headers = headers,
                    ))
                },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) {
                Text(I18nManager.t("agent.mcp.save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(I18nManager.t("agent.mcp.cancel"))
            }
        },
    )
}

// ==================== 插件标签页 ====================

@Composable
private fun PluginsTab() {
    val pluginManager: PluginManager = koinInject()
    val plugins by pluginManager.plugins.collectAsState()
    var configuringPluginId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            I18nManager.t("agent.plugins.description"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                // 刷新触发 StateFlow 更新
                plugins.values.forEach { /* noop, just force recomposition */ }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18nManager.t("agent.plugins.refresh"))
            }
        }

        // 统计
        val activeCount = plugins.values.count { it.enabled }
        Text(
            "共 ${plugins.size} 个插件，${activeCount} 个已激活",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (plugins.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyHint(I18nManager.t("agent.plugins.empty"))
                }
            }
        } else {
            plugins.values.forEach { plugin ->
                val statusColor = when (plugin.status) {
                    com.gameswu.nyadeskpet.plugin.PluginStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    com.gameswu.nyadeskpet.plugin.PluginStatus.LOADED -> MaterialTheme.colorScheme.tertiary
                    com.gameswu.nyadeskpet.plugin.PluginStatus.ERROR -> MaterialTheme.colorScheme.error
                    com.gameswu.nyadeskpet.plugin.PluginStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val statusText = when (plugin.status) {
                    com.gameswu.nyadeskpet.plugin.PluginStatus.ACTIVE -> "运行中"
                    com.gameswu.nyadeskpet.plugin.PluginStatus.LOADED -> "已加载"
                    com.gameswu.nyadeskpet.plugin.PluginStatus.ERROR -> "错误"
                    com.gameswu.nyadeskpet.plugin.PluginStatus.DISABLED -> "已禁用"
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                // 名称 + 状态指示
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Circle,
                                        contentDescription = null,
                                        tint = statusColor,
                                        modifier = Modifier.size(8.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(plugin.manifest.name, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor,
                                    )
                                }
                                val toolCount = if (plugin is com.gameswu.nyadeskpet.plugin.api.ToolProvider) {
                                    plugin.getTools().size
                                } else 0
                                Text(
                                    buildString {
                                        append("v${plugin.manifest.version} · ${plugin.manifest.author.ifBlank { "内置" }}")
                                        if (toolCount > 0) append(" · $toolCount 个工具")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (plugin.manifest.description.isNotBlank()) {
                                    Text(
                                        plugin.manifest.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    )
                                }
                            }
                            Switch(
                                checked = plugin.enabled,
                                onCheckedChange = { enabled ->
                                    pluginManager.setPluginEnabled(plugin.manifest.id, enabled)
                                },
                            )
                        }

                        // 操作按钮行：配置 / 重载 / 清除数据
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 配置按钮（仅有 configSchema 的插件显示）
                            if (plugin.configSchema != null && plugin.configSchema!!.fields.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { configuringPluginId = plugin.manifest.id },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("配置", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            // 重载按钮
                            OutlinedButton(
                                onClick = { pluginManager.reloadPlugin(plugin.manifest.id) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("重载", style = MaterialTheme.typography.labelSmall)
                            }

                            // 清除数据按钮 — 对齐原项目 clear-data
                            if (pluginManager.hasPluginData(plugin.manifest.id)) {
                                OutlinedButton(
                                    onClick = { pluginManager.clearPluginData(plugin.manifest.id) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(I18nManager.t("agent.plugins.clearData"), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ===== 插件配置对话框 =====
    configuringPluginId?.let { pluginId ->
        val plugin = plugins[pluginId]
        if (plugin != null && plugin.configSchema != null) {
            PluginConfigDialog(
                plugin = plugin,
                pluginManager = pluginManager,
                onDismiss = { configuringPluginId = null },
            )
        }
    }
}

// ==================== 指令标签页 ====================

@Composable
private fun CommandsTab(commands: List<CommandDefinition>) {
    val pluginManager: PluginManager = koinInject()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(I18nManager.t("agent.commands.title"), Icons.Default.Terminal)

        Text(
            I18nManager.t("agent.commands.description"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val enabledCount = commands.count { it.enabled != false }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "共 ${commands.size} 个指令，${enabledCount} 个已启用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { /* refresh */ }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }

        if (commands.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyHint(I18nManager.t("agent.commands.empty"))
                }
            }
        } else {
            commands.forEach { cmd ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "/${cmd.name}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (cmd.enabled != false)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            // 启用/禁用开关
                            Switch(
                                checked = cmd.enabled != false,
                                onCheckedChange = { enabled ->
                                    pluginManager.setCommandEnabled(cmd.name, enabled)
                                },
                            )
                        }
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        cmd.params?.let { params ->
                            Spacer(Modifier.height(4.dp))
                            params.forEach { p ->
                                Text(
                                    "  ${p.name}: ${p.type}${if (p.required == true) " *" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 技能标签页 ====================

@Composable
private fun SkillsTab() {
    val builtinAgent: BuiltinAgentService = koinInject()
    val skills by builtinAgent.skillManager.skillsFlow.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 文件选择器 — 导入技能 zip
    var importMessage by remember { mutableStateOf<String?>(null) }
    val openFilePicker = com.gameswu.nyadeskpet.ui.rememberFilePickerLauncher(
        mimeTypes = listOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
        onResult = { result ->
            if (result?.bytes != null) {
                val (info, error) = builtinAgent.skillManager.importFromZip(result.bytes)
                importMessage = if (info != null) {
                    "成功导入技能: ${info.name}"
                } else {
                    "导入失败: ${error ?: "未知错误"}"
                }
            }
        },
    )

    // 显示导入结果 Snackbar
    LaunchedEffect(importMessage) {
        importMessage?.let {
            snackbarHostState.showSnackbar(it)
            importMessage = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(I18nManager.t("agent.skills.title"), Icons.Default.AutoAwesome)

            Text(
                I18nManager.t("agent.skills.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val enabledCount = skills.count { it.enabled }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    I18nManager.t("agent.skills.count").replace("%d", "${skills.size}") +
                        if (enabledCount < skills.size) "（${enabledCount} 个已启用）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 导入技能按钮
                    IconButton(onClick = { openFilePicker() }) {
                        Icon(Icons.Default.Add, contentDescription = "导入技能", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        // 触发刷新（重新读取 skillsFlow）
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }

        if (skills.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyHint(I18nManager.t("agent.skills.empty"))
                }
            }
        } else {
            skills.forEach { skill ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        skill.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (skill.enabled)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(skill.category, style = MaterialTheme.typography.labelSmall)
                                        },
                                    )
                                }
                                Text(
                                    skill.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (skill.parameterNames.isNotEmpty()) {
                                    Text(
                                        "参数: ${skill.parameterNames.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                                if (skill.exampleCount > 0) {
                                    Text(
                                        "${skill.exampleCount} 个示例",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            Switch(
                                checked = skill.enabled,
                                onCheckedChange = { enabled ->
                                    builtinAgent.skillManager.setEnabled(skill.name, enabled)
                                },
                            )
                        }
                    }
                }
            }
        }
        }

        // Snackbar 用于显示导入结果
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ==================== 公共组件 ====================

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider()
}

@Composable
private fun StatusRow(label: String, value: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, content = value)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    )
}

// ==================== 插件配置对话框 ====================

/**
 * Schema 驱动的插件配置对话框。
 * 根据插件的 configSchema 自动渲染配置表单：
 * - BOOL → Switch
 * - INT / FLOAT → NumberField
 * - STRING (有 options) → Dropdown
 * - STRING → TextField
 * - TEXT → MultilineTextField
 *
 * 对齐原项目 plugin-config-ui.ts 的 renderConfigForm 逻辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginConfigDialog(
    plugin: Plugin,
    pluginManager: PluginManager,
    onDismiss: () -> Unit,
) {
    val schema = plugin.configSchema ?: return
    val currentConfig = pluginManager.getPluginConfig(plugin.manifest.id)

    // 可编辑的配置值副本
    val editValues = remember(currentConfig) {
        mutableStateMapOf<String, JsonElement>().apply {
            // 先填充默认值
            for (field in schema.fields) {
                if (field.invisible) continue
                val value = currentConfig[field.key] ?: field.default
                if (value != null) put(field.key, value)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${plugin.manifest.name} - 配置")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (field in schema.fields) {
                    if (field.invisible) continue

                    when (field.type) {
                        ConfigFieldType.BOOL -> {
                            val checked = editValues[field.key]?.jsonPrimitive?.booleanOrNull ?: false
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(field.key, style = MaterialTheme.typography.bodyMedium)
                                    if (field.description.isNotBlank()) {
                                        Text(
                                            field.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { editValues[field.key] = JsonPrimitive(it) },
                                )
                            }
                        }

                        ConfigFieldType.INT -> {
                            val value = editValues[field.key]?.jsonPrimitive?.contentOrNull ?: ""
                            Column {
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { newVal ->
                                        val intVal = newVal.toIntOrNull()
                                        if (intVal != null) {
                                            editValues[field.key] = JsonPrimitive(intVal)
                                        } else if (newVal.isEmpty()) {
                                            editValues.remove(field.key)
                                        }
                                    },
                                    label = { Text(field.key) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (field.description.isNotBlank()) {
                                    Text(
                                        field.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        ConfigFieldType.FLOAT -> {
                            val value = editValues[field.key]?.jsonPrimitive?.contentOrNull ?: ""
                            Column {
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { newVal ->
                                        val floatVal = newVal.toDoubleOrNull()
                                        if (floatVal != null) {
                                            editValues[field.key] = JsonPrimitive(floatVal)
                                        } else if (newVal.isEmpty()) {
                                            editValues.remove(field.key)
                                        }
                                    },
                                    label = { Text(field.key) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (field.description.isNotBlank()) {
                                    Text(
                                        field.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        ConfigFieldType.STRING -> {
                            val value = editValues[field.key]?.jsonPrimitive?.contentOrNull ?: ""
                            Column {
                                if (field.options != null && field.options.isNotEmpty()) {
                                    // 下拉选择
                                    var expanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = it },
                                    ) {
                                        OutlinedTextField(
                                            value = value,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(field.key) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                                        )
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                        ) {
                                            field.options.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option) },
                                                    onClick = {
                                                        editValues[field.key] = JsonPrimitive(option)
                                                        expanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = value,
                                        onValueChange = { editValues[field.key] = JsonPrimitive(it) },
                                        label = { Text(field.key) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                if (field.description.isNotBlank()) {
                                    Text(
                                        field.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        ConfigFieldType.TEXT -> {
                            val value = editValues[field.key]?.jsonPrimitive?.contentOrNull ?: ""
                            Column {
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { editValues[field.key] = JsonPrimitive(it) },
                                    label = { Text(field.key) },
                                    minLines = 3,
                                    maxLines = 8,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (field.description.isNotBlank()) {
                                    Text(
                                        field.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // LIST / OBJECT / DICT — 简单 JSON 编辑
                        else -> {
                            val value = editValues[field.key]?.toString() ?: ""
                            Column {
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { newVal ->
                                        try {
                                            editValues[field.key] = Json.parseToJsonElement(newVal)
                                        } catch (_: Exception) {
                                            // 暂不保存无效 JSON
                                        }
                                    },
                                    label = { Text("${field.key} (JSON)") },
                                    minLines = 2,
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (field.description.isNotBlank()) {
                                    Text(
                                        field.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                pluginManager.savePluginConfig(plugin.manifest.id, editValues.toMap())
                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 恢复默认值按钮
                TextButton(onClick = {
                    editValues.clear()
                    for (field in schema.fields) {
                        if (field.invisible) continue
                        field.default?.let { editValues[field.key] = it }
                    }
                }) {
                    Text("恢复默认")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}