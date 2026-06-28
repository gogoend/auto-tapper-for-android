package com.gogoend.intervaltapper

import com.gogoend.intervaltapper.scheduler.CountdownModel
import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownModelTest {

    @Test
    fun remainingFraction_fullAtStart_emptyAtFire() {
        assertEquals(1f, CountdownModel.remainingFraction(1000L, 1000L, now = 0L), 0.001f)
        assertEquals(0.25f, CountdownModel.remainingFraction(1000L, 1000L, now = 750L), 0.001f)
        assertEquals(0f, CountdownModel.remainingFraction(1000L, 1000L, now = 1000L), 0.001f)
        assertEquals(0f, CountdownModel.remainingFraction(1000L, 1000L, now = 2000L), 0.001f)
    }

    @Test
    fun sweepAngle_mapsFractionToDegrees() {
        assertEquals(360f, CountdownModel.sweepAngleDegrees(1f), 0.001f)
        assertEquals(180f, CountdownModel.sweepAngleDegrees(0.5f), 0.001f)
        assertEquals(0f, CountdownModel.sweepAngleDegrees(0f), 0.001f)
        assertEquals(360f, CountdownModel.sweepAngleDegrees(1.5f), 0.001f) // clamped
    }
}
