package com.gogoend.intervalclicker.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 可触摸的控制条：开始/停止、拖拽手柄、退出，三个按钮横向排列。
 * 放置于离准星落点有偏移的位置，因此不会遮挡注入点击、也不会被注入点击误触，故无需移除。
 */
@SuppressLint("ViewConstructor")
class ControlBarView(
    context: Context,
    private val buttonR: Float,
    private val gap: Float,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onStartStopTap()
        fun onExitTap()
        fun onDrag(dxScreen: Float, dyScreen: Float)
    }

    private var running = false

    private val cy get() = height / 2f
    private fun cx(i: Int) = gap + buttonR + i * (2 * buttonR + gap)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = buttonR * 0.16f
    }
    private val triangle = Path()

    fun setRunning(value: Boolean) {
        running = value
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = cy

        // 0: 开始/停止
        val sx = cx(0)
        bgPaint.color = if (running) Color.argb(235, 244, 67, 54) else Color.argb(235, 33, 150, 243)
        canvas.drawCircle(sx, y, buttonR, bgPaint)
        if (running) {
            val s = buttonR * 0.42f
            canvas.drawRect(sx - s, y - s, sx + s, y + s, iconFill)
        } else {
            val s = buttonR * 0.5f
            triangle.reset()
            triangle.moveTo(sx - s * 0.6f, y - s)
            triangle.lineTo(sx - s * 0.6f, y + s)
            triangle.lineTo(sx + s, y)
            triangle.close()
            canvas.drawPath(triangle, iconFill)
        }

        // 1: 拖拽手柄（四向）
        val dx = cx(1)
        bgPaint.color = Color.argb(235, 66, 66, 66)
        canvas.drawCircle(dx, y, buttonR, bgPaint)
        val h = buttonR * 0.5f
        canvas.drawLine(dx - h, y, dx + h, y, iconStroke)
        canvas.drawLine(dx, y - h, dx, y + h, iconStroke)

        // 2: 退出（X）
        val ex = cx(2)
        bgPaint.color = Color.argb(235, 66, 66, 66)
        canvas.drawCircle(ex, y, buttonR, bgPaint)
        val e = buttonR * 0.42f
        canvas.drawLine(ex - e, y - e, ex + e, y + e, iconStroke)
        canvas.drawLine(ex - e, y + e, ex + e, y - e, iconStroke)
    }

    private var dragging = false
    private var downIdx = -1
    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun hit(i: Int, x: Float, y: Float) = hypot(x - cx(i), y - cy) <= buttonR * 1.3f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downIdx = -1
                when {
                    hit(1, event.x, event.y) -> {
                        dragging = true
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    hit(0, event.x, event.y) -> downIdx = 0
                    hit(2, event.x, event.y) -> downIdx = 2
                    else -> return false
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
                if (!dragging) {
                    when {
                        downIdx == 0 && hit(0, event.x, event.y) -> listener.onStartStopTap()
                        downIdx == 2 && hit(2, event.x, event.y) -> listener.onExitTap()
                    }
                }
                dragging = false
                downIdx = -1
                return true
            }
        }
        return true
    }
}
