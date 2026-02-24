package com.gameswu.nyadeskpet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gameswu.nyadeskpet.data.ModelDataManager
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.data.UpdateChecker
import com.gameswu.nyadeskpet.getAppVersion
import com.gameswu.nyadeskpet.i18n.I18nManager
import com.gameswu.nyadeskpet.live2d.Live2DManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingsScreen() {
    val repo: SettingsRepository = koinInject()
    val settings by repo.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    // 对齐原项目标签页：模型 | 连接 | 角色 | 显示 | 日志 | 关于
    val tabs = listOf(
        I18nManager.t("settings.model"),
        I18nManager.t("settings.connection"),
        I18nManager.t("settings.character"),
        I18nManager.t("settings.display"),
        I18nManager.t("settings.log"),
        I18nManager.t("settings.about"),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标题
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = I18nManager.t("settings.title"),
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> ModelSection(repo, settings)
                    1 -> ConnectionSection(repo, settings)
                    2 -> CharacterSection(repo, settings)
                    3 -> DisplaySection(repo, settings)
                    4 -> LogSection(repo, settings)
                    5 -> AboutSection(repo)
                }
            }

            // 底部操作栏 — 对齐原项目 settings-footer
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    OutlinedButton(onClick = {
                        repo.resetToDefaults()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "✅ 已恢复默认设置",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }) {
                        Text(I18nManager.t("settings.resetToDefaults"))
                    }
                    Button(onClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "✅ 设置已保存",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }) {
                        Text(I18nManager.t("settings.saveButton"))
                    }
                }
            }
        }

        // Snackbar 显示在底部
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
        )
    }
}

