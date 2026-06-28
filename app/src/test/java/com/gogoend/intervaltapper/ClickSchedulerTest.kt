package com.gogoend.intervaltapper

import com.gogoend.intervaltapper.scheduler.ClickScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    /** SC-001 / T036（逻辑层）：模拟调度循环（16ms tick + 每次执行有少量开销），
     *  稳态下相邻点击间隔与设定值偏差应 ≤ ±5%，且不随次数累积漂移。 */
    @Test
    fun steadyStateIntervalWithinFivePercent() {
        val interval = 5000L
        var now = 0L
        val sched = ClickScheduler { now }
        var next = sched.start(interval, fireImmediately = false).nextFireElapsed

        val fireTimes = mutableListOf<Long>()
        var guard = 0
        while (fireTimes.size < 30 && guard < 10_000_000) {
            guard++
            if (now >= next) {
                fireTimes.add(now)
                now += 7 // 模拟一次点击的执行开销
                next = sched.onClickCompleted(interval)
            } else {
                now += 16 // 帧 tick
            }
        }

        assertTrue("应至少触发若干次", fireTimes.size >= 10)
        for (i in 1 until fireTimes.size) {
            val d = fireTimes[i] - fireTimes[i - 1]
            val err = abs(d - interval).toDouble() / interval
            assertTrue("第 $i 次间隔 $d ms 偏差 ${"%.3f".format(err)} 超过 5%", err <= 0.05)
        }
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
