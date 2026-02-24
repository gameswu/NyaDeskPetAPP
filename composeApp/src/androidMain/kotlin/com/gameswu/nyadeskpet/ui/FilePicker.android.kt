package com.gameswu.nyadeskpet.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android 原生文件选择器实现。
 * 使用 Intent.ACTION_OPEN_DOCUMENT 打开系统文件管理器。
 * 在 Android 11+ 自动请求 MANAGE_EXTERNAL_STORAGE 权限以访问更多目录。
 */
@Composable
actual fun rememberFilePickerLauncher(
    mimeTypes: List<String>,
    onResult: (FilePickerResult?) -> Unit,
): () -> Unit {
    val context = LocalContext.current

    // 文件选择器 launcher
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
                    if (available <= 10 * 1024 * 1024) {
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

    // READ_EXTERNAL_STORAGE 权限请求 launcher（Android 12 及以下）
    var pendingLaunchAfterPermission by remember { mutableStateOf(false) }
    val readPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 无论是否授权，都打开文件选择器（SAF 本身不依赖存储权限）
        pendingLaunchAfterPermission = true
    }

    // MANAGE_EXTERNAL_STORAGE 设置页返回后的处理
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // 从设置页返回后直接打开文件选择器
        pendingLaunchAfterPermission = true
    }

    // 权限回调后触发文件选择器
    LaunchedEffect(pendingLaunchAfterPermission) {
        if (pendingLaunchAfterPermission) {
            pendingLaunchAfterPermission = false
            documentLauncher.launch(mimeTypes.toTypedArray())
        }
    }

    return {
        if (hasStoragePermission(context)) {
            // 已有权限，直接打开
            documentLauncher.launch(mimeTypes.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：引导用户到"所有文件访问"设置页
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                manageStorageLauncher.launch(intent)
            } catch (_: Exception) {
                // 某些设备不支持该 Intent，直接打开文件选择器
                documentLauncher.launch(mimeTypes.toTypedArray())
            }
        } else {
            // Android 10 及以下：请求 READ_EXTERNAL_STORAGE
            readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
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

/**
 * 检查应用是否拥有存储访问权限。
 * Android 11+: 检查 MANAGE_EXTERNAL_STORAGE
 * Android 10-: 检查 READ_EXTERNAL_STORAGE
 */
private fun hasStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
