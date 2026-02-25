package com.gameswu.nyadeskpet

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * Returns the current time in milliseconds.
 */
expect fun currentTimeMillis(): Long

/**
 * Returns the app version string.
 */
expect fun getAppVersion(): String

/**
 * 将毫秒时间戳格式化为本地日期时间字符串
 * 格式: "YYYY-MM-DD HH:MM:SS"
 */
expect fun formatEpochMillis(ms: Long): String

/**
 * Platform-specific context.
 * Marked as abstract to match Android Context modality.
 */
expect abstract class PlatformContext
