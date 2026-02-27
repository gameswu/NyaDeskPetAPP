package com.gameswu.nyadeskpet

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
 * 返回当前 UTC 时间的英文格式字符串
 * 格式: "Wed Jan 01 2025 00:00:00 GMT+0000 (Coordinated Universal Time)"
 * 用于 Edge TTS 等需要标准 HTTP Date 的场景
 */
expect fun formatUtcDate(): String

/**
 * Platform-specific context.
 * Marked as abstract to match Android Context modality.
 */
expect abstract class PlatformContext
