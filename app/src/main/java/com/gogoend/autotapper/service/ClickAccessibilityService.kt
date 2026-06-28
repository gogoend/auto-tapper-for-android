package com.gogoend.autotapper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.gogoend.autotapper.data.CallAction
import com.gogoend.autotapper.data.ClickConfig
import com.gogoend.autotapper.data.ClickTarget
import com.gogoend.autotapper.logging.ClickLogger
import com.gogoend.autotapper.scheduler.ClickScheduler
import com.gogoend.autotapper.scheduler.StopReason
import com.gogoend.autotapper.ui.overlay.ControlBarView
import com.gogoend.autotapper.ui.overlay.CrosshairView
import com.gogoend.autotapper.ui.overlay.placeControlBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    private var controlGapPx = 0f
    private var screenW = 0
    private var screenH = 0

    private var currentConfig: ClickConfig = ClickConfig()
    private var target: ClickTarget = ClickTarget(0f, 0f)

    private val scheduler = ClickScheduler { SystemClock.elapsedRealtime() }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var clickJob: Job? = null
    private var clickCount = 0
    private var logger: ClickLogger? = null

    // US4 中断监听
    private var screenOffReceiver: BroadcastReceiver? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var callMonitoringActive = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    private val baseFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        logger = ClickLogger(applicationContext)
        lastOrientation = resources.configuration.orientation
        registerScreenOffReceiver()
        ensureCallMonitoring()
        instance = this
        _serviceReady.value =true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不需要监听事件 */ }

    override fun onInterrupt() { /* no-op */ }

    // 屏幕旋转 / 尺寸变化（FR-027）：运行中则停止并提示，同时把悬浮窗夹回新屏幕范围内
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientationChanged = newConfig.orientation != lastOrientation
        lastOrientation = newConfig.orientation
        if (!orientationChanged) return

        if (isRunning.value) {
            stopClicking(StopReason.CONFIG_CHANGE)
            Toast.makeText(this, "屏幕方向变化，已停止定时，请重新确认点击位置", Toast.LENGTH_LONG).show()
        }
        // 屏幕尺寸已变，重新读取并把准星/控制条夹回可见范围
        val m = resources.displayMetrics
        screenW = m.widthPixels
        screenH = m.heightPixels
        target = ClickTarget(
            target.x.coerceIn(0f, screenW.toFloat()),
            target.y.coerceIn(0f, screenH.toFloat()),
        )
        positionCrosshair()
        positionControl()
    }

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
        unregisterScreenOffReceiver()
        stopCallMonitoring()
        _overlayShown.value =false
        _serviceReady.value =false
        if (instance === this) instance = null
    }

    // ---- US4：中断监听（锁屏 / 来电 / 旋转）----

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    stopClicking(StopReason.SCREEN_OFF) // 锁屏/息屏停止，不自动恢复（FR-020/FR-028）
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffReceiver = receiver
    }

    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
    }

    /** 在已授予 READ_PHONE_STATE 时注册来电监听；可重复调用（幂等）。 */
    fun ensureCallMonitoring() {
        if (callMonitoringActive) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleCallState(state)
            }
            runCatching { tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb) }
                .onSuccess { telephonyCallback = cb; callMonitoringActive = true }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleCallState(state)
            }
            @Suppress("DEPRECATION")
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
                .onSuccess { phoneStateListener = listener; callMonitoringActive = true }
        }
    }

    private fun stopCallMonitoring() {
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { cb -> runCatching { tm?.unregisterTelephonyCallback(cb) } }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { l -> runCatching { tm?.listen(l, PhoneStateListener.LISTEN_NONE) } }
        }
        telephonyCallback = null
        phoneStateListener = null
        callMonitoringActive = false
    }

    private fun handleCallState(state: Int) {
        if (state == TelephonyManager.CALL_STATE_IDLE) return // 通话结束不自动恢复（FR-028）
        // 来电响铃或通话中：按配置处理（FR-018/019）
        if (currentConfig.onIncomingCall == CallAction.STOP &&
            (isRunning.value || overlayShown.value)
        ) {
            logger?.logEvent("INCOMING_CALL state=$state -> STOP + HIDE")
            // 停止定时并隐藏悬浮窗（FR-018）：取消协程即取消临近待派发点击
            stopClicking(StopReason.INCOMING_CALL)
            removeOverlay()
            _overlayShown.value =false
        }
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
        val buttonR = 14f * density // 在上次 3/4 基础上再缩小为 3/4（≈14.6dp）
        val gap = 12f * density
        // 控制条只有 2 个按钮（拖拽手柄 + 退出）
        controlW = (gap * 3 + buttonR * 4).toInt()
        controlH = (buttonR * 2 + gap * 2).toInt()
        // 让控制条按钮上沿与准星圆形轮廓底部"外切"：
        // 圆半径(=csSize*0.40，见 CrosshairView) - 窗口半高(csSize/2) - 控制条内上边距(gap)
        controlGapPx = csSize * 0.40f - csSize / 2f - gap
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

        _overlayShown.value =true
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

    /** 控制条位置：取可用空间更大的一侧（准星上/下方），夹取到屏幕内，不覆盖落点（见 placeControlBar / FR-014）。 */
    private fun positionControl() {
        val params = controlParams ?: return
        val p = placeControlBar(
            targetX = target.x,
            targetY = target.y,
            crosshairSize = csSize,
            controlW = controlW,
            controlH = controlH,
            screenW = screenW,
            screenH = screenH,
            gap = controlGapPx,
        )
        params.x = p.x.toInt()
        params.y = p.y.toInt()
        controlView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    fun startClicking() {
        val cs = crosshairView ?: return
        if (isRunning.value) return
        ensureCallMonitoring() // 若权限在连接后才授予，这里补注册
        _isRunning.value =true
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
        _isRunning.value =false
        crosshairView?.setRunning(false)
        crosshairView?.setFraction(0f)
    }

    /**
     * 诊断：移除一切悬浮窗后，3 秒倒计时再向屏幕中心派发一次点击。
     * 用于隔离"悬浮窗干扰" vs "派发本身在本设备无效"。
     */
    fun testTapCenter() {
        removeOverlay()
        _overlayShown.value =false
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

    /** 收起悬浮窗（停止点击并移除悬浮窗），但保持无障碍服务存活，便于之后再次显示。 */
    fun hideOverlay() {
        stopClicking(StopReason.EXIT)
        removeOverlay()
        _overlayShown.value =false
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

    override fun onExitTap() = hideOverlay()

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

        private val _isRunning = MutableStateFlow(false)
        private val _overlayShown = MutableStateFlow(false)
        private val _serviceReady = MutableStateFlow(false)

        /** 是否正在计时点击。 */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /** 悬浮窗是否显示中。 */
        val overlayShown: StateFlow<Boolean> = _overlayShown.asStateFlow()

        /** 无障碍服务是否已连接（授权判定权威来源）。 */
        val serviceReady: StateFlow<Boolean> = _serviceReady.asStateFlow()
    }
}
