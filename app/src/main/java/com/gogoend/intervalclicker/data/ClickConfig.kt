package com.gogoend.intervalclicker.data

import kotlin.math.max

/** 来电时的动作（FR-017）。 */
enum class CallAction { STOP, CONTINUE }

/**
 * 点击配置（持久化实体，见 data-model.md）。
 * minIntervalMs 由 UI 强制（FR-024），保证单次点击可完整结束后再进入下一周期。
 */
data class ClickConfig(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val pressDurationMs: Long = DEFAULT_PRESS_DURATION_MS,
    val fireImmediately: Boolean = true,
    val onIncomingCall: CallAction = CallAction.CONTINUE,
    val loggingEnabled: Boolean = true,
) {
    /** 最小允许间隔 = max(下限, 按下时长 + 缓冲)（R9 / FR-024）。 */
    val minIntervalMs: Long
        get() = max(MIN_INTERVAL_FLOOR_MS, pressDurationMs + PRESS_BUFFER_MS)

    /** 钳制到合法范围。 */
    fun normalized(): ClickConfig {
        val press = pressDurationMs.coerceAtLeast(1L)
        val withPress = copy(pressDurationMs = press)
        return withPress.copy(intervalMs = withPress.intervalMs.coerceAtLeast(withPress.minIntervalMs))
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5000L
        const val DEFAULT_PRESS_DURATION_MS = 60L
        const val MIN_INTERVAL_FLOOR_MS = 200L
        const val PRESS_BUFFER_MS = 50L
    }
}
