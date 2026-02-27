package com.gameswu.nyadeskpet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.gameswu.nyadeskpet.data.SettingsRepository
import org.koin.compose.koinInject

// 品牌色 — 与原项目一致的 Material Green 体系
private val Green500 = Color(0xFF4CAF50)
private val Green800 = Color(0xFF2E7D32)
private val GreenLight = Color(0xFF8BC34A)
private val GreenContainer = Color(0xFFC8E6C9)
private val OnGreenContainer = Color(0xFF1B5E20)

private val LightColors = lightColorScheme(
    primary = Green500,
    onPrimary = Color.White,
    primaryContainer = GreenContainer,
    onPrimaryContainer = OnGreenContainer,
    secondary = GreenLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEDC8),
    onSecondaryContainer = Color(0xFF33691E),
    tertiary = Color(0xFF00897B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB2DFDB),
    onTertiaryContainer = Color(0xFF004D40),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFBDBDBD),
)

private val DarkColors = darkColorScheme(
    primary = Green500,
    onPrimary = Color.White,
    primaryContainer = Green800,
    onPrimaryContainer = GreenContainer,
    secondary = GreenLight,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF33691E),
    onSecondaryContainer = Color(0xFFDCEDC8),
    tertiary = Color(0xFF4DB6AC),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF004D40),
    onTertiaryContainer = Color(0xFFB2DFDB),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF616161),
)

/**
 * 应用主题，支持 system / light / dark 三种模式
 * 品牌色体系对齐原 Electron 版本的 #4CAF50 绿色主基调
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val repo: SettingsRepository = koinInject()
    val settings by repo.settings.collectAsState()
    val systemDark = isSystemInDarkTheme()

    val isDark = when (settings.theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content
    )
}
