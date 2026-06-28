package com.gogoend.intervaltapper.scheduler

import kotlin.math.max

/**
 * 无堆积的定时调度核心（纯逻辑，可单测）。见 contracts/click-scheduler.md。
 * 时钟通过构造注入（生产用 SystemClock.elapsedRealtime，测试可替换）。
 */
class ClickScheduler(private val clock: () -> Long) {

    data class StartPlan(val fireNow: Boolean, val nextFireElapsed: Long)

    fun start(intervalMs: Long, fireImmediately: Boolean): StartPlan =
        StartPlan(fireNow = fireImmediately, nextFireElapsed = clock() + intervalMs)

    /**
     * 一次点击完成后计算下一目标时刻。
     * 关键不变量（FR-013）：始终以"当前时刻 + 间隔"重设，绝不累计补发被错过的周期。
     */
    fun onClickCompleted(intervalMs: Long): Long = clock() + intervalMs

    /** 距下一次点击应 delay 的毫秒；已过期返回 0（立即执行一次，不补发历史）。 */
    fun delayUntilNext(nextFireElapsed: Long): Long = max(0L, nextFireElapsed - clock())

    /** 剩余比例 [0,1]，供扇形渲染。 */
    fun remainingFraction(nextFireElapsed: Long, intervalMs: Long): Float =
        CountdownModel.remainingFraction(nextFireElapsed, intervalMs, clock())
}
