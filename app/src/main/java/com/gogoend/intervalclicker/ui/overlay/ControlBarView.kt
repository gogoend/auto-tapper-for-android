package com.gogoend.intervalclicker.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.hypot
import kotlin.math.max

/**
 * 可触摸的控制条：拖拽手柄 + 退出，两个按钮横向排列。
 * 放在偏离准星落点的位置，始终存在、不参与移除（不闪烁），也不会被注入点击误触。
 * 图标使用 SVG path（PathParser 解析后按按钮尺寸缩放填充）。
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

    private val bgGray = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 66, 66, 66)
    }
    private val bgRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 200, 60, 60)
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val movePath = PathParser.createPathFromPathData(MOVE_PATH)
    private val closePath = PathParser.createPathFromPathData(CLOSE_PATH)
    private val moveBounds = RectF().also { movePath.computeBounds(it, true) }
    private val closeBounds = RectF().also { closePath.computeBounds(it, true) }
    private val iconMatrix = Matrix()
    private val iconScratch = Path()

    /** 将 SVG path 等比缩放使较大边 = target，并居中绘制在 (cxp, cyp)。 */
    private fun drawIcon(canvas: Canvas, src: Path, bounds: RectF, cxp: Float, cyp: Float, target: Float) {
        val scale = target / max(bounds.width(), bounds.height())
        iconMatrix.reset()
        iconMatrix.postTranslate(-bounds.centerX(), -bounds.centerY())
        iconMatrix.postScale(scale, scale)
        iconMatrix.postTranslate(cxp, cyp)
        iconScratch.reset()
        src.transform(iconMatrix, iconScratch)
        canvas.drawPath(iconScratch, iconFill)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = cy

        // 0: 拖拽手柄（四向移动）
        val dx = cx(0)
        canvas.drawCircle(dx, y, buttonR, bgGray)
        drawIcon(canvas, movePath, moveBounds, dx, y, buttonR * 1.2f)

        // 1: 退出（圆形红底 + X）
        val ex = cx(1)
        canvas.drawCircle(ex, y, buttonR, bgRed)
        drawIcon(canvas, closePath, closeBounds, ex, y, buttonR * 1.0f)
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

    companion object {
        private const val CLOSE_PATH =
            "M24,20.5L40.3,4.3c1-1,1-2.6,0-3.5c-1-1-2.6-1-3.5,0L20.5,17L4.3,0.7c-1-1-2.6-1-3.5,0" +
                "c-1,1-1,2.6,0,3.5L17,20.5L0.7,36.7c-1,1-1,2.6,0,3.5C1.2,40.8,1.9,41,2.5,41s1.3-0.2,1.8-0.7" +
                "L20.5,24l16.2,16.2c0.5,0.5,1.1,0.7,1.8,0.7s1.3-0.2,1.8-0.7c1-1,1-2.6,0-3.5L24,20.5z"
        private const val MOVE_PATH =
            "M54.5,26.2l-11.2-6.4v6H29.7V12h6L29.2,0.9c-0.7-1.2-2.4-1.2-3.1,0L19.7,12h6v13.7H12v-6L0.9,26.2" +
                "c-1.2,0.7-1.2,2.4,0,3.1L12,35.7v-6h13.7v13.7h-6l6.4,11.2c0.7,1.2,2.4,1.2,3.1,0l6.4-11.2h-6V29.7" +
                "h13.7v6l11.2-6.4C55.7,28.6,55.7,26.9,54.5,26.2z"
    }
}
