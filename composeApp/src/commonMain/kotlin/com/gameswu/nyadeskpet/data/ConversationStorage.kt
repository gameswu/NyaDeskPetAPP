package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext

/**
 * 对话持久化存储接口 — 平台实现
 */
expect class ConversationStorage(context: PlatformContext) {
    fun loadConversations(): String?
    fun saveConversations(data: String)
}
