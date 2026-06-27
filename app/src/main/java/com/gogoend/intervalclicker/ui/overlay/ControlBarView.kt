package com.gogoend.intervalclicker.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 可触摸的控制条：拖拽手柄 + 退出，两个按钮横向排列。
 * 放在偏离准星落点的位置，始终存在、不参与移除（不闪烁），也不会被注入点击误触。
 * 开始/停止按钮按 PRD 位于准星中心，由 CrosshairView 承载（不在此处）。
 */
@SuppressLint("ViewConstructor")
class ControlBarView(
    context: Context,
    private val buttonR: Float,
    private val gap: Float,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onExitTap()
        fun onDrag(dxScreen: Float, dyScreen: Float)
    }

    private val cy get() = height / 2f
    private fun cx(i: Int) = gap + buttonR + i * (2 * buttonR + gap)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 66, 66, 66)
    }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = buttonR * 0.16f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = cy

        // 0: 拖拽手柄（四向）
        val dx = cx(0)
        canvas.drawCircle(dx, y, buttonR, bgPaint)
        val h = buttonR * 0.5f
        canvas.drawLine(dx - h, y, dx + h, y, iconStroke)
        canvas.drawLine(dx, y - h, dx, y + h, iconStroke)

        // 1: 退出（X）
        val ex = cx(1)
        canvas.drawCircle(ex, y, buttonR, bgPaint)
        val e = buttonR * 0.42f
        canvas.drawLine(ex - e, y - e, ex + e, y + e, iconStroke)
        canvas.drawLine(ex - e, y + e, ex + e, y - e, iconStroke)
    }

    private var dragging = false
    private var downExit = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun hit(i: Int, x: Float, y: Float) = hypot(x - cx(i), y - cy) <= buttonR * 1.3f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downExit = false
                when {
                    hit(0, event.x, event.y) -> {
                        dragging = true
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    hit(1, event.x, event.y) -> downExit = true
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
                if (!dragging && downExit && hit(1, event.x, event.y)) listener.onExitTap()
                dragging = false
                downExit = false
                return true
            }
        }
        return true
    }
}
