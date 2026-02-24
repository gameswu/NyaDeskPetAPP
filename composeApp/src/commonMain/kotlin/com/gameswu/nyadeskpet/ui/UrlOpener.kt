package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable

/**
 * 返回一个在当前平台打开 URL 的函数。
 */
@Composable
expect fun rememberUrlOpener(): (String) -> Unit
