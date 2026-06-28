package com.gogoend.intervalclicker.scheduler

/** 停止原因，用于区分用户停止与各类中断（日志与行为分支）。 */
enum class StopReason { USER, SCREEN_OFF, INCOMING_CALL, CONFIG_CHANGE, EXIT }
