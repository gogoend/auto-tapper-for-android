package com.gogoend.intervalclicker.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.gogoend.intervalclicker.scheduler.CountdownModel
import kotlin.math.hypot

/**
 * 准星 + 中心开始/停止按钮（按 PRD：按钮位于准星圆形轮廓正中、覆盖十字、半径不超出轮廓）。
 *  - 未运行：中心为半透明圆形"开始"按钮。
 *  - 运行中：中心为半透明扇形"停止"按钮，扇形角度表示距下次点击的剩余时间。
 * 中心按钮可点击（开始/停止）；该窗口仅在每次派发点击的瞬间被临时移除以避免遮挡注入点击。
 */
@SuppressLint("ViewConstructor")
class CrosshairView(
    context: Context,
    private val sizePx: Int,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onStartStopTap()
    }

    private var running = false
    private var fraction = 1f

    private val c get() = sizePx / 2f
    private val circleR = sizePx * 0.40f
    private val buttonR = circleR * 0.62f

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.02f
        color = Color.argb(235, 255, 255, 255)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.02f
        color = Color.argb(235, 0, 0, 0)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 33, 150, 243)
    }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 244, 67, 54)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 255, 255, 255)
    }
    private val arcRect = RectF()
    private val triangle = Path()

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
        // 圆形轮廓 + 中心十字
        canvas.drawCircle(c, c, circleR, outlinePaint)
        canvas.drawLine(c - circleR, c, c + circleR, c, crossPaint)
        canvas.drawLine(c, c - circleR, c, c + circleR, crossPaint)

        // 中心按钮：开始=圆形；停止=扇形倒计时（均覆盖十字、半径不超轮廓）
        if (running) {
            arcRect.set(c - buttonR, c - buttonR, c + buttonR, c + buttonR)
            canvas.drawArc(arcRect, -90f, CountdownModel.sweepAngleDegrees(fraction), true, stopPaint)
            val s = buttonR * 0.4f
            canvas.drawRect(c - s, c - s, c + s, c + s, iconPaint)
        } else {
            canvas.drawCircle(c, c, buttonR, startPaint)
            val s = buttonR * 0.5f
            triangle.reset()
            triangle.moveTo(c - s * 0.55f, c - s)
            triangle.lineTo(c - s * 0.55f, c + s)
            triangle.lineTo(c + s, c)
            triangle.close()
            canvas.drawPath(triangle, iconPaint)
        }
    }

    private var downOnButton = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val onButton = hypot(event.x - c, event.y - c) <= buttonR * 1.25f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOnButton = onButton
                return onButton // 仅按钮区域消费，其余透传
            }
            MotionEvent.ACTION_UP -> {
                if (downOnButton && onButton) listener.onStartStopTap()
                downOnButton = false
                return true
            }
        }
        return downOnButton
    }
}
