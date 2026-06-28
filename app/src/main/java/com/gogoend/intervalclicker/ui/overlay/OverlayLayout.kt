package com.gogoend.intervalclicker.ui.overlay

import kotlin.math.max

data class LPoint(val x: Float, val y: Float)

/**
 * 计算控制条窗口左上角坐标（纯逻辑，可单测）。见 contracts/overlay-ui.md / FR-014。
 *
 * 规则：
 *  - 优先放在准星圆下方或上方——选**可用空间更大**的一侧，避免与准星竖直重叠；
 *  - 夹取到安全区内（考虑上/下 inset，如状态栏/导航栏），保证完整可见；
 *  - 水平居中对齐准星并夹取到屏幕宽度内。
 */
fun placeControlBar(
    targetX: Float,
    targetY: Float,
    crosshairSize: Int,
    controlW: Int,
    controlH: Int,
    screenW: Int,
    screenH: Int,
    gap: Float,
    topInset: Float = 0f,
    bottomInset: Float = 0f,
): LPoint {
    val csHalf = crosshairSize / 2f
    val safeTop = topInset
    val safeBottom = screenH - bottomInset

    val belowTop = targetY + csHalf + gap
    val aboveTop = targetY - csHalf - gap - controlH
    val belowFits = belowTop + controlH <= safeBottom
    val aboveFits = aboveTop >= safeTop

    val top = when {
        belowFits && aboveFits -> {
            val roomBelow = safeBottom - (belowTop + controlH)
            val roomAbove = aboveTop - safeTop
            if (roomBelow >= roomAbove) belowTop else aboveTop
        }
        belowFits -> belowTop
        aboveFits -> aboveTop
        else -> (safeTop + safeBottom - controlH) / 2f // 上下都放不下：竖直居中于安全区
    }

    val clampedTop = top.coerceIn(safeTop, max(safeTop, safeBottom - controlH))
    val left = (targetX - controlW / 2f).coerceIn(0f, max(0f, (screenW - controlW).toFloat()))
    return LPoint(left, clampedTop)
}
