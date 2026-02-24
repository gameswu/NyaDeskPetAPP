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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = { /* TODO: TTS provider dialog */ }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18nManager.t("agent.addTts"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (settings.ttsProviderInstances.isEmpty()) {
                    EmptyHint(I18nManager.t("agent.noProviders"))
                }
            }
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
                        message = "✅ Provider \"${instanceConfig.displayName}\" 已添加",
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
                        message = "✅ Provider \"${newConfig.displayName}\" 已更新",
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
                // Provider 类型选择
                Text("Provider 类型", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 用 FlowRow 更好，但为简单起见用 wrap
                }
                // 类型按钮列表
                availableTypes.forEachIndexed { index, meta ->
                    val isSelected = index == selectedTypeIndex
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedTypeIndex = index
                            displayName = meta.name
                        },
                        label = { Text(meta.name, style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                        } else null,
                    )
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

private fun generateInstanceId(): String = buildString {
    append("inst-")
    repeat(12) { append("0123456789abcdef"[Random.nextInt(16)]) }
}

// ==================== 工具标签页 ====================

@Composable
private fun ToolsTab() {
    val pluginManager: PluginManager = koinInject()
    val toolProviders by pluginManager.toolProviders.collectAsState()

    // 使用 getAllToolsWithSource 获取工具及其来源插件
    val toolsWithSource = remember(toolProviders) {
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
                    Text(
                        I18nManager.t("agent.tools.count").replace("%d", "${toolsWithSource.size}"),
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
                    toolsWithSource.forEach { (tool, source) ->
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            tool.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        // 来源标签
                                        val sourceIcon = if (source.startsWith("builtin") || source == "内置") "⚡" else "🔌"
                                        AssistChip(
                                            onClick = {},
                                            label = {
                                                Text(
                                                    "$sourceIcon $source",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                    if (tool.requireConfirm) {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text("🔒 需确认", style = MaterialTheme.typography.labelSmall) },
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
                        }
                    }
                }
            }
        }

        SectionHeader(I18nManager.t("agent.mcp.title"), Icons.Default.Cable)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                EmptyHint(I18nManager.t("agent.mcp.noServers"))
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = { /* add MCP */ }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18nManager.t("agent.mcp.add"))
                }
            }
        }
    }
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
                                // 来源标签
                                cmd.category?.takeIf { it.isNotBlank() }?.let { source ->
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                source,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                I18nManager.t("agent.skills.count").replace("%d", "0"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { /* refresh */ }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyHint(I18nManager.t("agent.skills.empty"))
            }
        }
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