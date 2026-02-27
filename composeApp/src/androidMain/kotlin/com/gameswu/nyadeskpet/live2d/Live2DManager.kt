package com.gameswu.nyadeskpet.live2d

import android.content.Context
import android.opengl.GLSurfaceView
import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.agent.*

/**
 * Android Live2D Manager implementation.
 */
actual class Live2DManager actual constructor(private val context: PlatformContext) {

    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: Live2DRenderer? = null
    @Volatile
    private var lipSyncValue = 0f
    /** 当前模型的唇形同步参数 ID 列表（从 model3.json Groups.LipSync.Ids 解析） */
    private var lipSyncParamIds: List<String> = Live2DJsonParser.DEFAULT_LIP_SYNC_PARAMS
    @Volatile
    private var pendingModelPath: String? = null

    /** 记住上次成功请求加载的模型路径，用于 GL 上下文重建时自动重载 */
    @Volatile
    private var lastLoadedModelPath: String? = null

    // ===== 视线跟随 =====
    private val gazeController = GazeController()
    @Volatile
    private var eyeTrackingEnabled = true

    actual fun initialize(): Boolean = true

    actual fun loadModel(modelPath: String): Boolean {
        android.util.Log.i("Live2DManager", "loadModel called: $modelPath")
        lastLoadedModelPath = modelPath

        // 解析模型的 LipSync 参数 ID（从 model3.json Groups）
        try {
            val json = context.assets.open(modelPath).bufferedReader().use { it.readText() }
            val ids = Live2DJsonParser.parseLipSyncIds(json)
            if (ids.isNotEmpty()) {
                lipSyncParamIds = ids
                android.util.Log.i("Live2DManager", "LipSync params: $ids")
            } else {
                lipSyncParamIds = Live2DJsonParser.DEFAULT_LIP_SYNC_PARAMS // fallback
                android.util.Log.i("Live2DManager", "LipSync params: fallback to ParamMouthOpenY")
            }
        } catch (e: Exception) {
            lipSyncParamIds = Live2DJsonParser.DEFAULT_LIP_SYNC_PARAMS
            android.util.Log.w("Live2DManager", "Cannot parse LipSync IDs, using fallback", e)
        }

        val r = renderer
        val surface = glSurfaceView
        if (r != null && surface != null) {
            android.util.Log.i("Live2DManager", "GL available, queuing load on GL thread")
            surface.queueEvent { r.requestLoadModel(modelPath) }
        } else {
            android.util.Log.i("Live2DManager", "GL not ready, saving as pending")
            pendingModelPath = modelPath
        }
        return true
    }

    actual fun setParameterValue(id: String, value: Float, weight: Float) {
        val r = renderer ?: return
        val s = glSurfaceView ?: return
        s.queueEvent { r.nativeSetParameterValue(id, value, weight) }
    }

    actual fun setLipSync(value: Float) {
        lipSyncValue = value
    }

    /**
     * 设置用户拖拽/缩放变换。
     * @param scale  缩放倍率 (1.0 = 原始)
     * @param offsetX 水平偏移 (NDC, -1..1)
     * @param offsetY 垂直偏移 (NDC, -1..1)
     */
    actual fun setModelTransform(scale: Float, offsetX: Float, offsetY: Float) {
        val r = renderer ?: return
        val s = glSurfaceView ?: return
        s.queueEvent { r.nativeSetModelTransform(scale, offsetX, offsetY) }
    }

    actual fun playMotion(group: String, index: Int, priority: Int) {
        val r = renderer ?: return
        val s = glSurfaceView ?: return
        s.queueEvent { r.nativeStartMotion(group, index, priority) }
    }

    actual fun setExpression(expressionId: String) {
        val r = renderer ?: return
        val s = glSurfaceView ?: return
        s.queueEvent { r.nativeSetExpression(expressionId) }
    }

    fun bindSurface(surface: GLSurfaceView) {
        glSurfaceView = surface
        // 如果有明确的 pending，使用它；否则用上次加载的模型路径（GL 上下文重建场景）
        val pending = pendingModelPath ?: lastLoadedModelPath
        pendingModelPath = null
        android.util.Log.i("Live2DManager", "bindSurface called, pending=$pending (last=$lastLoadedModelPath)")

        renderer = Live2DRenderer(context).also { r ->
            // 将待加载模型路径传给 renderer，在 onSurfaceCreated 后加载
            if (pending != null) {
                android.util.Log.i("Live2DManager", "Passing pending path to renderer")
                r.pendingModelPath = pending
            }
            surface.setEGLContextClientVersion(2)
            surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // RGBA8 + depth16, no stencil
            surface.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            surface.setZOrderOnTop(true)
            surface.setRenderer(r)
            surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        renderer?.onFrameUpdate = this::onFrameUpdate
    }

    private fun onFrameUpdate() {
        applyLipSync()
        applyGaze()
    }

    private fun applyLipSync() {
        val r = renderer ?: return
        for (paramId in lipSyncParamIds) {
            r.nativeSetParameterValue(paramId, lipSyncValue)
        }
    }

    // ===== 视线跟随 =====

    /**
     * 每帧应用视线参数到 Live2D 模型。
     * 对齐原项目 FocusController → ParamEyeBallX/Y, ParamAngleX/Y/Z, ParamBodyAngleX
     */
    private fun applyGaze() {
        val r = renderer ?: return
        val (focusX, focusY) = gazeController.update(System.currentTimeMillis())

        // ParamEyeBallX/Y: 眼球方向 ±1
        r.nativeSetParameterValue("ParamEyeBallX", focusX)
        r.nativeSetParameterValue("ParamEyeBallY", focusY)
        // ParamAngleX/Y: 脸部朝向 ±30
        r.nativeSetParameterValue("ParamAngleX", focusX * 30f)
        r.nativeSetParameterValue("ParamAngleY", focusY * 30f)
        // ParamAngleZ: 脸部倾斜 (交叉项)
        r.nativeSetParameterValue("ParamAngleZ", focusX * focusY * -30f)
        // ParamBodyAngleX: 身体偏转 ±10
        r.nativeSetParameterValue("ParamBodyAngleX", focusX * 10f)
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
        if (!enabled) {
            gazeController.forceReset()
        }
    }

    /**
     * 从 assets 读取 model3.json 并解析 HitAreas。
     * 返回有效的 hitArea 名称列表（Name 为空时 fallback 到 Id）。
     */
    actual fun getModelHitAreas(modelPath: String): List<String> {
        return try {
            val json = context.assets.open(modelPath).bufferedReader().use { it.readText() }
            Live2DJsonParser.parseHitAreas(json)
        } catch (e: Exception) {
            android.util.Log.w("Live2DManager", "Cannot read model for hitAreas: $modelPath", e)
            emptyList()
        }
    }

    /**
     * 从 model3.json 提取完整的模型信息（对齐原项目 extractModelInfo）。
     *
     * 静态解析 model3.json 中的 Expressions、Motions、HitAreas，
     * 并尝试加载同目录下的 param-map.json 以获取语义映射。
     * availableParameters 需要运行时 JNI 访问，此处暂留空。
     */
    actual fun extractModelInfo(modelPath: String): ModelInfo? {
        return try {
            val json = context.assets.open(modelPath).bufferedReader().use { it.readText() }

            // 解析 expressions（FileReferences.Expressions 数组中的 Name 字段）
            val expressions = Live2DJsonParser.parseExpressions(json)

            // 解析 motions（FileReferences.Motions 对象）
            val motions = Live2DJsonParser.parseMotions(json)

            // 解析 hitAreas
            val hitAreas = Live2DJsonParser.parseHitAreas(json)

            // 尝试加载 param-map.json
            val modelDir = modelPath.substringBeforeLast('/', "")
            val paramMapPath = if (modelDir.isNotBlank()) "$modelDir/${Live2DJsonParser.PARAM_MAP_FILENAME}" else Live2DJsonParser.PARAM_MAP_FILENAME
            val paramMap = loadParamMap(context.assets, paramMapPath)

            // 构建 ModelInfo
            val modelInfo = ModelInfo(
                available = true,
                modelPath = modelPath,
                dimensions = Dimensions(0, 0),
                motions = motions,
                expressions = expressions,
                hitAreas = hitAreas,
                availableParameters = emptyList(), // 需运行时 JNI 获取，暂留空
                parameters = ScaleInfo(canScale = true, currentScale = 1f, userScale = 1f, baseScale = 1f),
            )

            // 如果有 param-map.json，附加映射字段
            if (paramMap != null) {
                Live2DJsonParser.enrichModelInfoWithParamMap(modelInfo, expressions, motions, paramMap)
            } else {
                modelInfo
            }
        } catch (e: Exception) {
            android.util.Log.w("Live2DManager", "Cannot extract model info: $modelPath", e)
            null
        }
    }

    companion object {
        private fun loadParamMap(assets: android.content.res.AssetManager, path: String): ParamMapData? {
            return try {
                val text = assets.open(path).bufferedReader().use { it.readText() }
                Live2DJsonParser.parseParamMap(text)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * JNI bridge for Live2D rendering.
 */
class Live2DRenderer(private val context: Context) : GLSurfaceView.Renderer {
    var onFrameUpdate: (() -> Unit)? = null
    val assetManager: android.content.res.AssetManager = context.assets

    /** 待加载的模型路径 — 在 onSurfaceCreated 之后执行 */
    @Volatile
    var pendingModelPath: String? = null

    /** GL 线程上的初始化状态 */
    @Volatile
    private var glInitialized = false

    // JNI declarations
    external fun nativeInit(assetManager: android.content.res.AssetManager)
    external fun nativeLoadModel(assetManager: android.content.res.AssetManager, modelPath: String)
    external fun nativeStartMotion(group: String, index: Int, priority: Int)
    external fun nativeSetExpression(expressionId: String)
    external fun nativeSetParameterValue(paramId: String, value: Float, weight: Float = 1f)
    external fun nativeOnDrawFrame()
    external fun nativeOnSurfaceChanged(width: Int, height: Int)
    external fun nativeSetModelTransform(scale: Float, offsetX: Float, offsetY: Float)

    companion object {
        var nativeAvailable: Boolean = false
            private set

        init {
            try {
                System.loadLibrary("live2d_native")
                nativeAvailable = true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("Live2DRenderer", "Native library not available: ${e.message}")
                nativeAvailable = false
            }
        }
    }

    /**
     * 线程安全的模型加载请求（在 GL 线程调用）。
     * 如果 GL 已初始化则立即加载，否则暂存等 onSurfaceCreated 后加载。
     */
    fun requestLoadModel(path: String) {
        if (glInitialized) {
            nativeLoadModel(assetManager, path)
        } else {
            pendingModelPath = path
        }
    }

    // 纹理解码已完全移至 C++ (stb_image)，不再需要 Java 侧 decodeBitmapFromAssets

    override fun onSurfaceCreated(
        gl: javax.microedition.khronos.opengles.GL10?,
        config: javax.microedition.khronos.egl.EGLConfig?
    ) {
        if (nativeAvailable) {
            nativeInit(assetManager)
            glInitialized = true
            // GL 就绪后加载待加载的模型
            pendingModelPath?.let { path ->
                pendingModelPath = null
                android.util.Log.i("Live2DRenderer", "Loading pending model: $path")
                nativeLoadModel(assetManager, path)
            }
        }
    }

    override fun onSurfaceChanged(
        gl: javax.microedition.khronos.opengles.GL10?,
        width: Int,
        height: Int
    ) {
        if (nativeAvailable) nativeOnSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        onFrameUpdate?.invoke()
        if (nativeAvailable) nativeOnDrawFrame()
    }
}