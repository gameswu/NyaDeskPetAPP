@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.gameswu.nyadeskpet

import platform.Foundation.*
import platform.UIKit.UIDevice

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * iOS 版本号：优先从 Info.plist 读取，fallback 到构建时生成的版本号
 * 对齐原项目 app.getVersion() 逻辑
 */
actual fun getAppVersion(): String {
    return NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
        ?: AppBuildConfig.VERSION_NAME
}

actual fun formatEpochMillis(ms: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    val date = NSDate(timeIntervalSinceReferenceDate = ms / 1000.0 - 978307200.0)
    return formatter.stringFromDate(date)
}

actual fun formatUtcDate(): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'"
    formatter.locale = NSLocale("en_US")
    formatter.timeZone = NSTimeZone.timeZoneWithName("UTC")!!
    return formatter.stringFromDate(NSDate())
}

actual abstract class PlatformContext

class IosPlatformContext : PlatformContext()
