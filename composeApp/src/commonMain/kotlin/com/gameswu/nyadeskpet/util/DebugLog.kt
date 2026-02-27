package com.gameswu.nyadeskpet.util

/**
 * 轻量级调试日志工具
 *
 * 集中管理所有 debug 输出，便于统一开关和后续对接 LogManager。
 * Release 构建可通过设置 [enabled] = false 关闭所有输出。
 */
object DebugLog {
    /** 全局开关 — 设为 false 可关闭所有 debug println */
    var enabled: Boolean = true

    inline fun d(tag: String, message: () -> String) {
        if (enabled) println("[$tag] ${message()}")
    }

    inline fun w(tag: String, message: () -> String) {
        if (enabled) println("[WARN][$tag] ${message()}")
    }

    inline fun e(tag: String, message: () -> String) {
        if (enabled) println("[ERROR][$tag] ${message()}")
    }
}
