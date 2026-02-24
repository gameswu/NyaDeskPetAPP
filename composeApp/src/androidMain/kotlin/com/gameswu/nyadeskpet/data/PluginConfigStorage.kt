package com.gameswu.nyadeskpet.data

import android.content.Context
import com.gameswu.nyadeskpet.PlatformContext

/**
 * Android 实现 — 使用 SharedPreferences 持久化插件配置
 */
actual class PluginConfigStorage actual constructor(private val context: PlatformContext) {
    private val prefs = context.getSharedPreferences("plugin_configs", Context.MODE_PRIVATE)

    actual fun loadAll(): String? = prefs.getString("data", null)

    actual fun saveAll(data: String) {
        prefs.edit().putString("data", data).apply()
    }

    actual fun clearAll() {
        prefs.edit().clear().apply()
    }
}
