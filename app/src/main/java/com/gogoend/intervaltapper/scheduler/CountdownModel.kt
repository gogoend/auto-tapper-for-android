package com.gogoend.intervaltapper.scheduler

/**
 * 倒计时换算（纯逻辑，可单测）。剩余比例 → 扇形角度（FR-009 / SC-005）。
 */
object CountdownModel {
    /** 返回 [0,1]：满间隔≈1.0（≈360°），临近点击≈0.0（≈0°）。 */
    fun remainingFraction(nextFireElapsed: Long, intervalMs: Long, now: Long): Float {
        if (intervalMs <= 0L) return 0f
        val frac = (nextFireElapsed - now).toFloat() / intervalMs.toFloat()
        return frac.coerceIn(0f, 1f)
    }

    /** 扇形扫过角度（钟表式：满间隔 360° → 0°）。 */
    fun sweepAngleDegrees(fraction: Float): Float = 360f * fraction.coerceIn(0f, 1f)
}
