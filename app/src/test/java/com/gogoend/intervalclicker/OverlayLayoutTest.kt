package com.gogoend.intervalclicker

import com.gogoend.intervalclicker.ui.overlay.LPoint
import com.gogoend.intervalclicker.ui.overlay.arrangeControls
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {

    private val w = 1080f
    private val h = 1920f
    private val r = 48f
    private val off = 200f

    private fun assertInBounds(p: LPoint) {
        assertTrue("x-left ${p.x}", p.x - r >= -0.01f)
        assertTrue("x-right ${p.x}", p.x + r <= w + 0.01f)
        assertTrue("y-top ${p.y}", p.y - r >= -0.01f)
        assertTrue("y-bottom ${p.y}", p.y + r <= h + 0.01f)
    }

    /** FR-014 / SC-004: 准星拖到四角/四边/中心时，手柄与退出按钮均完整可见。 */
    @Test
    fun controlsStayOnScreen_atCornersEdgesAndCenter() {
        val centers = listOf(
            LPoint(0f, 0f), LPoint(w, 0f), LPoint(0f, h), LPoint(w, h),
            LPoint(w / 2, 0f), LPoint(w / 2, h), LPoint(0f, h / 2), LPoint(w, h / 2),
            LPoint(w / 2, h / 2),
        )
        for (c in centers) {
            val layout = arrangeControls(c, w, h, satelliteRadius = r, offset = off)
            assertInBounds(layout.dragHandle)
            assertInBounds(layout.exit)
        }
    }
}
