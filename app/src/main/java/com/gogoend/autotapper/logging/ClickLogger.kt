package com.gogoend.autotapper.logging

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
 * 每次"开始"创建一个独立的会话日志文件，存放于应用专属外部目录（无需额外存储权限）：
 *   Android/data/<包名>/files/logs/click_log_<时间戳>.txt
 * 写入在 IO 线程进行，避免阻塞调度循环所在的主线程。
 */
class ClickLogger(private val context: Context) {

    @Volatile
    var enabled: Boolean = true

    private var currentFile: File? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 开始一个新会话日志文件并写入头部。logging 关闭时不创建文件。 */
    fun startSession(header: String) {
        if (!enabled) {
            currentFile = null
            return
        }
        val dir = logsDir(context).apply { mkdirs() }
        currentFile = File(dir, "click_log_${nameFormat.format(Date())}.txt")
        append("START $header")
    }

    /** 记录一次点击的坐标。 */
    fun logClick(x: Float, y: Float, count: Int) {
        append("CLICK #$count at (${x.toInt()}, ${y.toInt()})")
    }

    /** 记录会话事件（停止等）。 */
    fun logEvent(message: String) {
        append(message)
    }

    private fun append(body: String) {
        if (!enabled) return
        val file = currentFile ?: return
        val line = "${timeFormat.format(Date())}  $body\n"
        scope.launch {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line)
            }
        }
    }

    companion object {
        /** 日志目录（应用专属外部目录优先，回退到内部目录）。 */
        fun logsDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")

        /** 列出全部会话日志文件，按修改时间从新到旧排序。 */
        fun listLogFiles(context: Context): List<File> =
            logsDir(context).listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
    }
}
