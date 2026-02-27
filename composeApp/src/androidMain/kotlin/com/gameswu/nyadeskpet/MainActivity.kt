package com.gameswu.nyadeskpet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.gameswu.nyadeskpet.overlay.FloatingPetService
import com.gameswu.nyadeskpet.ui.setCurrentActivity

class MainActivity : ComponentActivity() {

    /**
     * 动态权限申请（符合 Android 6.0+ 要求）
     * 危险权限仅在 AndroidManifest.xml 声明不够，还需在代码中动态申请。
     * 参考：https://developer.huawei.com/consumer/cn/doc/hmscore-common-Guides/android-add-permissions-0000001167025694
     *
     * 需要动态申请的危险权限：
     * - RECORD_AUDIO: Visualizer 唇形同步 + 语音输入（所有 API 级别）
     * - POST_NOTIFICATIONS: 前台服务通知（API 33+, Android 13+）
     *
     * 其他危险权限已在各自使用处按需申请：
     * - 存储权限 (READ_EXTERNAL_STORAGE/READ_MEDIA_*): FilePicker.android.kt
     * - SYSTEM_ALERT_WINDOW: FloatingPetHelper.android.kt (Settings Intent)
     * - MANAGE_EXTERNAL_STORAGE: FilePicker.android.kt (Settings Intent)
     */
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, granted) ->
            val shortName = permission.substringAfterLast('.')
            if (granted) {
                Log.i("MainActivity", "$shortName 权限已授予")
            } else {
                Log.w("MainActivity", "$shortName 权限被拒绝")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestDangerousPermissions()

        setContent {
            App()
        }
    }

    /**
     * 统一检查并动态申请所有需要的危险权限。
     */
    private fun requestDangerousPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // RECORD_AUDIO: Visualizer 唇形同步 + 语音输入
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // POST_NOTIFICATIONS: Android 13+ 前台服务通知需要此权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册 Activity 引用，供 FloatingPetHelper.startFloatingPet() 调用 moveTaskToBack
        setCurrentActivity(this)

        // 安全守卫：应用回到前台时若悬浮窗仍在运行，统一停止
        // OVERLAY → IN_APP 的唯一收口点
        if (FloatingPetService.isRunning) {
            Log.i("MainActivity", "应用回到前台，自动关闭悬浮宠物")
            FloatingPetService.stop(this)
        }
    }

    override fun onPause() {
        super.onPause()
        // 清除 Activity 引用，避免内存泄漏
        setCurrentActivity(null)
    }
}