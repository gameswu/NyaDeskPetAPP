@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.gameswu.nyadeskpet.live2d

import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.agent.*
import kotlin.concurrent.Volatile
import live2d.*
import platform.Foundation.*

/**
 * iOS Live2D Manager — calls C bridge (Live2DBridge) via cinterop.
 * Model files are located on the filesystem (Documents/models/).
 */
actual class Live2DManager actual constructor(private val context: PlatformContext) {

    /** Documents directory — models are stored here after being copied from the Bundle. */
    private val documentsDir: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        )
        (paths.firstOrNull() as? String) ?: NSHomeDirectory()
    }
    private val modelsDir: String by lazy { "$documentsDir/models" }

    /** Bundle 中 models 目录的路径 */
    private val bundleModelsDir: String? by lazy {
        NSBundle.mainBundle.resourcePath?.let { "$it/models" }
    }

    /**
     * Resolve a model path: if relative, prepend the Documents/models directory.
     * Settings store paths like "models/live2d/mao_pro_zh/runtime/mao_pro.model3.json".
     * On iOS we need an absolute filesystem path.
     *
     * Resolution order:
     * 1. Documents/models/ (user-writable, ensureBuiltinModels copies here)
     * 2. Bundle/models/     (read-only fallback, always available if bundled)
     */
    private fun resolvePath(path: String): String {
        if (path.startsWith("/")) return path
        val clean = path.removePrefix("models/")
        val fm = NSFileManager.defaultManager

        // 1. Documents
        val docsPath = "$modelsDir/$clean"
        if (fm.fileExistsAtPath(docsPath)) return docsPath

        // 2. Bundle fallback
        val bPath = bundleModelsDir?.let { "$it/$clean" }
        if (bPath != null && fm.fileExistsAtPath(bPath)) {
            println("[Live2D_iOS] Resolved from Bundle: $bPath")
            return bPath
        }

        // Neither exists — return Documents path (will produce a clear error)
        println("[Live2D_iOS] Model not found in Documents($docsPath) or Bundle($bPath)")
        return docsPath
    }

    @Volatile
    private var lipSyncValue = 0f
    private var lipSyncParamIds: List<String> = Live2DJsonParser.DEFAULT_LIP_SYNC_PARAMS

    @Volatile
    private var pendingModelPath: String? = null

    @Volatile
    private var lastLoadedModelPath: String? = null

    @Volatile
    private var glInitialized = false

    // ===== 视线跟随 =====
    private val gazeController = GazeController()

    @Volatile
    private var eyeTrackingEnabled = true

    actual fun initialize(): Boolean {
        return true // GL init happens in Live2DView when EAGLContext is ready
    }

    /**
     * Called from GL thread when EAGLContext is established.
     */
    fun initOnGLThread() {
        L2DBridge_Init()
        glInitialized = true
        pendingModelPath?.let { path ->
            pendingModelPath = null
            L2DBridge_LoadModel(path)
        }
    }

    actual fun loadModel(modelPath: String): Boolean {
        val resolved = resolvePath(modelPath)
        lastLoadedModelPath = resolved

        // Parse LipSync parameter IDs
        try {
            val json = readFileAsString(resolved)
            if (json != null) {
                val ids = Live2DJsonParser.parseLipSyncIds(json)
                lipSyncParamIds = if (ids.isNotEmpty()) ids else Live2DJsonParser.DEFAULT_LIP_SYNC_PARAMS
            }
        } catch (_: Exception) {
            lipSyncParamIds = Live2DJsonParser.DEFAULT_LIP_SYNC_PARAMS
        }

        pendingModelPath = resolved
        return true
    }

    /**
     * Called from GL thread to load the pending model.
     * Only loads when pendingModelPath is set (i.e. loadModel was called).
     * Does NOT fall back to lastLoadedModelPath — that would cause
     * reloading 60 times/sec and destroy all animation/physics state.
     */
    fun loadPendingModelOnGLThread() {
        val path = pendingModelPath ?: return
        pendingModelPath = null
        L2DBridge_LoadModel(path)
    }

    actual fun setParameterValue(id: String, value: Float, weight: Float) {
        L2DBridge_SetParameterValue(id, value, weight)
    }

    actual fun playMotion(group: String, index: Int, priority: Int) {
        L2DBridge_StartMotion(group, index, priority)
    }

    actual fun setExpression(expressionId: String) {
        L2DBridge_SetExpression(expressionId)
    }

    actual fun setLipSync(value: Float) {
        lipSyncValue = value
    }

    actual fun setModelTransform(scale: Float, offsetX: Float, offsetY: Float) {
        L2DBridge_SetModelTransform(scale, offsetX, offsetY)
    }

    /**
     * Called every frame from the GL render loop.
     */
    fun onFrameUpdate() {
        applyLipSync()
        applyGaze()
    }

    private fun applyLipSync() {
        for (paramId in lipSyncParamIds) {
            L2DBridge_SetParameterValue(paramId, lipSyncValue, 1f)
        }
    }

    private fun applyGaze() {
        val now = NSDate().timeIntervalSince1970.toLong() * 1000
        val (focusX, focusY) = gazeController.update(now)

        L2DBridge_SetParameterValue("ParamEyeBallX", focusX, 1f)
        L2DBridge_SetParameterValue("ParamEyeBallY", focusY, 1f)
        L2DBridge_SetParameterValue("ParamAngleX", focusX * 30f, 1f)
        L2DBridge_SetParameterValue("ParamAngleY", focusY * 30f, 1f)
        L2DBridge_SetParameterValue("ParamAngleZ", focusX * focusY * -30f, 1f)
        L2DBridge_SetParameterValue("ParamBodyAngleX", focusX * 10f, 1f)
    }

    actual fun getModelHitAreas(modelPath: String): List<String> {
        return try {
            val json = readFileAsString(resolvePath(modelPath)) ?: return emptyList()
            Live2DJsonParser.parseHitAreas(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    actual fun extractModelInfo(modelPath: String): ModelInfo? {
        val resolvedModelPath = resolvePath(modelPath)
        return try {
            val json = readFileAsString(resolvedModelPath) ?: return null

            val expressions = Live2DJsonParser.parseExpressions(json)
            val motions = Live2DJsonParser.parseMotions(json)
            val hitAreas = Live2DJsonParser.parseHitAreas(json)

            val modelDir = resolvedModelPath.substringBeforeLast('/', "")
            val paramMapPath = if (modelDir.isNotBlank()) "$modelDir/${Live2DJsonParser.PARAM_MAP_FILENAME}" else Live2DJsonParser.PARAM_MAP_FILENAME
            val paramMap = loadParamMap(paramMapPath)

            val modelInfo = ModelInfo(
                available = true,
                modelPath = modelPath,
                dimensions = Dimensions(0, 0),
                motions = motions,
                expressions = expressions,
                hitAreas = hitAreas,
                availableParameters = emptyList(),
                parameters = ScaleInfo(canScale = true, currentScale = 1f, userScale = 1f, baseScale = 1f),
            )

            if (paramMap != null) {
                Live2DJsonParser.enrichModelInfoWithParamMap(modelInfo, expressions, motions, paramMap)
            } else {
                modelInfo
            }
        } catch (_: Exception) {
            null
        }
    }

    actual fun setGazeTarget(x: Float, y: Float) {
        if (!eyeTrackingEnabled) return
        gazeController.setTarget(x, y)
    }

    actual fun clearGazeTarget() {
        gazeController.clearTarget()
    }

    actual fun resetGaze() {
        gazeController.forceReset()
    }

    actual fun setEyeTrackingEnabled(enabled: Boolean) {
        eyeTrackingEnabled = enabled
        if (!enabled) gazeController.forceReset()
    }

    // ===================== File Reading =====================

    private fun readFileAsString(path: String): String? {
        return try {
            val data = NSData.dataWithContentsOfFile(path) ?: return null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private fun loadParamMap(path: String): ParamMapData? {
            return try {
                val data = NSData.dataWithContentsOfFile(path) ?: return null
                val text = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: return null
                Live2DJsonParser.parseParamMap(text)
            } catch (_: Exception) {
                null
            }
        }
    }
}