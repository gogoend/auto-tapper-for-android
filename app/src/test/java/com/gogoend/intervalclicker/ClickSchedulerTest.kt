package com.gogoend.intervalclicker

import com.gogoend.intervalclicker.scheduler.ClickScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickSchedulerTest {

    private var fakeNow = 0L
    private val scheduler = ClickScheduler { fakeNow }

    @Test
    fun start_fireImmediatelyTrue_firesNowAndSetsNext() {
        fakeNow = 1000L
        val plan = scheduler.start(5000L, fireImmediately = true)
        assertTrue(plan.fireNow)
        assertEquals(6000L, plan.nextFireElapsed)
    }

    @Test
    fun start_fireImmediatelyFalse_waitsOneInterval() {
        fakeNow = 1000L
        val plan = scheduler.start(5000L, fireImmediately = false)
        assertFalse(plan.fireNow)
        assertEquals(6000L, plan.nextFireElapsed)
    }

    /** FR-013: 长时间卡顿后不得累计补发——下一次基于 now 重设。 */
    @Test
    fun onClickCompleted_afterLongStall_resetsRelativeToNow_noPileup() {
        fakeNow = 0L
        val first = scheduler.start(1000L, fireImmediately = false).nextFireElapsed
        assertEquals(1000L, first)

        // 模拟系统繁忙：now 跳到远超多个周期之后
        fakeNow = 10_000L
        val next = scheduler.onClickCompleted(1000L)

        // 必须是 now + interval，而非 1000 + N*1000 的追赶式补发
        assertEquals(11_000L, next)
    }

    @Test
    fun delayUntilNext_futureReturnsRemaining_expiredReturnsZero() {
        fakeNow = 5000L
        assertEquals(0L, scheduler.delayUntilNext(4000L))
        assertEquals(1000L, scheduler.delayUntilNext(6000L))
    }

    @Test
    fun remainingFraction_isClampedBetweenZeroAndOne() {
        fakeNow = 0L
        assertEquals(1f, scheduler.remainingFraction(1000L, 1000L), 0.001f)
        fakeNow = 500L
        assertEquals(0.5f, scheduler.remainingFraction(1000L, 1000L), 0.001f)
        fakeNow = 2000L
        assertEquals(0f, scheduler.remainingFraction(1000L, 1000L), 0.001f)
    }
}
