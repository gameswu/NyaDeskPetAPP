package com.gameswu.nyadeskpet.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android 拍照实现。
 * 使用 TakePicture + FileProvider 启动系统相机应用，拍摄全尺寸照片。
 * 兼容华为/OPPO/vivo/小米等 OEM ROM。
 */
@Composable
actual fun rememberCameraCaptureLauncher(
    onResult: (FilePickerResult?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var photoFile by remember { mutableStateOf<File?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && photoUri != null) {
            try {
                val file = photoFile
                val bytes = if (file != null && file.exists()) {
                    file.readBytes()
                } else {
                    // 回退到通过 ContentResolver 读取
                    context.contentResolver.openInputStream(photoUri!!)?.use { it.readBytes() }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    onResult(
                        FilePickerResult(
                            uri = photoUri.toString(),
                            name = "photo_${System.currentTimeMillis()}.jpg",
                            mimeType = "image/jpeg",
                            bytes = bytes,
                        )
                    )
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                android.util.Log.e("CameraCapture", "Error reading photo", e)
                onResult(null)
            }
        } else {
            onResult(null)
        }
    }

    return {
        try {
            // 在缓存目录创建临时文件
            val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
            val tmpFile = File(cameraDir, "photo_${System.currentTimeMillis()}.jpg")
            photoFile = tmpFile
            // 通过 FileProvider 生成 content:// URI
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tmpFile
            )
            photoUri = uri
            launcher.launch(uri)
        } catch (e: Exception) {
            android.util.Log.e("CameraCapture", "Failed to launch camera", e)
            onResult(null)
        }
    }
}
