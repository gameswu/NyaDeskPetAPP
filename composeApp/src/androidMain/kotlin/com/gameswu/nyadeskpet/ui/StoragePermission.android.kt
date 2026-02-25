package com.gameswu.nyadeskpet.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Android 启动权限请求 — 主动引导用户授予完整存储访问权限。
 *
 * 策略:
 * 1. 首次检测到缺少权限时弹出对话框说明
 * 2. Android 11+: 引导到「所有文件访问」设置页
 * 3. Android 13+: 同时请求 READ_MEDIA_* 权限
 * 4. Android 10-: 请求 READ_EXTERNAL_STORAGE
 * 5. 用户可选择「稍后再说」跳过
 */
@Composable
actual fun StoragePermissionRequest() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var hasChecked by remember { mutableStateOf(false) }

    // Android 13+ 批量请求 READ_MEDIA_* 权限
    val mediaPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // 媒体权限请求完成后，继续检查 MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showDialog = true // 弹出对话框引导用户去设置页
        }
    }

    // Android 10 及以下 READ_EXTERNAL_STORAGE
    val readPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> /* 完成 */ }

    // MANAGE_EXTERNAL_STORAGE 设置页回调
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        showDialog = false
    }

    // 启动时自动检测权限
    LaunchedEffect(Unit) {
        if (hasChecked) return@LaunchedEffect
        hasChecked = true

        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+: 先检查 READ_MEDIA_* 权限
            val mediaPerms = arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
            val missingMedia = mediaPerms.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missingMedia.isNotEmpty()) {
                mediaPermissionsLauncher.launch(mediaPerms)
            } else if (!Environment.isExternalStorageManager()) {
                showDialog = true
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12: 只检查 MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                showDialog = true
            }
        } else {
            // Android 10 及以下
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    // 对话框：引导用户去「所有文件访问」设置页
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("需要存储访问权限") },
            text = {
                Text(
                    "应用需要「所有文件访问」权限来读取外部文件（如模型文件、技能包等）。\n\n" +
                        "点击「前往设置」，在设置页中允许本应用的文件访问权限。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        manageStorageLauncher.launch(intent)
                    } catch (_: Exception) {
                        try {
                            manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Exception) { /* 设备不支持 */ }
                    }
                }) {
                    Text("前往设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("稍后再说")
                }
            },
        )
    }
}
