package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable

/**
 * iOS 不需要存储权限管理 UI。
 */
@Composable
actual fun StoragePermissionSection() {
    // iOS: no-op
}
