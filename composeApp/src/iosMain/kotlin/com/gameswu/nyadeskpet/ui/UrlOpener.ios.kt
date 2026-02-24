package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS URL 打开实现，使用 UIApplication.openURL。
 */
@Composable
actual fun rememberUrlOpener(): (String) -> Unit {
    return { url: String ->
        val nsUrl = NSURL(string = url)
        UIApplication.sharedApplication.openURL(nsUrl)
    }
}
