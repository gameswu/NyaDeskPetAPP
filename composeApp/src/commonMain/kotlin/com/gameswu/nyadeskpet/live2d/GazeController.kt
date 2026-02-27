package com.gameswu.nyadeskpet.live2d

import kotlin.math.abs
import kotlin.math.exp

/**
 * 视线跟随控制器 — 指数衰减平滑。
 *
 * 采用帧率无关的指数插值（exponential smoothing）实现从旧视线到新视线的
 * 快速且连贯的过渡：
 *   current += (target - current) * (1 - e^(-speed * dt))
 *
 * 特性：
 * - 帧率无关：30fps 和 60fps 下视觉效果一致
 * - 无过冲：不会超过目标位置
 * - 快速响应：~0.15s 达到 90%，~0.25s 达到 99%
 * - 接近目标时自然减速
 *
 * 坐标系：X/Y 均为 -1..1，对应 Live2D 参数标准化范围。
 */
class GazeController {

    companion object {
        /**
         * 平滑速度因子（越大越快）。
         * speed=15 时：50% → ~46ms, 90% → ~153ms, 99% → ~307ms
         */
        private const val SMOOTH_SPEED = 15f

        /** 到达判定阈值 — 差距小于此值时直接吸附到目标 */
        private const val SNAP_THRESHOLD = 0.005f
    }

    // 当前值 (平滑后)
    var currentX: Float = 0f
        private set
    var currentY: Float = 0f
        private set

    // 目标值
    private var targetX: Float = 0f
    private var targetY: Float = 0f

    // 上一帧时间(ms)
    private var lastUpdateTime: Long = 0L

    /**
     * 设置目标位置（手指按下/移动时调用）。
     * @param x 归一化 X，范围 -1..1
     * @param y 归一化 Y，范围 -1..1
     */
    fun setTarget(x: Float, y: Float) {
        targetX = x.coerceIn(-1f, 1f)
        targetY = y.coerceIn(-1f, 1f)
    }

    /**
     * 清除目标（手指抬起时调用）。
     * 保持当前视线方向不变（不回到 0,0）。
     */
    fun clearTarget() {
        targetX = currentX
        targetY = currentY
    }

    /**
     * 重置到默认位置（关闭视线跟随时调用）。
     * 不是立即归零，而是将目标设为 (0,0) 让视线平滑回到正前方。
     */
    fun reset() {
        targetX = 0f
        targetY = 0f
    }

    /**
     * 强制立即归零（首次初始化或切换模型时调用）。
     */
    fun forceReset() {
        currentX = 0f
        currentY = 0f
        targetX = 0f
        targetY = 0f
        lastUpdateTime = 0L
    }

    /**
     * 每帧更新，返回当前平滑后的 (x, y)。
     *
     * 使用帧率无关的指数衰减：
     *   factor = 1 - e^(-SMOOTH_SPEED * dt)
     *   current += (target - current) * factor
     *
     * @param currentTimeMs 当前时间毫秒
     * @return Pair(focusX, focusY) 范围 -1..1
     */
    fun update(currentTimeMs: Long): Pair<Float, Float> {
        if (lastUpdateTime == 0L) {
            lastUpdateTime = currentTimeMs
            return currentX to currentY
        }

        val dtMs = (currentTimeMs - lastUpdateTime).toFloat().coerceIn(0f, 100f)
        lastUpdateTime = currentTimeMs

        val dt = dtMs / 1000f
        if (dt <= 0f) return currentX to currentY

        // 帧率无关的指数衰减因子
        val factor = 1f - exp(-SMOOTH_SPEED * dt)

        // X 轴
        val diffX = targetX - currentX
        if (abs(diffX) < SNAP_THRESHOLD) {
            currentX = targetX
        } else {
            currentX += diffX * factor
        }

        // Y 轴
        val diffY = targetY - currentY
        if (abs(diffY) < SNAP_THRESHOLD) {
            currentY = targetY
        } else {
            currentY += diffY * factor
        }

        return currentX to currentY
    }
}
