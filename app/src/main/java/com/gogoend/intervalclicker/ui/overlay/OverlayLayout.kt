package com.gogoend.intervalclicker.ui.overlay

import kotlin.math.max
import kotlin.math.min

data class LPoint(val x: Float, val y: Float)

/** 控件布局：中心按钮（=点击落点）、拖拽手柄、退出按钮。 */
data class ControlLayout(
    val center: LPoint,
    val dragHandle: LPoint,
    val exit: LPoint,
)

/**
 * 边缘自适应布局（纯逻辑，可单测）。见 contracts/overlay-ui.md / FR-014。
 * 准星中心可贴近屏幕边缘；拖拽手柄与退出按钮优先放在中心上/下方，
 * 空间不足时翻转到另一侧，并最终钳制到屏幕可见区内，保证完整可见可点。
 */
fun arrangeControls(
    center: LPoint,
    screenWidth: Float,
    screenHeight: Float,
    satelliteRadius: Float,
    offset: Float,
): ControlLayout {
    fun clamp(v: Float, r: Float, maxV: Float): Float = max(r, min(v, maxV - r))

    // 拖拽手柄：优先上方；上方放不下则下方
    val handleAbove = center.y - offset - satelliteRadius >= 0f
    val handleY = if (handleAbove) center.y - offset else center.y + offset
    // 退出：与手柄相反的竖直侧
    val exitY = if (handleAbove) center.y + offset else center.y - offset

    val handle = LPoint(
        clamp(center.x, satelliteRadius, screenWidth),
        clamp(handleY, satelliteRadius, screenHeight),
    )
    val exit = LPoint(
        clamp(center.x, satelliteRadius, screenWidth),
        clamp(exitY, satelliteRadius, screenHeight),
    )
    return ControlLayout(center = center, dragHandle = handle, exit = exit)
}
