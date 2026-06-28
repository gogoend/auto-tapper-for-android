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
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val arrowPath = Path()

    /** 在 (cxp, cyp) 处画一个指向 dir（0=上,1=下,2=左,3=右）的小箭头，箭尖距中心 len。 */
    private fun drawArrowHead(canvas: Canvas, cxp: Float, cyp: Float, len: Float, dir: Int) {
        val a = buttonR * 0.22f // 箭头大小
        arrowPath.reset()
        when (dir) {
            0 -> { arrowPath.moveTo(cxp, cyp - len); arrowPath.lineTo(cxp - a, cyp - len + a); arrowPath.lineTo(cxp + a, cyp - len + a) }
            1 -> { arrowPath.moveTo(cxp, cyp + len); arrowPath.lineTo(cxp - a, cyp + len - a); arrowPath.lineTo(cxp + a, cyp + len - a) }
            2 -> { arrowPath.moveTo(cxp - len, cyp); arrowPath.lineTo(cxp - len + a, cyp - a); arrowPath.lineTo(cxp - len + a, cyp + a) }
            3 -> { arrowPath.moveTo(cxp + len, cyp); arrowPath.lineTo(cxp + len - a, cyp - a); arrowPath.lineTo(cxp + len - a, cyp + a) }
        }
        arrowPath.close()
        canvas.drawPath(arrowPath, iconFill)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = cy

        // 0: 拖拽手柄（四向移动：十字 + 四个箭头）
        val dx = cx(0)
        canvas.drawCircle(dx, y, buttonR, bgPaint)
        val h = buttonR * 0.46f
        canvas.drawLine(dx - h, y, dx + h, y, iconStroke)
        canvas.drawLine(dx, y - h, dx, y + h, iconStroke)
        drawArrowHead(canvas, dx, y, h, 0)
        drawArrowHead(canvas, dx, y, h, 1)
        drawArrowHead(canvas, dx, y, h, 2)
        drawArrowHead(canvas, dx, y, h, 3)

        // 1: 退出（圆形红底 + 白色 X）
        val ex = cx(1)
        bgPaint.color = Color.argb(235, 200, 60, 60)
        canvas.drawCircle(ex, y, buttonR, bgPaint)
        bgPaint.color = Color.argb(235, 66, 66, 66) // 复原供下次绘制
        val e = buttonR * 0.4f
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
