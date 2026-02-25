package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// iOS 不支持悬浮窗

actual fun hasOverlayPermission(): Boolean = false

@Composable
actual fun rememberOverlayPermissionLauncher(onResult: (Boolean) -> Unit): () -> Unit {
    return { onResult(false) }
}

actual fun startFloatingPet() { /* no-op */ }

actual fun stopFloatingPet() { /* no-op */ }

actual fun isFloatingPetRunning(): Boolean = false

private val _iosOverlayFlow = MutableStateFlow(false)
actual fun floatingPetRunningFlow(): StateFlow<Boolean> = _iosOverlayFlow

actual fun isOverlaySupported(): Boolean = false
