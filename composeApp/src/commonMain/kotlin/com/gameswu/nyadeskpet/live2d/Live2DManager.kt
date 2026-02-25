package com.gameswu.nyadeskpet.live2d

import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.agent.ModelInfo

/**
 * Platform-specific Live2D rendering engine.
 */
expect class Live2DManager(context: PlatformContext) {
    /**
     * Initializes the Live2D rendering environment.
     * @return True if successful.
     */
    fun initialize(): Boolean

    /**
     * Loads a Live2D model from the given path.
     * @param modelPath The path to the .model3.json file.
     * @return True if successful.
     */
    fun loadModel(modelPath: String): Boolean

    /**
     * Directly sets a parameter's value.
     * This is the platform-specific call that the common controller uses.
     */
    fun setParameterValue(id: String, value: Float, weight: Float)

    /**
     * Plays a motion from the model's definition.
     */
    fun playMotion(group: String, index: Int, priority: Int)

    /**
     * Applies an expression to the model.
     */
    fun setExpression(expressionId: String)

    /**
     * Sets the lip-sync value, typically from an audio processor.
     */
    fun setLipSync(value: Float)

    /**
     * Sets user-controlled model transform (drag + pinch zoom).
     * @param scale   zoom factor, 1.0 = original
     * @param offsetX horizontal offset in NDC (-1..1)
     * @param offsetY vertical offset in NDC (-1..1)
     */
    fun setModelTransform(scale: Float, offsetX: Float, offsetY: Float)

    /**
     * Reads the model3.json at [modelPath] from assets and returns the HitAreas names.
     * Returns an empty list if the model has no HitAreas or the file cannot be read.
     * Uses the Id as fallback when Name is empty (matching original project behavior).
     */
    fun getModelHitAreas(modelPath: String): List<String>

    /**
     * 从 model3.json 提取完整的模型信息（对齐原项目 extractModelInfo）。
     * 包含 expressions、motions、hitAreas，以及 param-map.json 的映射表。
     * 返回 null 表示无法提取（如平台未实现）。
     */
    fun extractModelInfo(modelPath: String): ModelInfo?

    // ===== 视线跟随 =====

    /**
     * 设置视线跟随目标位置。
     * @param x 归一化坐标 -1..1
     * @param y 归一化坐标 -1..1
     */
    fun setGazeTarget(x: Float, y: Float)

    /**
     * 清除视线目标（手指抬起），保持当前视线方向不变。
     */
    fun clearGazeTarget()

    /**
     * 重置视线到默认位置 (0, 0)。
     */
    fun resetGaze()

    /**
     * 启用/禁用视线跟随功能。
     * 禁用时立即重置到默认位置。
     */
    fun setEyeTrackingEnabled(enabled: Boolean)
}
