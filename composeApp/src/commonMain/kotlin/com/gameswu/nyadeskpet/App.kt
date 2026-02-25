package com.gameswu.nyadeskpet

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.i18n.I18nManager
import com.gameswu.nyadeskpet.ui.PetScreen
import com.gameswu.nyadeskpet.ui.SettingsScreen
import com.gameswu.nyadeskpet.ui.StoragePermissionRequest
import com.gameswu.nyadeskpet.ui.agent.AgentPanelScreen
import com.gameswu.nyadeskpet.ui.chat.ChatScreen
import com.gameswu.nyadeskpet.ui.theme.AppTheme
import org.koin.compose.koinInject

@Composable
fun App() {
    val settingsRepo: SettingsRepository = koinInject()
    var selectedTab by remember { mutableIntStateOf(0) }

    // 初始化 i18n
    LaunchedEffect(Unit) {
        I18nManager.setLocale(settingsRepo.current.locale)
    }

    AppTheme {
        // 启动时主动请求存储权限（Android 实际弹窗, iOS 空实现）
        StoragePermissionRequest()

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Pets, contentDescription = null) },
                        label = { Text(I18nManager.t("tabs.pet")) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                        label = { Text(I18nManager.t("tabs.chat")) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                        label = { Text("Agent") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(I18nManager.t("tabs.settings")) }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (selectedTab) {
                    0 -> PetScreen()
                    1 -> ChatScreen()
                    2 -> AgentPanelScreen()
                    3 -> SettingsScreen()
                }
            }
        }
    }
}
