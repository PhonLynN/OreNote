package com.phonlynn.oreplan.platform.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 自动同步的**触发调度**（用 `AlarmManager`，与提醒功能同一套机制）。
 *
 * ## 为什么不引 WorkManager
 *
 * WorkManager 是这一需求的"标准答案"，但为它要多引一个依赖
 * （work-runtime + 它的传递依赖），而项目已有的 `ReminderAlarms`
 * 已经证明了 `AlarmManager` 这套在本项目里工作良好。
 *
 * 更重要的是**语义匹配**：WorkManager 保证的是"最终会执行"，
 * 适合"必须完成"的后台任务；而自动同步是**尽力而为**的 ——
 * 失败了下一次再试就行，系统为了省电延后执行也完全可以接受。
 * 为一个"尽力而为"的需求引入一套"保证执行"的框架，是拿复杂度换了不需要的东西。
 *
 * ## 为什么用 `setInexactRepeating` 而不是精确闹钟
 *
 * 同步不是"差一分钟都不行"的事。精确闹钟在 Android 12+ 需要
 * `SCHEDULE_EXACT_ALARM` 权限（本项目已因提醒功能持有），
 * 但把它用在同步上纯属浪费 —— 系统为了对齐其他唤醒而产生的几分钟偏差，
 * 对"每 6 小时同步一次"毫无影响。
 *
 * 用不精确的重复闹钟还有额外好处：**系统会把多个应用的唤醒合并**，
 * 对续航更友好。
 */
object SyncAlarms {

    private const val REQUEST_CODE = 0x5A17

    /** 自动同步的间隔：6 小时。 */
    const val INTERVAL_MILLIS = 6L * 60 * 60 * 1000

    /** 排定（或重排）自动同步。已在排定中则先取消，避免重复。 */
    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ?: return
        manager.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + INTERVAL_MILLIS,
            INTERVAL_MILLIS,
            intent,
        )
    }

    /** 取消自动同步（用户在设置页关掉开关时调用）。 */
    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, SyncAlarmReceiver::class.java).setAction(SyncAlarmReceiver.ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    /** 开机/更新后重排（系统重启会清掉所有闹钟）。 */
    fun rescheduleIfEnabled(context: Context, enabled: Boolean) {
        if (enabled) schedule(context) else cancel(context)
    }

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? = runCatching {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, SyncAlarmReceiver::class.java).setAction(SyncAlarmReceiver.ACTION),
            flags,
        )
    }.getOrNull()
}