// ==================== 模型 — 对齐原项目 Model 标签页 ====================
@Composable
private fun ModelSection(
    repo: SettingsRepository,
    settings: com.gameswu.nyadeskpet.data.AppSettings,
) {
    SectionHeader(I18nManager.t("settings.model"))

    // 模型路径 + 浏览按钮
    val filePickerLauncher = rememberFilePickerLauncher(
        mimeTypes = listOf("application/json", "application/octet-stream", "*/*"),
        onResult = { result ->
            if (result != null) {
                // 使用选中文件的 URI 作为模型路径
                repo.update { s -> s.copy(modelPath = result.uri) }
            }
        }
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = settings.modelPath,
            onValueChange = { repo.update { s -> s.copy(modelPath = it) } },
            label = { Text(I18nManager.t("settings.modelPath")) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("models/live2d/mao_pro_zh/runtime/mao_pro.model3.json") },
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { filePickerLauncher() }) {
            Icon(Icons.Default.FolderOpen, contentDescription = I18nManager.t("chatWindow.attach"))
        }
    }

    Spacer(Modifier.height(8.dp))

    // 触碰反应配置 — 动态基于模型 HitAreas（对齐原项目 tap config）
    val live2dManager: Live2DManager = koinInject()
    val hitAreas = remember(settings.modelPath) {
        live2dManager.getModelHitAreas(settings.modelPath)
    }

    if (hitAreas.isNotEmpty()) {
        SectionHeader(I18nManager.t("settings.tapConfig"))
        Text(
            text = I18nManager.t("settings.tapConfigDesc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 获取当前模型的触碰配置
        val modelTapConfigs = settings.tapConfigs[settings.modelPath] ?: emptyMap()

        hitAreas.forEach { areaName ->
            val areaConfig = modelTapConfigs[areaName] ?: com.gameswu.nyadeskpet.data.TapAreaConfig()
            TapAreaItem(
                label = areaName,
                enabled = areaConfig.enabled,
                onEnabledChange = { newEnabled ->
                    repo.update { s ->
                        val currentModelConfigs = s.tapConfigs[s.modelPath]?.toMutableMap() ?: mutableMapOf()
                        currentModelConfigs[areaName] = areaConfig.copy(enabled = newEnabled)
                        s.copy(tapConfigs = s.tapConfigs + (s.modelPath to currentModelConfigs))
                    }
                },
                description = areaConfig.description,
                onDescriptionChange = { newDesc ->
                    repo.update { s ->
                        val currentModelConfigs = s.tapConfigs[s.modelPath]?.toMutableMap() ?: mutableMapOf()
                        currentModelConfigs[areaName] = areaConfig.copy(description = newDesc)
                        s.copy(tapConfigs = s.tapConfigs + (s.modelPath to currentModelConfigs))
                    }
                },
                expression = areaConfig.expression,
                onExpressionChange = { newExpr ->
                    repo.update { s ->
                        val currentModelConfigs = s.tapConfigs[s.modelPath]?.toMutableMap() ?: mutableMapOf()
                        currentModelConfigs[areaName] = areaConfig.copy(expression = newExpr)
                        s.copy(tapConfigs = s.tapConfigs + (s.modelPath to currentModelConfigs))
                    }
                },
                motion = areaConfig.motion,
                onMotionChange = { newMotion ->
                    repo.update { s ->
                        val currentModelConfigs = s.tapConfigs[s.modelPath]?.toMutableMap() ?: mutableMapOf()
                        currentModelConfigs[areaName] = areaConfig.copy(motion = newMotion)
                        s.copy(tapConfigs = s.tapConfigs + (s.modelPath to currentModelConfigs))
                    }
                },
            )
        }
    }
}

// ==================== 连接 — 对齐原项目，含音频和麦克风 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSection(
    repo: SettingsRepository,
    settings: com.gameswu.nyadeskpet.data.AppSettings,
) {
    SectionHeader(I18nManager.t("settings.backendMode"))
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (settings.backendMode == "builtin") I18nManager.t("settings.builtin") else I18nManager.t("settings.custom"),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(I18nManager.t("settings.builtin")) },
                onClick = {
                    repo.update { it.copy(backendMode = "builtin") }
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(I18nManager.t("settings.custom")) },
                onClick = {
                    repo.update { it.copy(backendMode = "custom") }
                    expanded = false
                }
            )
        }
    }

    SettingsToggle(
        label = I18nManager.t("settings.autoConnect"),
        checked = settings.autoConnect,
        onCheckedChange = { repo.update { s -> s.copy(autoConnect = it) } }
    )

    if (settings.backendMode == "builtin") {
        OutlinedTextField(
            value = settings.agentPort.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { port ->
                    repo.update { it.copy(agentPort = port) }
                }
            },
            label = { Text(I18nManager.t("settings.agentPort")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    } else {
        OutlinedTextField(
            value = settings.backendUrl,
            onValueChange = { repo.update { s -> s.copy(backendUrl = it) } },
            label = { Text("HTTP URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = settings.wsUrl,
            onValueChange = { repo.update { s -> s.copy(wsUrl = it) } },
            label = { Text(I18nManager.t("settings.wsUrl")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }

    // 音频 — 对齐原项目 audio section
    SectionHeader(I18nManager.t("settings.audio"))
    Text("${I18nManager.t("settings.volume")}: ${(settings.volume * 100).toInt()}%")
    Slider(
        value = settings.volume,
        onValueChange = { repo.update { s -> s.copy(volume = it) } },
        valueRange = 0f..1f,
        colors = SliderDefaults.colors(thumbColor = Color(0xFF4CAF50), activeTrackColor = Color(0xFF4CAF50))
    )

    // 麦克风 — 对齐原项目 microphone section
    SectionHeader(I18nManager.t("settings.microphone"))
    OutlinedTextField(
        value = settings.asrModel,
        onValueChange = { repo.update { s -> s.copy(asrModel = it) } },
        label = { Text(I18nManager.t("settings.asrModel")) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    SettingsToggle(
        label = I18nManager.t("settings.micBackgroundMode"),
        checked = settings.micBackgroundMode,
        onCheckedChange = { repo.update { s -> s.copy(micBackgroundMode = it) } }
    )
    Text("${I18nManager.t("settings.micVolumeThreshold")}: ${settings.micVolumeThreshold}")
    Slider(
        value = settings.micVolumeThreshold.toFloat(),
        onValueChange = { repo.update { s -> s.copy(micVolumeThreshold = it.toInt()) } },
        valueRange = 0f..100f,
        colors = SliderDefaults.colors(thumbColor = Color(0xFF4CAF50), activeTrackColor = Color(0xFF4CAF50))
    )
    SettingsToggle(
        label = I18nManager.t("settings.micAutoSend"),
        checked = settings.micAutoSend,
        onCheckedChange = { repo.update { s -> s.copy(micAutoSend = it) } }
    )
}

// ==================== 显示 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySection(
    repo: SettingsRepository,
    settings: com.gameswu.nyadeskpet.data.AppSettings,
) {
    SectionHeader(I18nManager.t("settings.theme"))
    val themeOptions = listOf(
        "light" to I18nManager.t("settings.themeLight"),
        "dark" to I18nManager.t("settings.themeDark"),
        "system" to I18nManager.t("settings.themeSystem"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        themeOptions.forEach { (value, label) ->
            FilterChip(
                selected = settings.theme == value,
                onClick = { repo.update { it.copy(theme = value) } },
                label = { Text(label) }
            )
        }
    }

    SectionHeader(I18nManager.t("settings.language"))
    var langExpanded by remember { mutableStateOf(false) }
    val locales = I18nManager.availableLocales()
    ExposedDropdownMenuBox(expanded = langExpanded, onExpandedChange = { langExpanded = it }) {
        OutlinedTextField(
            value = locales.firstOrNull { it.first == settings.locale }?.second ?: settings.locale,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
            locales.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        repo.update { it.copy(locale = code) }
                        I18nManager.setLocale(code)
                        langExpanded = false
                    }
                )
            }
        }
    }

    SettingsToggle(
        label = I18nManager.t("settings.showSubtitle"),
        checked = settings.showSubtitle,
        onCheckedChange = { repo.update { s -> s.copy(showSubtitle = it) } }
    )

}

// ==================== 角色 ====================
@Composable
private fun CharacterSection(
    repo: SettingsRepository,
    settings: com.gameswu.nyadeskpet.data.AppSettings,
) {
    SectionHeader(I18nManager.t("settings.character"))
    SettingsToggle(
        label = I18nManager.t("settings.useCustomCharacter"),
        checked = settings.useCustomCharacter,
        onCheckedChange = { repo.update { s -> s.copy(useCustomCharacter = it) } }
    )
    if (settings.useCustomCharacter) {
        OutlinedTextField(
            value = settings.customName,
            onValueChange = { repo.update { s -> s.copy(customName = it) } },
            label = { Text(I18nManager.t("settings.customName")) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.customPersonality,
            onValueChange = { repo.update { s -> s.copy(customPersonality = it) } },
            label = { Text(I18nManager.t("settings.customPersonality")) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
    }
}

// ==================== 日志 — 对齐原项目，添加日志等级勾选 ====================
@Composable
private fun LogSection(
    repo: SettingsRepository,
    settings: com.gameswu.nyadeskpet.data.AppSettings,
) {
    SectionHeader(I18nManager.t("settings.logEnabled"))
    SettingsToggle(
        label = I18nManager.t("settings.logEnabled"),
        checked = settings.logEnabled,
        onCheckedChange = { repo.update { s -> s.copy(logEnabled = it) } }
    )

    if (settings.logEnabled) {
        // 日志等级 — 对齐原项目的日志等级勾选
        SectionHeader(I18nManager.t("settings.logLevels"))
        val levels = listOf("debug" to "调试", "info" to "信息", "warn" to "警告", "error" to "错误", "critical" to "严重")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            levels.forEach { (level, label) ->
                FilterChip(
                    selected = settings.logLevels.contains(level),
                    onClick = {
                        val newLevels = if (settings.logLevels.contains(level)) {
                            settings.logLevels - level
                        } else {
                            settings.logLevels + level
                        }
                        repo.update { s -> s.copy(logLevels = newLevels) }
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // 保留天数
        SectionHeader(I18nManager.t("settings.logRetentionDays"))
        Text("${settings.logRetentionDays} ${I18nManager.t("settings.days")}")
        Slider(
            value = settings.logRetentionDays.toFloat(),
            onValueChange = { repo.update { s -> s.copy(logRetentionDays = it.toInt()) } },
            valueRange = 1f..90f,
            colors = SliderDefaults.colors(thumbColor = Color(0xFF4CAF50), activeTrackColor = Color(0xFF4CAF50))
        )
    }
}

// ==================== 关于 — 对齐原项目：版本号、检查更新、更新源、项目链接、数据管理 ====================
@Composable
private fun AboutSection(repo: SettingsRepository) {
    val settings by repo.settings.collectAsState()
    val openUrl = rememberUrlOpener()
    var editingUpdateSource by remember { mutableStateOf(settings.updateSource) }
    val coroutineScope = rememberCoroutineScope()

    // 版本检查状态
    val updateChecker = remember { UpdateChecker() }
    var isChecking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.UpdateResult?>(null) }

    // 数据管理状态
    val modelDataManager: ModelDataManager = koinInject()
    var dataDirSize by remember { mutableStateOf(0L) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    // 初始加载数据目录大小
    LaunchedEffect(Unit) {
        dataDirSize = modelDataManager.getDataDirSize()
    }

    SectionHeader(I18nManager.t("settings.about"))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("NyaDeskPet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${I18nManager.t("settings.version")}: ${getAppVersion()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (!isChecking) {
                            isChecking = true
                            updateResult = null
                            coroutineScope.launch {
                                updateResult = updateChecker.checkForUpdate(settings.updateSource)
                                isChecking = false
                            }
                        }
                    },
                    enabled = !isChecking
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(if (isChecking) I18nManager.t("settings.checking") else I18nManager.t("settings.checkUpdate"))
                }
            }

            // 检查更新结果显示
            updateResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.error != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else if (result.hasUpdate) {
                            Color(0xFFFFF3E0)  // orange tint
                        } else {
                            Color(0xFFE8F5E9)  // green tint
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (result.error != null) {
                            Text(
                                text = result.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (result.hasUpdate) {
                            Text(
                                text = "${I18nManager.t("settings.newVersionAvailable")}: v${result.latestVersion}",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFE65100)
                            )
                            if (result.releaseNotes.isNotBlank()) {
                                Text(
                                    text = result.releaseNotes.take(200) + if (result.releaseNotes.length > 200) "..." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { openUrl(result.releaseUrl) }) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(I18nManager.t("settings.goToDownload"))
                            }
                        } else {
                            Text(
                                text = I18nManager.t("settings.alreadyLatest"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }
            }

            // 更新源编辑
            OutlinedTextField(
                value = editingUpdateSource,
                onValueChange = { newVal ->
                    editingUpdateSource = newVal
                    repo.update { s -> s.copy(updateSource = newVal) }
                },
                label = { Text(I18nManager.t("settings.updateSource")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    Text(
        text = "基于 Live2D + AI Agent 的跨平台桌面宠物应用",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // ==================== 数据管理 — 对齐原项目，支持打开/清除数据目录 ====================
    SectionHeader(I18nManager.t("settings.dataManagement"))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 数据目录大小
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        I18nManager.t("settings.dataDir"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "${I18nManager.t("settings.dataDirSize")}: ${formatFileSize(dataDirSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 数据目录路径（只读显示）
            Text(
                text = modelDataManager.getAppDataDir(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { modelDataManager.openDataDirectory() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18nManager.t("settings.openDataDir"), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18nManager.t("settings.clearModelCache"), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    // 清除缓存确认对话框
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(I18nManager.t("settings.clearModelCache")) },
            text = { Text(I18nManager.t("settings.clearCacheConfirm")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        modelDataManager.clearModelCache()
                        dataDirSize = modelDataManager.getDataDirSize()
                        showClearCacheDialog = false
                    }
                ) {
                    Text(I18nManager.t("settings.confirm"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(I18nManager.t("settings.cancel"))
                }
            }
        )
    }

    // 项目链接  — 对齐原项目 about-links-bar
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = { openUrl(settings.updateSource) }) {
            Text("GitHub", color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = { openUrl("https://afdian.com/a/gameswu") }) {
            Text(I18nManager.t("settings.donate"), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 格式化文件大小为人类可读字符串 */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).toLong() / 10.0} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).toLong() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 100).toLong() / 100.0} GB"
}

// ==================== 通用组件 ====================

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
    HorizontalDivider()
}

@Composable
private fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF4CAF50))
        )
    }
}

@Composable
private fun TapAreaItem(
    label: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    expression: String,
    onExpressionChange: (String) -> Unit,
    motion: String,
    onMotionChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF4CAF50))
                )
            }
            if (enabled) {
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(I18nManager.t("settings.tapAreaDesc")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(I18nManager.t("settings.tapAreaDescPlaceholder")) },
                )
                OutlinedTextField(
                    value = expression,
                    onValueChange = onExpressionChange,
                    label = { Text(I18nManager.t("settings.expression")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = motion,
                    onValueChange = onMotionChange,
                    label = { Text(I18nManager.t("settings.motion")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}
