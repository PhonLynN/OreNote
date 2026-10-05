package com.phonlynn.oreplan.platform.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把**未捕获异常的堆栈**落到文件里，下次启动可以取出来。
 *
 * ## 为什么需要它
 *
 * 用户连续报了三次闪退，而我**从这边看不到任何日志** —— `adb devices` 是空的，
 * 手机不在我这台机器上。于是只能靠读代码猜，猜错三次。
 *
 * 这个类把「拿不到证据」这件事一次性解决：崩溃时自己写文件，
 * 下次启动由 [com.phonlynn.oreplan.v2.CrashReportDialog] 读出来并**自动复制到剪贴板**，
 * 用户粘一下就完事 —— 不需要 USB、不需要 adb、不需要开发者选项。
 *
 * ## 为什么不引第三方崩溃上报
 *
 * 项目是零第三方依赖，而且**用户明确不在乎隐私但不能接受无谓的外发** ——
 * 崩溃日志里可能有对话片段，绝不能自动上传。写本地文件是唯一合适的做法。
 *
 * ## 只留最后一次
 *
 * 不做滚动归档：需要的是"刚才那次崩在哪"，留一堆反而要挑。
 */
object CrashLog {

    private const val TAG = "OrePlan"
    private const val FILE_NAME = "last-crash.txt"

    /** 堆栈截断上限 —— 剪贴板里塞几 MB 没有意义，前 16KB 足够定位。 */
    private const val MAX_CHARS = 16 * 1024

    /**
     * 装上全局异常处理器。**在 Application.onCreate 里调一次。**
     *
     * ⚠️ 处理完必须**转交给原来的处理器** —— 否则系统不会走正常的崩溃流程，
     * 用户看到的是"App 卡住不动然后消失"，而不是标准的崩溃行为，
     * 也拿不到系统的崩溃记录。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 写日志本身绝不能再抛异常，否则会把真正的错误盖掉
            runCatching { write(app, thread, error) }
                .onFailure { Log.w(TAG, "崩溃日志写入失败", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 上一次崩溃的记录；没有就是 null。 */
    fun read(context: Context): String? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        file.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("时间：$time")
            appendLine("线程：${thread.name}")
            appendLine("异常：${error.javaClass.name}")
            appendLine("信息：${error.message}")
            appendLine()
            appendLine(error.stackTraceToString())
        }
        File(context.filesDir, FILE_NAME).writeText(text.take(MAX_CHARS))
    }
}
