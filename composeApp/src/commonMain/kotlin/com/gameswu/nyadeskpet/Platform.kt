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
 * Platform-specific context.
 * Marked as abstract to match Android Context modality.
 */
expect abstract class PlatformContext
