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
 *
 * 权限模型：
 * - Android 10 及以下：READ/WRITE_EXTERNAL_STORAGE + requestLegacyExternalStorage
 * - Android 11-12 (API 30-32)：MANAGE_EXTERNAL_STORAGE（引导到设置页）或 READ_EXTERNAL_STORAGE
 * - Android 13+ (API 33+)：READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO +
 *                           MANAGE_EXTERNAL_STORAGE（非媒体文件访问）
 */
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

    // 权限回调后打开文件选择器的标志
    var pendingLaunchAfterPermission by remember { mutableStateOf(false) }

    // Android 12 及以下：READ_EXTERNAL_STORAGE 权限请求
    val readPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 无论是否授权，SAF 本身不需要存储权限，照样打开
        pendingLaunchAfterPermission = true
    }

    // Android 13+：批量请求 READ_MEDIA_* 权限
    val mediaPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 请求完成后打开文件选择器
        pendingLaunchAfterPermission = true
    }

    // MANAGE_EXTERNAL_STORAGE 设置页返回后
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
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
        if (hasFullStoragePermission(context)) {
            // 已有完整存储权限，直接打开
            documentLauncher.launch(mimeTypes.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：
            // 1) 先请求媒体权限（Android 13+ 需要）
            // 2) 然后引导到 MANAGE_EXTERNAL_STORAGE 设置页（访问非媒体文件）
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (_: Exception) {
                    // 某些设备不支持，回退到通用设置页
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageStorageLauncher.launch(intent)
                    } catch (_: Exception) {
                        documentLauncher.launch(mimeTypes.toTypedArray())
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 33 && !hasMediaPermissions(context)) {
                // 有 MANAGE_EXTERNAL_STORAGE 但缺 READ_MEDIA_* 权限
                mediaPermissionsLauncher.launch(getMediaPermissions())
            } else {
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
 * 检查是否拥有完整存储访问权限。
 */
private fun hasFullStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager() &&
            (Build.VERSION.SDK_INT < 33 || hasMediaPermissions(context))
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 检查 Android 13+ 媒体权限是否已全部授予。
 */
private fun hasMediaPermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return getMediaPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 获取 Android 13+ 需要的细分媒体权限列表。
 */
private fun getMediaPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        emptyArray()
    }
}

/**
 * 检查是否拥有基本存储权限（供外部模块调用）。
 */
fun hasStoragePermission(context: Context): Boolean = hasFullStoragePermission(context)
