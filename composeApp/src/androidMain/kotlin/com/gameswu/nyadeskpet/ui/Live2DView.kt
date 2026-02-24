package com.gameswu.nyadeskpet.ui

import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.live2d.Live2DManager
import com.gameswu.nyadeskpet.live2d.Live2DRenderer
import org.koin.compose.koinInject

/**
 * Android Live2D 画布组件。
 *
 * - Native 可用时：使用 GLSurfaceView + Cubism Core 进行实时渲染
 * - Native 不可用时（兜底）：从 assets 加载模型纹理缩略图预览
 */
@Composable
actual fun Live2DCanvas(modifier: Modifier) {
    val context = LocalContext.current

    if (Live2DRenderer.nativeAvailable) {
        // ===== 正常路径：GLSurfaceView 渲染 =====
        val live2dManager: Live2DManager = koinInject()
        val glView = remember {
            GLSurfaceView(context).apply {
                live2dManager.bindSurface(this)
            }
        }
        AndroidView(factory = { glView }, modifier = modifier)
        DisposableEffect(Unit) {
            onDispose { glView.onPause() }
        }
    } else {
        // ===== 兜底路径：纹理预览 =====
        val settingsRepo: SettingsRepository = koinInject()
        val settings by settingsRepo.settings.collectAsState()
        val textureBitmap = remember(settings.modelPath) {
            resolveModelTexture(context, settings.modelPath)
        }
        Box(modifier = modifier.background(Color.Transparent), contentAlignment = Alignment.Center) {
            if (textureBitmap != null) {
                Image(
                    bitmap = textureBitmap,
                    contentDescription = "Live2D Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

/**
 * 从 assets 加载模型纹理缩略图（降采样避免 OOM）。
 */
private fun resolveModelTexture(
    context: android.content.Context,
    modelPath: String
): androidx.compose.ui.graphics.ImageBitmap? {
    if (modelPath.isBlank()) return null
    val assets = context.assets
    val modelDir = modelPath.substringBeforeLast('/')
    val opts = BitmapFactory.Options().apply { inSampleSize = 8 } // 8K/8 = 1K

    fun tryLoad(path: String): androidx.compose.ui.graphics.ImageBitmap? {
        return try {
            assets.open(path).use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
            }
        } catch (_: Exception) { null }
    }

    // 搜索子文件夹中的 texture_00.png
    try {
        val entries = assets.list(modelDir) ?: emptyArray()
        for (entry in entries) {
            tryLoad("$modelDir/$entry/texture_00.png")?.let { return it }
        }
    } catch (_: Exception) {}

    // 直接在模型目录查找
    return tryLoad("$modelDir/texture_00.png")
}