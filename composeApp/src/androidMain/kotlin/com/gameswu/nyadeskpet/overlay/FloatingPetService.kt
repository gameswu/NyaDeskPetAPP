package com.gameswu.nyadeskpet.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.gameswu.nyadeskpet.MainActivity
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.live2d.GazeController
import com.gameswu.nyadeskpet.live2d.Live2DManager
import com.gameswu.nyadeskpet.live2d.Live2DRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.android.inject
import kotlin.math.abs

/**
 * 悬浮宠物前台服务。
 *
 * 功能：
 * - 在系统桌面上显示透明背景的 Live2D 宠物悬浮窗
 * - 支持手指拖拽移动位置
 * - 支持双击返回应用
 * - 支持通过通知栏按钮关闭悬浮窗
 * - 使用前台服务保证后台不被系统杀死
 */
class FloatingPetService : Service() {

    companion object {
        private const val TAG = "FloatingPetService"
        private const val CHANNEL_ID = "floating_pet_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.gameswu.nyadeskpet.STOP_FLOATING_PET"

        /** 悬浮窗默认尺寸 (dp) */
        private const val DEFAULT_SIZE_DP = 200

        /** 悬浮窗默认初始位置 (px) */
        private const val DEFAULT_WINDOW_X = 100
        private const val DEFAULT_WINDOW_Y = 200

        fun start(context: Context) {
            if (_isRunning.value) {
                Log.w(TAG, "Service already running, ignoring start")
                return
            }
            val intent = Intent(context, FloatingPetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!_isRunning.value) return
            context.stopService(Intent(context, FloatingPetService::class.java))
        }

        // ===== 响应式状态：单一可信来源 =====
        private val _isRunning = MutableStateFlow(false)
        /** 悬浮窗运行状态的可观察流 */
        val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()
        /** 便捷访问当前值 */
        val isRunning: Boolean get() = _isRunning.value
    }

    private val live2dManager: Live2DManager by inject()
    private val settingsRepo: SettingsRepository by inject()

    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var overlayRenderer: Live2DRenderer? = null

    // 悬浮窗独立的视线跟随控制器
    private val gazeController = GazeController()

    // 悬浮窗位置状态
    private var windowX = DEFAULT_WINDOW_X
    private var windowY = DEFAULT_WINDOW_Y

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        _isRunning.value = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        _isRunning.value = false
        removeOverlayWindow()
        super.onDestroy()
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮宠物",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "悬浮宠物运行中"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // 点击通知返回主界面
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 关闭悬浮窗按钮
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingPetService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("悬浮宠物运行中")
            .setContentText("点击返回应用 | 下拉关闭")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopIntent)
            .build()
    }

    // ==================== 悬浮窗创建 ====================

    private fun createOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = (DEFAULT_SIZE_DP * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // 关键 flag：透明背景 + 不获取焦点
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = windowX
            y = windowY
        }

        // 容器 FrameLayout — 承载 GLSurfaceView
        val container = FrameLayout(this).apply {
            // 透明背景
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // 创建 GLSurfaceView 用于 Live2D 渲染
        val glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderOnTop(true)
        }

        // 绑定 Live2D 渲染器
        val renderer = Live2DRenderer(this).also { r ->
            val modelPath = settingsRepo.current.modelPath
            if (modelPath.isNotBlank()) {
                r.pendingModelPath = modelPath
            }
            glView.setRenderer(r)
            glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlayRenderer = renderer

        // 悬浮窗模式下的每帧回调：应用视线跟随参数
        renderer.onFrameUpdate = {
            if (settingsRepo.current.enableEyeTracking) {
                applyOverlayGaze(renderer)
            }
        }

        container.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        // 触摸事件：拖拽移动 + 双击返回应用
        setupTouchListener(container, params)

        windowManager?.addView(container, params)
        overlayContainer = container
        glSurfaceView = glView

        Log.i(TAG, "Overlay window created, size=${sizePx}px")
    }

    // ==================== 触摸处理 ====================

    private fun setupTouchListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastClickTime = 0L
        val doubleClickThreshold = 300L
        val dragThreshold = 10f

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    // 视线跟随：按下时跟踪触摸点
                    if (settingsRepo.current.enableEyeTracking) {
                        updateGazeFromTouch(event, v)
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 多指按下：选最近的触摸点
                    if (settingsRepo.current.enableEyeTracking) {
                        updateGazeFromTouch(event, v)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 只用第一根手指做拖拽
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowX = params.x
                    windowY = params.y
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) { }

                    // 视线跟随：移动时持续更新
                    if (settingsRepo.current.enableEyeTracking) {
                        updateGazeFromTouch(event, v)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)

                    // 如果没有拖拽，视为点击
                    if (dx < dragThreshold && dy < dragThreshold) {
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < doubleClickThreshold) {
                            // 双击：返回应用
                            openMainActivity()
                            lastClickTime = 0
                        } else {
                            lastClickTime = now
                        }
                    }

                    // 视线跟随：所有手指抬起，保持最后方向
                    if (settingsRepo.current.enableEyeTracking) {
                        gazeController.clearTarget()
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // 还有手指按着，更新视线到剩余最近的点
                    if (settingsRepo.current.enableEyeTracking) {
                        // 延迟到下一个 MOVE 事件再更新
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 从触摸事件的所有活跃触摸点中选择离当前视线最近的一个，
     * 将其坐标转换为归一化视线目标 (-1..1)。
     */
    private fun updateGazeFromTouch(event: MotionEvent, view: View) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0 || h <= 0) return

        val pointerCount = event.pointerCount
        var bestDist = Float.MAX_VALUE
        var bestNx = 0f
        var bestNy = 0f

        for (i in 0 until pointerCount) {
            val px = event.getX(i)
            val py = event.getY(i)
            // 归一化到 -1..1
            val nx = (px / w) * 2f - 1f
            val ny = -((py / h) * 2f - 1f)

            val dx = nx - gazeController.currentX
            val dy = ny - gazeController.currentY
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestNx = nx
                bestNy = ny
            }
        }

        gazeController.setTarget(bestNx, bestNy)
    }

    /**
     * 在 GL 渲染线程每帧调用，用悬浮窗独立的 GazeController 更新视线参数。
     */
    private fun applyOverlayGaze(r: Live2DRenderer) {
        val (focusX, focusY) = gazeController.update(System.currentTimeMillis())

        r.nativeSetParameterValue("ParamEyeBallX", focusX)
        r.nativeSetParameterValue("ParamEyeBallY", focusY)
        r.nativeSetParameterValue("ParamAngleX", focusX * 30f)
        r.nativeSetParameterValue("ParamAngleY", focusY * 30f)
        r.nativeSetParameterValue("ParamAngleZ", focusX * focusY * -30f)
        r.nativeSetParameterValue("ParamBodyAngleX", focusX * 10f)
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        // 不在这里 stopSelf()，而是让 MainActivity.onResume 统一处理停止逻辑，
        // 保证「先恢复应用内渲染 → 再销毁悬浮窗渲染」的顺序。
    }

    // ==================== 清理 ====================

    private fun removeOverlayWindow() {
        // 先暂停 GL 渲染，确保停止 native 调用
        try {
            glSurfaceView?.onPause()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing GL: ${e.message}")
        }

        // 从窗口管理器中移除
        try {
            overlayContainer?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing overlay: ${e.message}")
        }
        overlayContainer = null
        glSurfaceView = null
        overlayRenderer = null

        Log.i(TAG, "Overlay window removed")
    }
}
