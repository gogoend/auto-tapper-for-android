package com.gogoend.autotapper.data

/**
 * 点击位置（准星中心，屏幕绝对坐标 px）。运行态、不持久化（FR-025），
 * 每次启动重置为屏幕中心。
 */
data class ClickTarget(val x: Float, val y: Float) {
    companion object {
        fun center(screenWidth: Int, screenHeight: Int): ClickTarget =
            ClickTarget(screenWidth / 2f, screenHeight / 2f)
    }
}
