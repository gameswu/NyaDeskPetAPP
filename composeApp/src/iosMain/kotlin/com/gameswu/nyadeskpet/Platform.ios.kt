package com.gameswu.nyadeskpet

import platform.Foundation.NSBundle
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

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
    val date = NSDate(timeIntervalSince1970 = ms / 1000.0)
    return formatter.stringFromDate(date)
}

actual class PlatformContext
