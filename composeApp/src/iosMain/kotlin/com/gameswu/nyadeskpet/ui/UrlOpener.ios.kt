package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS URL 打开实现，使用非废弃的 UIApplication.open(_:options:completionHandler:)。
 */
@Composable
actual fun rememberUrlOpener(): (String) -> Unit {
    return { url: String ->
        val nsUrl = NSURL(string = url)
        UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>(), null)
    }
}
