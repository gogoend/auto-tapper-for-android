package com.gogoend.intervalclicker

import com.gogoend.intervalclicker.ui.overlay.placeControlBar
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {

    private val w = 1080
    private val h = 1920
    private val cs = 360
    private val cw = 320
    private val ch = 130
    private val gap = 43f

    private fun assertBarInside(x: Float, y: Float, top: Float = 0f, bottom: Float = h.toFloat()) {
        assertTrue("x-left $x", x >= -0.01f)
        assertTrue("x-right ${x + cw}", x + cw <= w + 0.01f)
        assertTrue("y-top $y vs $top", y >= top - 0.01f)
        assertTrue("y-bottom ${y + ch} vs $bottom", y + ch <= bottom + 0.01f)
    }

    /** FR-014 / SC-004 / T025: 准星拖到四角/四边/中心时，控制条仍完整落在屏幕内。 */
    @Test
    fun controlBarStaysOnScreen_atCornersEdgesAndCenter() {
        val targets = listOf(
            0f to 0f, w.toFloat() to 0f, 0f to h.toFloat(), w.toFloat() to h.toFloat(),
            w / 2f to 0f, w / 2f to h.toFloat(), 0f to h / 2f, w.toFloat() to h / 2f,
            w / 2f to h / 2f,
        )
        for ((tx, ty) in targets) {
            val p = placeControlBar(tx, ty, cs, cw, ch, w, h, gap)
            assertBarInside(p.x, p.y)
        }
    }

    /** 控制条不与准星竖直重叠（位于准星圆上方或下方），中心位置时尤其要满足。 */
    @Test
    fun controlBarDoesNotOverlapCrosshairVertically_whenRoomExists() {
        val tx = w / 2f
        val ty = h / 2f
        val p = placeControlBar(tx, ty, cs, cw, ch, w, h, gap)
        val csHalf = cs / 2f
        val barBottom = p.y + ch
        val above = barBottom <= ty - csHalf + 0.01f
        val below = p.y >= ty + csHalf - 0.01f
        assertTrue("bar should be fully above or below the crosshair circle", above || below)
    }

    /** T035: 考虑状态栏/导航栏 inset 时，控制条仍落在安全区内。 */
    @Test
    fun controlBarRespectsInsets() {
        val topInset = 96f
        val bottomInset = 140f
        val targets = listOf(
            w / 2f to 0f, w / 2f to h.toFloat(), w / 2f to h / 2f,
            0f to topInset, w.toFloat() to (h - bottomInset),
        )
        for ((tx, ty) in targets) {
            val p = placeControlBar(tx, ty, cs, cw, ch, w, h, gap, topInset, bottomInset)
            assertBarInside(p.x, p.y, top = topInset, bottom = h - bottomInset)
        }
    }
}
