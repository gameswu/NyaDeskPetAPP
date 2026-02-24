package com.gameswu.nyadeskpet.data

import android.content.Context
import com.gameswu.nyadeskpet.PlatformContext
import kotlinx.serialization.json.Json

/**
 * Android implementation of SettingsStorage using SharedPreferences.
 */
actual class SettingsStorage(private val context: PlatformContext) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    actual fun load(): AppSettings {
        val serialized = prefs.getString("settings", null) ?: return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), serialized)
        } catch (e: Exception) {
            AppSettings()
        }
    }

    actual fun save(settings: AppSettings) {
        val serialized = json.encodeToString(AppSettings.serializer(), settings)
        prefs.edit().putString("settings", serialized).apply()
    }
}
