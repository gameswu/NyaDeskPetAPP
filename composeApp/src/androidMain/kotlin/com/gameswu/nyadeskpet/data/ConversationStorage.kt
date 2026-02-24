package com.gameswu.nyadeskpet.data

import android.content.Context
import com.gameswu.nyadeskpet.PlatformContext

actual class ConversationStorage actual constructor(private val context: PlatformContext) {
    private val prefs = context.getSharedPreferences("conversations", Context.MODE_PRIVATE)

    actual fun loadConversations(): String? = prefs.getString("data", null)

    actual fun saveConversations(data: String) {
        prefs.edit().putString("data", data).apply()
    }
}
