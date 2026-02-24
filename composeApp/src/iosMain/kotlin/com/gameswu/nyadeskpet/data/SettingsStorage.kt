package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of SettingsStorage using NSUserDefaults.
 */
actual class SettingsStorage(private val context: PlatformContext) {
    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val json = Json { ignoreUnknownKeys = true }

    actual fun load(): AppSettings {
        val serialized = userDefaults.stringForKey("settings") ?: return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), serialized)
        } catch (e: Exception) {
            AppSettings()
        }
    }

    actual fun save(settings: AppSettings) {
        val serialized = json.encodeToString(AppSettings.serializer(), settings)
        userDefaults.setObject(serialized, "settings")
    }
}
