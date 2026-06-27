package com.gogoend.intervalclicker.logging

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将每次点击的坐标等信息追加写入手机文件系统。
 * 写入应用专属外部目录（无需额外存储权限），用户可通过文件管理器访问：
 *   Android/data/<包名>/files/click_log.txt
 * 写入在 IO 线程进行，避免阻塞调度循环所在的主线程。
 */
class ClickLogger(context: Context) {

    val logFile: File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 记录一次点击的坐标。 */
    fun logClick(x: Float, y: Float, count: Int) {
        append("CLICK #$count at (${x.toInt()}, ${y.toInt()})")
    }

    /** 记录会话事件（开始/停止等）。 */
    fun logEvent(message: String) {
        append(message)
    }

    private fun append(body: String) {
        val line = "${timeFormat.format(Date())}  $body\n"
        scope.launch {
            runCatching {
                logFile.parentFile?.mkdirs()
                logFile.appendText(line)
            }
        }
    }

    companion object {
        const val FILE_NAME = "click_log.txt"
    }
}
