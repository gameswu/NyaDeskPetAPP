package com.gameswu.nyadeskpet

import android.content.Context
import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/**
 * 读取构建时生成的版本号 — 对齐原项目 app.getVersion()
 * 版本号来源：gradle.properties → AppBuildConfig (构建时生成)
 */
actual fun getAppVersion(): String = AppBuildConfig.VERSION_NAME

actual fun formatEpochMillis(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}

actual typealias PlatformContext = Context
