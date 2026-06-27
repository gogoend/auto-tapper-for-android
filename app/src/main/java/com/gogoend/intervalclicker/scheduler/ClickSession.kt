package com.gogoend.intervalclicker.scheduler

/** 会话状态机（data-model.md）。 */
enum class SessionState { STOPPED, RUNNING }

/** 停止原因，用于区分用户停止与各类中断。 */
enum class StopReason { USER, SCREEN_OFF, INCOMING_CALL, CONFIG_CHANGE, EXIT }

/** 点击会话运行态（内存）。 */
data class ClickSession(
    val state: SessionState = SessionState.STOPPED,
    val nextFireElapsed: Long = 0L,
    val clickCount: Int = 0,
)
