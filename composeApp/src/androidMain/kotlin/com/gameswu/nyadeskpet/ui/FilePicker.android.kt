package com.gameswu.nyadeskpet.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/**
 * Android 原生文件选择器实现。
 * 使用 Storage Access Framework (SAF) 打开系统文件管理器。
 * SAF 不需要任何存储权限即可工作。
 */

private const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024 // 10MB

@Composable
actual fun rememberFilePickerLauncher(
    mimeTypes: List<String>,
    onResult: (FilePickerResult?) -> Unit,
): () -> Unit {
    val context = LocalContext.current

    // SAF 文件选择器 launcher
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        try {
            val name = getFileName(context, uri) ?: "unknown"
            val mimeType = context.contentResolver.getType(uri)

            // 读取文件内容（限制 10MB 防 OOM）
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val available = input.available()
                    if (available <= MAX_FILE_SIZE_BYTES) {
                        input.readBytes()
                    } else null
                }
            } catch (_: Exception) { null }

            onResult(FilePickerResult(
                uri = uri.toString(),
                name = name,
                mimeType = mimeType,
                bytes = bytes,
            ))
        } catch (e: Exception) {
            android.util.Log.e("FilePicker", "Error reading file", e)
            onResult(null)
        }
    }

    // SAF 不需要存储权限，直接启动文件选择器
    return {
        try {
            documentLauncher.launch(mimeTypes.toTypedArray())
        } catch (e: Exception) {
            android.util.Log.e("FilePicker", "Failed to launch file picker", e)
            onResult(null)
        }
    }
}

/**
 * 从 content URI 获取文件名。
 */
private fun getFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return cursor.getString(nameIndex)
            }
        }
    }
    return uri.lastPathSegment
}
