package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable

/**
 * 平台存储权限状态显示与管理入口。
 * Android：显示权限授予状态 + 引导按钮。
 * iOS：空实现。
 */
@Composable
expect fun StoragePermissionSection()
