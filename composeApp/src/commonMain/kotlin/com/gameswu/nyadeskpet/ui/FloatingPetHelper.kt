package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * 悬浮宠物控制器接口（跨平台 expect/actual）。
 *
 * 状态机：
 *   IN_APP  ←→  OVERLAY
 *
 * - IN_APP → OVERLAY: startFloatingPet() 启动服务 + 退后台
 * - OVERLAY → IN_APP: stopFloatingPet() 或应用回前台时自动停止
 *
 * 关键不变量：同一时刻只有一个 Live2D GL 渲染器活跃。
 *
 * Android：使用 WindowManager overlay + 前台服务
 * iOS：不支持悬浮窗，返回不可用状态
 */

/** 检查悬浮窗权限是否已授予 */
expect fun hasOverlayPermission(): Boolean

/** 请求悬浮窗权限 */
@Composable
expect fun rememberOverlayPermissionLauncher(onResult: (Boolean) -> Unit): () -> Unit

/** 启动悬浮宠物（IN_APP → OVERLAY 转换） */
expect fun startFloatingPet()

/** 停止悬浮宠物（OVERLAY → IN_APP 转换） */
expect fun stopFloatingPet()

/** 悬浮宠物是否正在运行（快照） */
expect fun isFloatingPetRunning(): Boolean

/** 悬浮宠物运行状态的响应式流（主要用于 UI 观察） */
expect fun floatingPetRunningFlow(): StateFlow<Boolean>

/** 平台是否支持悬浮窗 */
expect fun isOverlaySupported(): Boolean
