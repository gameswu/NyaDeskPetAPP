package com.gameswu.nyadeskpet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Android 存储权限管理区域 — 显示在设置「关于」标签页中。
 *
 * 显示当前权限授权状态 + 引导用户去系统设置授权。
 */
@Composable
actual fun StoragePermissionSection() {
    val context = LocalContext.current
    var permissionState by remember { mutableStateOf(checkPermissions(context)) }

    // 从设置页返回后刷新
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        permissionState = checkPermissions(context)
    }

    // 媒体权限请求
    val mediaPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionState = checkPermissions(context)
    }

    // Legacy 权限请求
    val readPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionState = checkPermissions(context)
    }

    // 每次重组时刷新
    LaunchedEffect(Unit) {
        permissionState = checkPermissions(context)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("存储权限管理", style = MaterialTheme.typography.titleSmall)

            // 权限状态列表
            PermissionStatusRow(
                label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "所有文件访问" else "存储读取",
                granted = permissionState.hasFileAccess,
                onRequest = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            settingsLauncher.launch(intent)
                        } catch (_: Exception) {
                            try {
                                settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (_: Exception) { }
                        }
                    } else {
                        readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            )

            if (Build.VERSION.SDK_INT >= 33) {
                PermissionStatusRow(
                    label = "媒体文件访问",
                    granted = permissionState.hasMediaAccess,
                    onRequest = {
                        mediaPermissionsLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.READ_MEDIA_AUDIO,
                            )
                        )
                    }
                )
            }

            if (permissionState.hasFileAccess && permissionState.hasMediaAccess) {
                Text(
                    "所有存储权限已授予",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32)
                )
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    label: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (!granted) {
            TextButton(onClick = onRequest) {
                Text("去授权")
            }
        } else {
            Text(
                "已授权",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2E7D32),
            )
        }
    }
}

private data class PermissionCheckResult(
    val hasFileAccess: Boolean,
    val hasMediaAccess: Boolean,
)

private fun checkPermissions(context: android.content.Context): PermissionCheckResult {
    val hasFileAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    val hasMediaAccess = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        ).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    } else {
        true // Android 12 及以下不需要
    }

    return PermissionCheckResult(hasFileAccess, hasMediaAccess)
}
