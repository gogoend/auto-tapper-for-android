package com.gogoend.intervalclicker.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.gogoend.intervalclicker.scheduler.CountdownModel

/**
 * 仅负责视觉的准星层：中心十字 + 圆形轮廓 + 运行时扇形倒计时。
 * 永不接收触摸（窗口设 FLAG_NOT_TOUCHABLE），仅在每次派发点击的瞬间被临时移除以避免遮挡注入点击。
 */
@SuppressLint("ViewConstructor")
class CrosshairView(context: Context, private val sizePx: Int) : View(context) {

    private var running = false
    private var fraction = 1f

    private val c get() = sizePx / 2f
    private val circleR = sizePx * 0.40f
    private val sectorR = circleR * 0.52f

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.02f
        color = Color.argb(235, 255, 255, 255)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.02f
        color = Color.argb(235, 244, 67, 54)
    }
    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 244, 67, 54)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 33, 150, 243)
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
        canvas.drawCircle(c, c, circleR, outlinePaint)
        canvas.drawLine(c - circleR, c, c + circleR, c, crossPaint)
        canvas.drawLine(c, c - circleR, c, c + circleR, crossPaint)
        if (running) {
            arcRect.set(c - sectorR, c - sectorR, c + sectorR, c + sectorR)
            canvas.drawArc(arcRect, -90f, CountdownModel.sweepAngleDegrees(fraction), true, sectorPaint)
        } else {
            canvas.drawCircle(c, c, sectorR * 0.7f, dotPaint)
        }
    }
}
