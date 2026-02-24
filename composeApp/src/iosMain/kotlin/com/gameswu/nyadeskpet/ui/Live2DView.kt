package com.gameswu.nyadeskpet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * iOS Live2D 画布组件（桩实现）。
 * TODO: 使用 Metal/UIKit 集成 Cubism Native SDK for iOS。
 */
@Composable
actual fun Live2DCanvas(modifier: Modifier) {
    Box(
        modifier = modifier.background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Text("Live2D (iOS - not yet implemented)", color = Color.Gray)
    }
}
