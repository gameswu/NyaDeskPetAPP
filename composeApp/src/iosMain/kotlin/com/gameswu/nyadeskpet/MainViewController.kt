package com.gameswu.nyadeskpet

import androidx.compose.ui.window.ComposeUIViewController
import com.gameswu.nyadeskpet.di.commonModule
import com.gameswu.nyadeskpet.di.iosModule
import com.gameswu.nyadeskpet.di.setupWiring
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    // 防止 SwiftUI 视图重建时重复调用 startKoin
    val alreadyStarted = try { KoinPlatform.getKoin(); true } catch (_: Exception) { false }
    if (!alreadyStarted) {
        startKoin {
            modules(commonModule, iosModule)
            setupWiring()
        }
    }
    return ComposeUIViewController { App() }
}