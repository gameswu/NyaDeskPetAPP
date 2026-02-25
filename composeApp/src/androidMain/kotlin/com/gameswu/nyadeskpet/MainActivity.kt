package com.gameswu.nyadeskpet

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.gameswu.nyadeskpet.overlay.FloatingPetService
import com.gameswu.nyadeskpet.ui.setCurrentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App()
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