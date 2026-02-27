@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
@file:Suppress("DEPRECATION")

package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.gameswu.nyadeskpet.live2d.Live2DManager
import kotlinx.cinterop.*
import live2d.*
import org.koin.compose.koinInject
import platform.CoreGraphics.CGRect
import platform.Foundation.*
import platform.GLKit.*
import platform.EAGL.*
import platform.QuartzCore.CADisplayLink
import platform.UIKit.*
import platform.darwin.NSObject

/**
 * iOS Live2D 画布组件 — 使用 GLKView + EAGLContext + Cubism C Bridge 渲染。
 *
 * 使用 GLKView 的正规 delegate 渲染流程：
 * CADisplayLink → render() → glkView.display()
 *   → bindDrawable() → delegate.drawInRect() → presentRenderbuffer
 */
@Composable
actual fun Live2DCanvas(modifier: Modifier) {
    val live2dManager: Live2DManager = koinInject()
    val displayLinkRef = remember { mutableStateOf<CADisplayLink?>(null) }

    UIKitView(
        factory = {
            val eaglContext = EAGLContext(kEAGLRenderingAPIOpenGLES2)

            val glkView = GLKView().apply {
                this.context = eaglContext
                drawableColorFormat = GLKViewDrawableColorFormatRGBA8888
                drawableDepthFormat = GLKViewDrawableDepthFormatNone
                drawableStencilFormat = GLKViewDrawableStencilFormat8
                opaque = false
                backgroundColor = UIColor.clearColor
                // 确保 Retina 分辨率渲染 — 创建时即设定，保证首次 bindDrawable 使用正确尺寸
                contentScaleFactor = UIScreen.mainScreen.scale
                // 禁止 UIKit 自动触发 setNeedsDisplay 重绘，由 CADisplayLink 驱动
                enableSetNeedsDisplay = false
            }

            // 同一对象同时作为 GLKView delegate（渲染回调）和 CADisplayLink target（帧驱动）
            // CADisplayLink 会 retain target，GLKView.delegate 是 weak — 无循环引用
            val renderer = object : NSObject(), GLKViewDelegateProtocol {
                /**
                 * GLKView.display() 内部依次调用 bindDrawable() → 本方法 → presentRenderbuffer。
                 * 此时 FBO 已绑定，直接进行 GL 渲染即可。
                 */
                override fun glkView(view: GLKView, drawInRect: CValue<CGRect>) {
                    // Safety: ensure GLKView's drawable FBO is bound.
                    // display() should do this, but we ensure it for robustness.
                    view.bindDrawable()

                    val w = view.drawableWidth.toInt()
                    val h = view.drawableHeight.toInt()
                    if (w > 0 && h > 0) {
                        L2DBridge_OnSurfaceChanged(w, h)
                        L2DBridge_OnDrawFrame()
                    }
                }

                /** CADisplayLink 每帧调用 — 加载模型、更新动画、触发渲染 */
                @ObjCAction
                fun render() {
                    EAGLContext.setCurrentContext(eaglContext)
                    live2dManager.loadPendingModelOnGLThread()
                    live2dManager.onFrameUpdate()
                    // display() = bindDrawable + delegate.drawInRect + presentRenderbuffer
                    glkView.display()
                }
            }

            glkView.delegate = renderer

            // 在 GL 线程初始化 Live2D Bridge（创建 shader 等）
            EAGLContext.setCurrentContext(eaglContext)
            live2dManager.initOnGLThread()

            // CADisplayLink 持续驱动 60fps 渲染
            val displayLink = CADisplayLink.displayLinkWithTarget(
                target = renderer,
                selector = NSSelectorFromString("render")
            )
            displayLink.addToRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
            displayLink.preferredFramesPerSecond = 60
            displayLinkRef.value = displayLink

            glkView
        },
        modifier = modifier,
        onRelease = { _ ->
            displayLinkRef.value?.invalidate()
            displayLinkRef.value = null
            L2DBridge_Cleanup()
        }
    )
}