package com.gogoend.intervalclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gogoend.intervalclicker.data.ClickConfig
import com.gogoend.intervalclicker.data.ClickTarget
import com.gogoend.intervalclicker.logging.ClickLogger
import com.gogoend.intervalclicker.scheduler.ClickScheduler
import com.gogoend.intervalclicker.scheduler.StopReason
import com.gogoend.intervalclicker.ui.overlay.OverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 核心运行时：承载悬浮窗、手势派发与无堆积调度循环。见 contracts/accessibility-service.md。
 */
class ClickAccessibilityService : AccessibilityService(), OverlayView.Listener {

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var sizePx: Int = 0

    private var currentConfig: ClickConfig = ClickConfig()
    private var target: ClickTarget = ClickTarget(0f, 0f)

    private val scheduler = ClickScheduler { SystemClock.elapsedRealtime() }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var clickJob: Job? = null
    private var clickCount = 0
    private var logger: ClickLogger? = null
    private var overlayAttached = false

    private val baseFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        logger = ClickLogger(applicationContext)
        instance = this
        serviceReady.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不需要监听事件 */ }

    override fun onInterrupt() { /* no-op */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        stopClicking(StopReason.EXIT)
        removeOverlay()
        serviceReady.value = false
        if (instance === this) instance = null
    }

    // ---- 对外能力 ----

    fun updateConfig(config: ClickConfig) {
        currentConfig = config.normalized()
        logger?.enabled = currentConfig.loggingEnabled
    }

    fun showOverlay(config: ClickConfig) {
        currentConfig = config.normalized()
        logger?.enabled = currentConfig.loggingEnabled
        if (overlayView != null) return

        val metrics = resources.displayMetrics
        sizePx = (minOf(metrics.widthPixels, metrics.heightPixels) * 0.55f).toInt()
        target = ClickTarget.center(metrics.widthPixels, metrics.heightPixels)

        val view = OverlayView(this, sizePx, this)
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            baseFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (target.x - sizePx / 2f).toInt()
            y = (target.y - sizePx / 2f).toInt()
            // 关键：窗口不透明度需 ≤ maximumObscuringOpacityForTouch（默认 0.8），
            // 否则 Android 12+（在 Android 16 上默认强制）会把"被本悬浮窗遮挡"的注入点击
            // 当作 untrusted touch 丢弃，导致目标 App（如相机）收不到点击。
            alpha = OVERLAY_ALPHA
        }
        windowManager.addView(view, params)
        overlayView = view
        layoutParams = params
        overlayAttached = true
        overlayShown.value = true
    }

    fun startClicking() {
        val view = overlayView ?: return
        if (isRunning.value) return
        isRunning.value = true
        view.setRunning(true)
        clickCount = 0

        logger?.enabled = currentConfig.loggingEnabled
        clickJob = scope.launch {
            val interval = currentConfig.intervalMs
            val m = resources.displayMetrics
            val lp = layoutParams
            logger?.startSession(
                "interval=${interval}ms press=${currentConfig.pressDurationMs}ms " +
                    "fireImmediately=${currentConfig.fireImmediately} target=(${target.x.toInt()}, ${target.y.toInt()}) " +
                    "display=${m.widthPixels}x${m.heightPixels} overlay=ACCESSIBILITY size=$sizePx " +
                    "rect=(${lp?.x},${lp?.y},${lp?.width},${lp?.height})",
            )
            val plan = scheduler.start(interval, currentConfig.fireImmediately)
            var next = plan.nextFireElapsed
            if (plan.fireNow) {
                performTap()
                next = scheduler.onClickCompleted(interval)
            }
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now >= next) {
                    performTap()
                    next = scheduler.onClickCompleted(interval)
                } else {
                    view.setFraction(scheduler.remainingFraction(next, interval))
                    delay(FRAME_MS)
                }
            }
        }
    }

    fun stopClicking(reason: StopReason) {
        if (isRunning.value) {
            logger?.logEvent("STOP reason=$reason totalClicks=$clickCount")
        }
        clickJob?.cancel()
        clickJob = null
        isRunning.value = false
        overlayView?.setRunning(false)
        overlayView?.setFraction(0f)
        setOverlayTouchable(true)
    }

    /**
     * 诊断：移除一切悬浮窗后，3 秒倒计时再向屏幕中心派发一次点击。
     * 用于隔离"悬浮窗干扰" vs "派发本身在本设备无效"。
     */
    fun testTapCenter() {
        removeOverlay()
        overlayShown.value = false
        logger?.enabled = true
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels / 2f
        logger?.startSession("DIAGNOSTIC no-overlay; will tap center ($cx, $cy) after 3s")
        scope.launch {
            delay(3000)
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + 1f, cy + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { logger?.logEvent("TEST tap onCompleted") }
                    override fun onCancelled(d: GestureDescription?) { logger?.logEvent("TEST tap onCancelled") }
                },
                null,
            )
            logger?.logEvent("TEST tap dispatched at ($cx, $cy) returned=$dispatched (no overlay)")
        }
    }

    fun hideOverlayAndExit() {
        stopClicking(StopReason.EXIT)
        removeOverlay()
        overlayShown.value = false
        disableSelf()
    }

    private fun removeOverlay() {
        if (overlayAttached) {
            overlayView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        }
        overlayAttached = false
        overlayView = null
        layoutParams = null
    }

    // ---- 手势派发（FR-008 / FR-012）----

    private suspend fun performTap() {
        val t = target
        clickCount++
        val n = clickCount
        logger?.logClick(t.x, t.y, n)

        // Android 16+ 会丢弃"被遮挡"的注入点击：可见悬浮窗（哪怕 NOT_TOUCHABLE）盖在落点上方，
        // 注入手势仍被目标判为 obscured 而不下发。故派发期间同步移除悬浮窗，结束后再恢复。
        detachOverlayForDispatch()
        try {
            // 让窗口移除真正生效（含 obscured 状态重算）。一帧（16ms）在部分设备/模拟器上不够，
            // 取较保守的沉降时间，确保派发时落点上方确实无任何本应用窗口。
            delay(OVERLAY_SETTLE_MS)
            // 注意：path 必须有非零长度，否则 Android 16+ 可能不把它识别为有效点击
            val path = Path().apply {
                moveTo(t.x, t.y)
                lineTo(t.x + 1f, t.y + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(
                path, 0L, currentConfig.pressDurationMs.coerceAtLeast(1L),
            )
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            suspendCancellableCoroutine { cont ->
                val dispatched = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription?) {
                            logger?.logEvent("  gesture #$n onCompleted")
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onCancelled(d: GestureDescription?) {
                            logger?.logEvent("  gesture #$n onCancelled")
                            if (cont.isActive) cont.resume(Unit)
                        }
                    },
                    null,
                )
                logger?.logEvent("  gesture #$n dispatchGesture returned=$dispatched")
                if (!dispatched && cont.isActive) cont.resume(Unit)
            }
        } finally {
            reattachOverlayAfterDispatch()
        }
    }

    /** 派发前同步移除悬浮窗（保留 view 与 params 以便恢复）。 */
    private fun detachOverlayForDispatch() {
        val view = overlayView ?: return
        if (!overlayAttached) return
        runCatching { windowManager.removeViewImmediate(view) }
        overlayAttached = false
    }

    /** 派发结束后恢复悬浮窗。 */
    private fun reattachOverlayAfterDispatch() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        if (overlayAttached) return
        runCatching { windowManager.addView(view, params) }
        overlayAttached = true
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        params.flags = if (touchable) {
            baseFlags
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // ---- OverlayView.Listener ----

    override fun onStartStopTap() {
        // 若这条出现在某次 CLICK 之后、而你并未触屏，说明注入的点击命中了悬浮窗自身（穿透失败）
        logger?.logEvent("overlay button TAPPED (running=${isRunning.value})")
        if (isRunning.value) stopClicking(StopReason.USER) else startClicking()
    }

    override fun onExitTap() {
        logger?.logEvent("overlay EXIT tapped")
        hideOverlayAndExit()
    }

    override fun onDrag(dxScreen: Float, dyScreen: Float) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        params.x += dxScreen.toInt()
        params.y += dyScreen.toInt()
        runCatching { windowManager.updateViewLayout(view, params) }
        target = ClickTarget(params.x + sizePx / 2f, params.y + sizePx / 2f)
    }

    companion object {
        private const val FRAME_MS = 16L
        private const val OVERLAY_SETTLE_MS = 90L

        /** 悬浮窗不透明度，≤ 系统 maximumObscuringOpacityForTouch（默认 0.8），避免注入点击被当作被遮挡而丢弃。 */
        private const val OVERLAY_ALPHA = 0.8f

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        val isRunning = MutableStateFlow(false)
        val overlayShown = MutableStateFlow(false)
        val serviceReady = MutableStateFlow(false)

        val running: StateFlow<Boolean> = isRunning
    }
}
