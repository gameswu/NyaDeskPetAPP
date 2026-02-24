package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext
import platform.Foundation.NSUserDefaults

/**
 * iOS 实现 — 使用 NSUserDefaults 持久化插件配置
 */
actual class PluginConfigStorage actual constructor(private val context: PlatformContext) {
    private val userDefaults = NSUserDefaults.standardUserDefaults

    actual fun loadAll(): String? = userDefaults.stringForKey("plugin_configs_data")

    actual fun saveAll(data: String) {
        userDefaults.setObject(data, "plugin_configs_data")
    }

    actual fun clearAll() {
        userDefaults.removeObjectForKey("plugin_configs_data")
    }
}
