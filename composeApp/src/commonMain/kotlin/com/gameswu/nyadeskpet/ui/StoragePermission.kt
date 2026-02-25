package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable

/**
 * 平台存储权限请求。
 *
 * Android：启动后主动引导用户授予存储权限（含 MANAGE_EXTERNAL_STORAGE + READ_MEDIA_*）。
 * iOS：无需额外权限，为空实现。
 */
@Composable
expect fun StoragePermissionRequest()
