package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext
import platform.Foundation.NSUserDefaults

actual class ConversationStorage actual constructor(private val context: PlatformContext) {
    private val userDefaults = NSUserDefaults.standardUserDefaults

    actual fun loadConversations(): String? = userDefaults.stringForKey("conversations_data")

    actual fun saveConversations(data: String) {
        userDefaults.setObject(data, "conversations_data")
    }
}
