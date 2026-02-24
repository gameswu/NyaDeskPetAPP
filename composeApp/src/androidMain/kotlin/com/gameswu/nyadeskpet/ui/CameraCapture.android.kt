package com.gameswu.nyadeskpet.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import java.io.ByteArrayOutputStream

/**
 * Android 拍照实现。
 * 使用 TakePicturePreview 启动系统相机应用，返回预览 Bitmap。
 * 不需要 CAMERA 权限（系统相机应用自行处理权限）。
 */
@Composable
actual fun rememberCameraCaptureLauncher(
    onResult: (FilePickerResult?) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val bytes = stream.toByteArray()
            onResult(
                FilePickerResult(
                    uri = "camera://preview",
                    name = "photo_${System.currentTimeMillis()}.jpg",
                    mimeType = "image/jpeg",
                    bytes = bytes,
                )
            )
        } else {
            onResult(null)
        }
    }

    return { launcher.launch(null) }
}
