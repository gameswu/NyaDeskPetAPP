package com.gameswu.nyadeskpet.live2d

import android.content.Context
import android.opengl.GLSurfaceView
import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.agent.*
import kotlinx.coroutines.delay

/**
 * Android Live2D Manager implementation.
 */
actual class Live2DManager actual constructor(private val context: PlatformContext) {

    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: Live2DRenderer? = null
    private val paramOverrides = mutableMapOf<String, ParamOverride>()
    private var lipSyncValue = 0f
    @Volatile
    private var pendingModelPath: String? = null

    /** 记住上次成功请求加载的模型路径，用于 GL 上下文重建时自动重载 */
    @Volatile
    private var lastLoadedModelPath: String? = null

    actual fun initialize(): Boolean = true

    actual fun loadModel(modelPath: String): Boolean {
        android.util.Log.i("Live2DManager", "loadModel called: $modelPath")
        lastLoadedModelPath = modelPath
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

    fun setParameter(id: String, value: Float, weight: Float, durationMs: Int) {
        val now = System.currentTimeMillis()
        val startValue = paramOverrides[id]?.let { calculateCurrentOverrideValue(it) } ?: 0f
        val transitionDuration = if (durationMs > 0) durationMs.toLong() else 400L

        paramOverrides[id] = ParamOverride(
            targetValue = value, startValue = startValue, weight = weight, startTime = now,
            duration = transitionDuration, holdUntil = now + transitionDuration + 2000L,
            releaseEnd = now + transitionDuration + 2000L + 500L
        )
    }

    fun setParameters(params: List<ParamValue>) {
        params.forEach { p -> setParameter(p.id, p.value, p.blend ?: 1f, p.duration ?: 0) }
    }

    suspend fun executeSyncCommand(data: SyncCommandData) {
        for (action in data.actions) {
            when (action.type) {
                "motion" -> playMotion(action.group ?: "", action.index ?: 0, 2)
                "expression" -> setExpression(action.expressionId ?: "")
                "parameter" -> action.parameterId?.let {
                    setParameter(it, action.value ?: 0f, action.weight ?: 1f, action.duration?.toInt() ?: 0)
                }
            }
            if (action.waitComplete == true && action.duration != null) {
                delay(action.duration)
            }
        }
    }

    data class ParamValue(val id: String, val value: Float, val blend: Float?, val duration: Int?)

    private data class ParamOverride(
        val targetValue: Float, val startValue: Float, val weight: Float,
        val startTime: Long, val duration: Long, val holdUntil: Long, val releaseEnd: Long
    )

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

    fun setLipSyncTarget(value: Float) {
        lipSyncValue = (value * 0.7f + lipSyncValue * 0.3f)
    }

    private fun onFrameUpdate() {
        applyParameterOverrides()
        applyLipSync()
    }

    private fun applyParameterOverrides() {
        val now = System.currentTimeMillis()
        val r = renderer ?: return
        paramOverrides.entries.removeIf { (paramId, override) ->
            if (now >= override.releaseEnd) {
                r.nativeSetParameterValue(paramId, 0f, 0f)
                true
            } else {
                val (value, weight) = calculateOverrideState(override)
                r.nativeSetParameterValue(paramId, value, weight)
                false
            }
        }
    }

    private fun calculateOverrideState(o: ParamOverride): Pair<Float, Float> {
        val now = System.currentTimeMillis()
        val elapsed = now - o.startTime
        if (elapsed < o.duration) {
            val eased = easeInOutCubic(elapsed.toFloat() / o.duration)
            return (o.startValue + (o.targetValue - o.startValue) * eased) to o.weight
        }
        if (now < o.holdUntil) return o.targetValue to o.weight
        val releaseProgress = (now - o.holdUntil).toFloat() / (o.releaseEnd - o.holdUntil)
        return o.targetValue to (o.weight * (1 - easeInOutCubic(releaseProgress)))
    }

    private fun calculateCurrentOverrideValue(o: ParamOverride): Float {
        val now = System.currentTimeMillis()
        val elapsed = now - o.startTime
        if (elapsed < o.duration) {
            return o.startValue + (o.targetValue - o.startValue) * easeInOutCubic(elapsed.toFloat() / o.duration)
        }
        return o.targetValue
    }

    private fun applyLipSync() {
        val r = renderer ?: return
        r.nativeSetParameterValue("ParamMouthOpenY", lipSyncValue)
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    /**
     * 从 assets 读取 model3.json 并解析 HitAreas。
     * 返回有效的 hitArea 名称列表（Name 为空时 fallback 到 Id）。
     */
    actual fun getModelHitAreas(modelPath: String): List<String> {
        return try {
            val json = context.assets.open(modelPath).bufferedReader().use { it.readText() }
            parseHitAreas(json)
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
            val expressions = parseExpressions(json)

            // 解析 motions（FileReferences.Motions 对象）
            val motions = parseMotions(json)

            // 解析 hitAreas
            val hitAreas = parseHitAreas(json)

            // 尝试加载 param-map.json
            val modelDir = modelPath.substringBeforeLast('/', "")
            val paramMapPath = if (modelDir.isNotBlank()) "$modelDir/$PARAM_MAP_FILENAME" else PARAM_MAP_FILENAME
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
                enrichModelInfoWithParamMap(modelInfo, expressions, motions, paramMap)
            } else {
                modelInfo
            }
        } catch (e: Exception) {
            android.util.Log.w("Live2DManager", "Cannot extract model info: $modelPath", e)
            null
        }
    }

    /**
     * 将 param-map.json 的映射附加到 ModelInfo。
     * 对齐原项目 _enrichModelInfoWithParamMap 逻辑。
     */
    private fun enrichModelInfoWithParamMap(
        base: ModelInfo,
        expressions: List<String>,
        motions: Map<String, MotionGroup>,
        paramMap: ParamMapData,
    ): ModelInfo {
        val mappedParams = if (paramMap.parameters.isNotEmpty()) {
            paramMap.parameters.map { entry ->
                MappedParameter(
                    id = entry.id, alias = entry.alias, description = entry.description,
                    min = 0f, max = 1f, default = 0f, // 运行时参数范围待 JNI 补充
                )
            }
        } else null

        val validExps = expressions.toSet()
        val mappedExps = if (paramMap.expressions.isNotEmpty()) {
            paramMap.expressions.filter { it.id in validExps }.map { entry ->
                MappedExpression(id = entry.id, alias = entry.alias, description = entry.description)
            }.takeIf { it.isNotEmpty() }
        } else null

        val mappedMots = if (paramMap.motions.isNotEmpty()) {
            paramMap.motions.filter { entry ->
                val group = motions[entry.group]
                group != null && entry.index >= 0 && entry.index < group.count
            }.map { entry ->
                MappedMotion(
                    group = entry.group, index = entry.index,
                    alias = entry.alias, description = entry.description,
                )
            }.takeIf { it.isNotEmpty() }
        } else null

        return base.copy(
            mappedParameters = mappedParams,
            mappedExpressions = mappedExps,
            mappedMotions = mappedMots,
        )
    }

    companion object {
        private const val PARAM_MAP_FILENAME = "param-map.json"
        /**
         * 解析 model3.json 中的 HitAreas 数组。
         * 格式：{ "HitAreas": [ { "Id": "HitAreaHead", "Name": "Head" }, ... ] }
         * Name 为空时 fallback 到 Id（对齐原项目 patchHitAreas 逻辑）。
         */
        internal fun parseHitAreas(json: String): List<String> {
            val result = mutableListOf<String>()
            val hitAreasKey = "\"HitAreas\""
            val start = json.indexOf(hitAreasKey)
            if (start == -1) return result

            // 找到 [ 开始
            val arrayStart = json.indexOf('[', start + hitAreasKey.length)
            if (arrayStart == -1) return result

            // 找到匹配的 ]
            var depth = 0
            var pos = arrayStart
            var arrayEnd = -1
            while (pos < json.length) {
                when (json[pos]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) { arrayEnd = pos; break }
                    }
                }
                pos++
            }
            if (arrayEnd == -1) return result

            val arrayContent = json.substring(arrayStart, arrayEnd + 1)

            // 逐个解析 { ... } 对象
            var objStart = arrayContent.indexOf('{')
            while (objStart != -1) {
                val objEnd = arrayContent.indexOf('}', objStart)
                if (objEnd == -1) break
                val obj = arrayContent.substring(objStart, objEnd + 1)

                val id = extractJsonStringValue(obj, "Id")
                val name = extractJsonStringValue(obj, "Name")
                // Name 非空优先使用，否则 fallback 到 Id
                val effectiveName = name.ifBlank { id }
                if (effectiveName.isNotBlank()) {
                    result.add(effectiveName)
                }
                objStart = arrayContent.indexOf('{', objEnd + 1)
            }
            return result
        }

        private fun extractJsonStringValue(json: String, key: String): String {
            val keyStr = "\"$key\""
            val keyPos = json.indexOf(keyStr)
            if (keyPos == -1) return ""
            var pos = keyPos + keyStr.length
            // skip whitespace and colon
            while (pos < json.length && json[pos] in " \t\n\r:") pos++
            if (pos >= json.length || json[pos] != '"') return ""
            val valueStart = pos + 1
            val valueEnd = json.indexOf('"', valueStart)
            return if (valueEnd == -1) "" else json.substring(valueStart, valueEnd)
        }

        /**
         * 解析 model3.json 中的 Expressions 数组。
         * 格式：{ "FileReferences": { "Expressions": [ { "Name": "f01", "File": "..." }, ... ] } }
         * 对齐原项目 extractModelInfo 中的 expressions 提取逻辑。
         */
        internal fun parseExpressions(json: String): List<String> {
            val result = mutableListOf<String>()
            val expKey = "\"Expressions\""
            val start = json.indexOf(expKey)
            if (start == -1) return result

            val arrayStart = json.indexOf('[', start + expKey.length)
            if (arrayStart == -1) return result

            val arrayEnd = findMatchingBracket(json, arrayStart, '[', ']')
            if (arrayEnd == -1) return result

            val arrayContent = json.substring(arrayStart, arrayEnd + 1)

            var objStart = arrayContent.indexOf('{')
            while (objStart != -1) {
                val objEnd = arrayContent.indexOf('}', objStart)
                if (objEnd == -1) break
                val obj = arrayContent.substring(objStart, objEnd + 1)
                val name = extractJsonStringValue(obj, "Name")
                if (name.isNotBlank()) {
                    result.add(name)
                }
                objStart = arrayContent.indexOf('{', objEnd + 1)
            }
            return result
        }

        /**
         * 解析 model3.json 中的 Motions 对象。
         * 格式：{ "FileReferences": { "Motions": { "Idle": [...], "TapBody": [...] } } }
         * 对齐原项目 extractModelInfo 逻辑，空组名 fallback 为 "Default"。
         */
        internal fun parseMotions(json: String): Map<String, MotionGroup> {
            val result = mutableMapOf<String, MotionGroup>()
            val motionsKey = "\"Motions\""
            val start = json.indexOf(motionsKey)
            if (start == -1) return result

            // 找到 Motions 后面的 {
            val objStart = json.indexOf('{', start + motionsKey.length)
            if (objStart == -1) return result

            val objEnd = findMatchingBracket(json, objStart, '{', '}')
            if (objEnd == -1) return result

            val motionsContent = json.substring(objStart + 1, objEnd)

            // 逐个解析组名和对应的数组
            var pos = 0
            while (pos < motionsContent.length) {
                // 找到组名
                val keyStart = motionsContent.indexOf('"', pos)
                if (keyStart == -1) break
                val keyEnd = motionsContent.indexOf('"', keyStart + 1)
                if (keyEnd == -1) break
                val groupName = motionsContent.substring(keyStart + 1, keyEnd)
                val displayName = groupName.ifBlank { "Default" }

                // 找到数组
                val arrStart = motionsContent.indexOf('[', keyEnd)
                if (arrStart == -1) break
                val arrEnd = findMatchingBracket(motionsContent, arrStart, '[', ']')
                if (arrEnd == -1) break

                val arrContent = motionsContent.substring(arrStart, arrEnd + 1)

                // 统计文件数和提取文件名
                val files = mutableListOf<String>()
                var fPos = 0
                while (true) {
                    val fileKeyPos = arrContent.indexOf("\"File\"", fPos)
                    if (fileKeyPos == -1) break
                    val file = extractJsonStringValue(arrContent.substring(fileKeyPos), "File")
                    if (file.isNotBlank()) files.add(file)
                    fPos = fileKeyPos + 6
                }

                result[displayName] = MotionGroup(count = files.size.coerceAtLeast(1), files = files)
                // 前进到数组结束之后
                pos = arrEnd + 1
            }
            return result
        }

        /**
         * 加载 param-map.json（语义映射表）。
         * 对齐原项目 loadParamMap 逻辑。
         */
        internal fun loadParamMap(context: android.content.res.AssetManager, path: String): ParamMapData? {
            return try {
                val text = context.open(path).bufferedReader().use { it.readText() }
                parseParamMap(text)
            } catch (_: Exception) {
                null // 文件不存在或读取失败
            }
        }

        internal fun parseParamMap(json: String): ParamMapData? {
            // 简单解析 version
            val versionKey = "\"version\""
            val versionPos = json.indexOf(versionKey)
            if (versionPos == -1) return null
            // 提取 version 数字
            var vp = versionPos + versionKey.length
            while (vp < json.length && json[vp] in " \t\n\r:") vp++
            val vStart = vp
            while (vp < json.length && json[vp].isDigit()) vp++
            val version = json.substring(vStart, vp).toIntOrNull() ?: return null
            if (version != 1) return null

            val parameters = parseParamMapParameters(json)
            val expressions = parseParamMapExpressions(json)
            val motions = parseParamMapMotions(json)

            return ParamMapData(parameters, expressions, motions)
        }

        private fun parseParamMapParameters(json: String): List<ParamMapEntry> {
            val result = mutableListOf<ParamMapEntry>()
            // 找 "parameters" 键
            val key = "\"parameters\""
            val start = json.indexOf(key)
            if (start == -1) return result
            val arrStart = json.indexOf('[', start + key.length)
            if (arrStart == -1) return result
            val arrEnd = findMatchingBracket(json, arrStart, '[', ']')
            if (arrEnd == -1) return result
            val content = json.substring(arrStart, arrEnd + 1)

            var objStart = content.indexOf('{')
            while (objStart != -1) {
                val objEnd = content.indexOf('}', objStart)
                if (objEnd == -1) break
                val obj = content.substring(objStart, objEnd + 1)
                val id = extractJsonStringValue(obj, "id")
                val alias = extractJsonStringValue(obj, "alias")
                val description = extractJsonStringValue(obj, "description")
                if (id.isNotBlank() && alias.isNotBlank()) {
                    result.add(ParamMapEntry(id, alias, description))
                }
                objStart = content.indexOf('{', objEnd + 1)
            }
            return result
        }

        private fun parseParamMapExpressions(json: String): List<ParamMapEntry> {
            val result = mutableListOf<ParamMapEntry>()
            val key = "\"expressions\""
            val start = json.indexOf(key)
            if (start == -1) return result
            val arrStart = json.indexOf('[', start + key.length)
            if (arrStart == -1) return result
            val arrEnd = findMatchingBracket(json, arrStart, '[', ']')
            if (arrEnd == -1) return result
            val content = json.substring(arrStart, arrEnd + 1)

            var objStart = content.indexOf('{')
            while (objStart != -1) {
                val objEnd = content.indexOf('}', objStart)
                if (objEnd == -1) break
                val obj = content.substring(objStart, objEnd + 1)
                val id = extractJsonStringValue(obj, "id")
                val alias = extractJsonStringValue(obj, "alias")
                val description = extractJsonStringValue(obj, "description")
                if (id.isNotBlank() && alias.isNotBlank()) {
                    result.add(ParamMapEntry(id, alias, description))
                }
                objStart = content.indexOf('{', objEnd + 1)
            }
            return result
        }

        private fun parseParamMapMotions(json: String): List<ParamMapMotionEntry> {
            val result = mutableListOf<ParamMapMotionEntry>()
            val key = "\"motions\""
            val start = json.indexOf(key)
            if (start == -1) return result
            val arrStart = json.indexOf('[', start + key.length)
            if (arrStart == -1) return result
            val arrEnd = findMatchingBracket(json, arrStart, '[', ']')
            if (arrEnd == -1) return result
            val content = json.substring(arrStart, arrEnd + 1)

            var objStart = content.indexOf('{')
            while (objStart != -1) {
                val objEnd = content.indexOf('}', objStart)
                if (objEnd == -1) break
                val obj = content.substring(objStart, objEnd + 1)
                val group = extractJsonStringValue(obj, "group")
                val alias = extractJsonStringValue(obj, "alias")
                val description = extractJsonStringValue(obj, "description")
                // 提取 index（数字，不是字符串）
                val indexStr = extractJsonNumberValue(obj, "index")
                val index = indexStr.toIntOrNull() ?: 0
                if (group.isNotBlank() && alias.isNotBlank()) {
                    result.add(ParamMapMotionEntry(group, index, alias, description))
                }
                objStart = content.indexOf('{', objEnd + 1)
            }
            return result
        }

        private fun extractJsonNumberValue(json: String, key: String): String {
            val keyStr = "\"$key\""
            val keyPos = json.indexOf(keyStr)
            if (keyPos == -1) return ""
            var pos = keyPos + keyStr.length
            while (pos < json.length && json[pos] in " \t\n\r:") pos++
            val vStart = pos
            while (pos < json.length && (json[pos].isDigit() || json[pos] == '-' || json[pos] == '.')) pos++
            return json.substring(vStart, pos)
        }

        private fun findMatchingBracket(json: String, start: Int, open: Char, close: Char): Int {
            var depth = 0
            var pos = start
            while (pos < json.length) {
                when (json[pos]) {
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return pos
                    }
                }
                pos++
            }
            return -1
        }
    }
}

/** param-map.json 解析结果 */
internal data class ParamMapData(
    val parameters: List<ParamMapEntry>,
    val expressions: List<ParamMapEntry>,
    val motions: List<ParamMapMotionEntry>,
)
internal data class ParamMapEntry(val id: String, val alias: String, val description: String)
internal data class ParamMapMotionEntry(val group: String, val index: Int, val alias: String, val description: String)

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
    external fun nativeGetParameterValue(paramId: String): Float
    external fun nativeGetParameterRange(paramId: String): Float
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