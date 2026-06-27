package com.gogoend.intervalclicker.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.gogoend.intervalclicker.scheduler.CountdownModel
import kotlin.math.hypot

/**
 * 悬浮控制层自绘视图：准星（十字+圆形轮廓）、中心开始/停止按钮（停止为扇形倒计时）、
 * 拖拽手柄、退出按钮。见 contracts/overlay-ui.md。
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    private val sizePx: Int,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onStartStopTap()
        fun onExitTap()
        /** 拖拽手柄移动的屏幕坐标增量。 */
        fun onDrag(dxScreen: Float, dyScreen: Float)
    }

    private var running = false
    private var fraction = 1f

    private val cx get() = sizePx / 2f
    private val cy get() = sizePx / 2f
    private val circleR = sizePx * 0.30f
    private val buttonR = circleR * 0.62f
    private val satelliteR = sizePx * 0.085f
    private val satelliteOffset = circleR + satelliteR + sizePx * 0.03f

    private val handleX get() = cx
    private val handleY get() = cy - satelliteOffset
    private val exitX get() = cx
    private val exitY get() = cy + satelliteOffset

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.012f
        color = Color.argb(220, 255, 255, 255)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.012f
        color = Color.argb(220, 0, 0, 0)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 33, 150, 243) // 半透明蓝
    }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 244, 67, 54) // 半透明红
    }
    private val satellitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 66, 66, 66)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.012f
        color = Color.WHITE
    }

    private val arcRect = RectF()

    fun setRunning(value: Boolean) {
        running = value
        postInvalidate()
    }

    fun setFraction(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        if (running) postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 圆形轮廓
        canvas.drawCircle(cx, cy, circleR, outlinePaint)
        // 中心十字
        canvas.drawLine(cx - circleR, cy, cx + circleR, cy, crossPaint)
        canvas.drawLine(cx, cy - circleR, cx, cy + circleR, crossPaint)

        // 中心按钮：停止=扇形倒计时；开始=实心圆
        if (running) {
            arcRect.set(cx - buttonR, cy - buttonR, cx + buttonR, cy + buttonR)
            val sweep = CountdownModel.sweepAngleDegrees(fraction)
            canvas.drawArc(arcRect, -90f, sweep, true, stopPaint)
        } else {
            canvas.drawCircle(cx, cy, buttonR, startPaint)
        }

        // 拖拽手柄（四向箭头近似：实心圆 + 十字）
        canvas.drawCircle(handleX, handleY, satelliteR, satellitePaint)
        val h = satelliteR * 0.5f
        canvas.drawLine(handleX - h, handleY, handleX + h, handleY, iconPaint)
        canvas.drawLine(handleX, handleY - h, handleX, handleY + h, iconPaint)

        // 退出按钮（X）
        canvas.drawCircle(exitX, exitY, satelliteR, satellitePaint)
        val e = satelliteR * 0.45f
        canvas.drawLine(exitX - e, exitY - e, exitX + e, exitY + e, iconPaint)
        canvas.drawLine(exitX - e, exitY + e, exitX + e, exitY - e, iconPaint)
    }

    private var dragging = false
    private var downOnButton = false
    private var downOnExit = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun dist(x: Float, y: Float, px: Float, py: Float) = hypot(x - px, y - py)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false; downOnButton = false; downOnExit = false
                when {
                    dist(event.x, event.y, handleX, handleY) <= satelliteR * 1.6f -> {
                        dragging = true
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    dist(event.x, event.y, cx, cy) <= buttonR -> downOnButton = true
                    dist(event.x, event.y, exitX, exitY) <= satelliteR * 1.6f -> downOnExit = true
                    else -> return false // 非交互区域：不消费，便于落点附近透传
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    listener.onDrag(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> dragging = false
                    downOnButton && dist(event.x, event.y, cx, cy) <= buttonR ->
                        listener.onStartStopTap()
                    downOnExit && dist(event.x, event.y, exitX, exitY) <= satelliteR * 1.6f ->
                        listener.onExitTap()
                }
                downOnButton = false; downOnExit = false
                return true
            }
        }
        return true
    }
}
