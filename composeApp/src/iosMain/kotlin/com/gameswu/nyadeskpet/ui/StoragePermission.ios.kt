package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable

/**
 * iOS 不需要额外存储权限请求（文件选择器通过 UIDocumentPickerViewController 自带权限）。
 */
@Composable
actual fun StoragePermissionRequest() {
    // iOS: no-op
}
