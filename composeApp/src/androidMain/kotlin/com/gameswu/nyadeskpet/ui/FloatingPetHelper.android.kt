package com.gameswu.nyadeskpet.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.gameswu.nyadeskpet.overlay.FloatingPetService
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

// ===== Activity 引用跟踪，用于安全地调用 moveTaskToBack =====
private var currentActivityRef: WeakReference<Activity>? = null

/** 由 MainActivity 在 onResume/onPause 中调用，维护当前前台 Activity 引用 */
fun setCurrentActivity(activity: Activity?) {
    currentActivityRef = activity?.let { WeakReference(it) }
}

actual fun hasOverlayPermission(): Boolean {
    val context = org.koin.core.context.GlobalContext.get().get<android.content.Context>()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

@Composable
actual fun rememberOverlayPermissionLauncher(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        onResult(granted)
    }

    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            launcher.launch(intent)
        } else {
            onResult(true)
        }
    }
}

actual fun startFloatingPet() {
    val context = org.koin.core.context.GlobalContext.get().get<android.content.Context>()
    // 状态守卫：已在运行则跳过
    if (FloatingPetService.isRunning) {
        Log.w("FloatingPetHelper", "Overlay already running, ignoring start")
        return
    }
    FloatingPetService.start(context)
    // 将应用退到后台，避免应用内和悬浮窗两个 GL 渲染器同时活跃
    currentActivityRef?.get()?.moveTaskToBack(true)
        ?: Log.w("FloatingPetHelper", "No Activity ref, cannot moveTaskToBack")
}

actual fun stopFloatingPet() {
    val context = org.koin.core.context.GlobalContext.get().get<android.content.Context>()
    FloatingPetService.stop(context)
}

actual fun isFloatingPetRunning(): Boolean {
    return FloatingPetService.isRunning
}

actual fun floatingPetRunningFlow(): StateFlow<Boolean> {
    return FloatingPetService.isRunningFlow
}

actual fun isOverlaySupported(): Boolean = true
