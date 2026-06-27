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

    private val baseFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (target.x - sizePx / 2f).toInt()
            y = (target.y - sizePx / 2f).toInt()
        }
        windowManager.addView(view, params)
        overlayView = view
        layoutParams = params
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
            logger?.startSession(
                "interval=${interval}ms press=${currentConfig.pressDurationMs}ms " +
                    "fireImmediately=${currentConfig.fireImmediately} target=(${target.x.toInt()}, ${target.y.toInt()})",
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

    fun hideOverlayAndExit() {
        stopClicking(StopReason.EXIT)
        removeOverlay()
        overlayShown.value = false
        disableSelf()
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        layoutParams = null
    }

    // ---- 手势派发（FR-008 / FR-012）----

    private fun performTap() {
        val t = target
        clickCount++
        val n = clickCount
        logger?.logClick(t.x, t.y, n)
        val path = Path().apply { moveTo(t.x, t.y) }
        val stroke = GestureDescription.StrokeDescription(
            path, 0L, currentConfig.pressDurationMs.coerceAtLeast(1L),
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        // 派发瞬间使悬浮窗透传，避免点到控制按钮自身
        setOverlayTouchable(false)
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    logger?.logEvent("  gesture #$n onCompleted")
                    setOverlayTouchable(true)
                }
                override fun onCancelled(d: GestureDescription?) {
                    logger?.logEvent("  gesture #$n onCancelled")
                    setOverlayTouchable(true)
                }
            },
            null,
        )
        logger?.logEvent("  gesture #$n dispatchGesture returned=$dispatched")
        if (!dispatched) setOverlayTouchable(true)
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

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        val isRunning = MutableStateFlow(false)
        val overlayShown = MutableStateFlow(false)
        val serviceReady = MutableStateFlow(false)

        val running: StateFlow<Boolean> = isRunning
    }
}
