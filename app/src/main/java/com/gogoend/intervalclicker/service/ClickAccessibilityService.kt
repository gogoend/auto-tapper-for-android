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
import com.gogoend.intervalclicker.ui.overlay.ControlBarView
import com.gogoend.intervalclicker.ui.overlay.CrosshairView
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
import kotlin.math.max

/**
 * 核心运行时：承载悬浮窗、手势派发与无堆积调度循环。见 contracts/accessibility-service.md。
 *
 * 悬浮层分为两个独立窗口：
 *  - 准星视觉层（CrosshairView）：永久 NOT_TOUCHABLE，位于点击落点之上；仅在每次派发点击的瞬间
 *    被临时移除，避免 Android 16+ 把"被遮挡"的注入点击丢弃。
 *  - 控制条（ControlBarView）：可触摸，放在偏离落点的位置，始终存在、不参与移除（故不闪烁），
 *    也不会被注入点击误触。
 */
class ClickAccessibilityService :
    AccessibilityService(),
    ControlBarView.Listener,
    CrosshairView.Listener {

    private lateinit var windowManager: WindowManager

    private var crosshairView: CrosshairView? = null
    private var crosshairParams: WindowManager.LayoutParams? = null
    private var crosshairAttached = false

    private var controlView: ControlBarView? = null
    private var controlParams: WindowManager.LayoutParams? = null

    private var csSize = 0
    private var controlW = 0
    private var controlH = 0
    private var screenW = 0
    private var screenH = 0

    private var currentConfig: ClickConfig = ClickConfig()
    private var target: ClickTarget = ClickTarget(0f, 0f)

    private val scheduler = ClickScheduler { SystemClock.elapsedRealtime() }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var clickJob: Job? = null
    private var clickCount = 0
    private var logger: ClickLogger? = null

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
        if (crosshairView != null) return

        val m = resources.displayMetrics
        screenW = m.widthPixels
        screenH = m.heightPixels
        val density = m.density
        csSize = (minOf(screenW, screenH) * 0.28f).toInt()
        val buttonR = 26f * density
        val gap = 12f * density
        // 控制条只有 2 个按钮（拖拽手柄 + 退出）
        controlW = (gap * 3 + buttonR * 4).toInt()
        controlH = (buttonR * 2 + gap * 2).toInt()
        target = ClickTarget.center(screenW, screenH)

        // 准星 + 中心开始/停止按钮（可触摸；仅在派发瞬间临时移除）
        val cs = CrosshairView(this, csSize, this)
        val csParams = WindowManager.LayoutParams(
            csSize,
            csSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            baseFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = OVERLAY_ALPHA
        }
        crosshairView = cs
        crosshairParams = csParams
        positionCrosshair()
        windowManager.addView(cs, csParams)
        crosshairAttached = true

        // 控制条（可触摸，偏离落点）
        val cv = ControlBarView(this, buttonR, gap, this)
        val cvParams = WindowManager.LayoutParams(
            controlW,
            controlH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            baseFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        controlView = cv
        controlParams = cvParams
        positionControl()
        windowManager.addView(cv, cvParams)

        overlayShown.value = true
    }

    /** 准星窗口位置：中心对准 target。 */
    private fun positionCrosshair() {
        val params = crosshairParams ?: return
        params.x = (target.x - csSize / 2f).toInt()
        params.y = (target.y - csSize / 2f).toInt()
        if (crosshairAttached) {
            crosshairView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        }
    }

    /** 控制条位置：默认放在准星下方一段距离；下方放不下则放上方；并夹取到屏幕内。不覆盖 target。 */
    private fun positionControl() {
        val params = controlParams ?: return
        val belowY = (target.y + csSize / 2f + csSize * 0.12f).toInt()
        val aboveY = (target.y - csSize / 2f - csSize * 0.12f - controlH).toInt()
        var top = if (belowY + controlH <= screenH) belowY else aboveY
        var left = (target.x - controlW / 2f).toInt()
        left = left.coerceIn(0, max(0, screenW - controlW))
        top = top.coerceIn(0, max(0, screenH - controlH))
        params.x = left
        params.y = top
        controlView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    fun startClicking() {
        val cs = crosshairView ?: return
        if (isRunning.value) return
        isRunning.value = true
        cs.setRunning(true)
        clickCount = 0

        logger?.enabled = currentConfig.loggingEnabled
        clickJob = scope.launch {
            val interval = currentConfig.intervalMs
            logger?.startSession(
                "interval=${interval}ms press=${currentConfig.pressDurationMs}ms " +
                    "fireImmediately=${currentConfig.fireImmediately} target=(${target.x.toInt()}, ${target.y.toInt()}) " +
                    "display=${screenW}x${screenH} overlay=ACCESSIBILITY(2win) csSize=$csSize",
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
                    cs.setFraction(scheduler.remainingFraction(next, interval))
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
        crosshairView?.setRunning(false)
        crosshairView?.setFraction(0f)
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
        if (crosshairAttached) {
            crosshairView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        }
        crosshairAttached = false
        crosshairView = null
        crosshairParams = null
        controlView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        controlView = null
        controlParams = null
    }

    // ---- 手势派发（FR-008 / FR-012）----

    private suspend fun performTap() {
        clickCount++
        val n = clickCount

        // 以准星视图的"屏幕实际中心"作为点击坐标，确保点击落点与可见准星完全一致
        // （规避悬浮窗坐标受状态栏/挖孔 inset 影响导致的视觉与落点错位）。
        val loc = IntArray(2)
        val view = crosshairView
        val tx: Float
        val ty: Float
        if (view != null && crosshairAttached && view.width > 0) {
            view.getLocationOnScreen(loc)
            tx = loc[0] + view.width / 2f
            ty = loc[1] + view.height / 2f
        } else {
            tx = target.x
            ty = target.y
        }
        logger?.logClick(tx, ty, n)

        // 仅移除准星视觉层（它盖在落点上方会导致注入点击被判为 obscured 而丢弃）；
        // 控制条偏离落点、不参与移除，因此不会闪烁。
        detachCrosshairForDispatch()
        try {
            // 让窗口移除真正生效（含 obscured 状态重算）后再派发。
            delay(OVERLAY_SETTLE_MS)
            // path 必须有非零长度，否则 Android 16+ 可能不把它识别为有效点击。
            val path = Path().apply {
                moveTo(tx, ty)
                lineTo(tx + 1f, ty + 1f)
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
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onCancelled(d: GestureDescription?) {
                            logger?.logEvent("  gesture #$n onCancelled")
                            if (cont.isActive) cont.resume(Unit)
                        }
                    },
                    null,
                )
                if (!dispatched) {
                    logger?.logEvent("  gesture #$n dispatchGesture returned=false")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } finally {
            reattachCrosshairAfterDispatch()
        }
    }

    private fun detachCrosshairForDispatch() {
        val view = crosshairView ?: return
        if (!crosshairAttached) return
        runCatching { windowManager.removeViewImmediate(view) }
        crosshairAttached = false
    }

    private fun reattachCrosshairAfterDispatch() {
        val view = crosshairView ?: return
        val params = crosshairParams ?: return
        if (crosshairAttached) return
        runCatching { windowManager.addView(view, params) }
        crosshairAttached = true
    }

    // ---- ControlBarView.Listener ----

    override fun onStartStopTap() {
        if (isRunning.value) stopClicking(StopReason.USER) else startClicking()
    }

    override fun onExitTap() = hideOverlayAndExit()

    override fun onDrag(dxScreen: Float, dyScreen: Float) {
        target = ClickTarget(
            (target.x + dxScreen).coerceIn(0f, screenW.toFloat()),
            (target.y + dyScreen).coerceIn(0f, screenH.toFloat()),
        )
        positionCrosshair()
        positionControl()
    }

    companion object {
        private const val FRAME_MS = 16L
        private const val OVERLAY_SETTLE_MS = 90L

        /** 准星窗口不透明度，≤ 系统 maximumObscuringOpacityForTouch（默认 0.8）。 */
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
