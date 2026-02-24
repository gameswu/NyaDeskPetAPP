package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable

/**
 * 创建并记住一个拍照启动器。
 * 调用返回的 lambda 即可打开系统相机，拍照后通过 [onResult] 回调。
 *
 * @param onResult 拍照完成回调，取消或失败时传 null
 */
@Composable
expect fun rememberCameraCaptureLauncher(
    onResult: (FilePickerResult?) -> Unit,
): () -> Unit
