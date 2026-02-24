package com.gameswu.nyadeskpet.live2d

import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.agent.ModelInfo

/**
 * iOS implementation of Live2DManager using cinterop to Cubism Objective-C SDK.
 */
actual class Live2DManager(private val context: PlatformContext) {
    actual fun initialize(): Boolean {
        // Initialize CubismFramework via cinterop
        return true
    }

    actual fun loadModel(modelPath: String): Boolean {
        // Load model via cinterop
        return true
    }

    actual fun setParameterValue(id: String, value: Float, weight: Float) {
        // cinterop call
    }

    actual fun playMotion(group: String, index: Int, priority: Int) {
        // cinterop call
    }

    actual fun setExpression(expressionId: String) {
        // cinterop call
    }

    actual fun setLipSync(value: Float) {
        // cinterop call
    }

    actual fun setModelTransform(scale: Float, offsetX: Float, offsetY: Float) {
        // TODO: iOS Live2D transform
    }

    actual fun getModelHitAreas(modelPath: String): List<String> {
        // TODO: implement via iOS bundle resource reading
        return emptyList()
    }

    actual fun extractModelInfo(modelPath: String): ModelInfo? {
        // TODO: implement via iOS bundle resource reading
        return null
    }
}
