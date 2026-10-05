package com.phonlynn.oreplan.platform.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.phonlynn.oreplan.domain.reminder.ReminderPlanner

/**
 * 把「要提醒的时刻」交给系统闹钟。
 *
 * 两个要点：
 *
 * 1. **拿不到精确闹钟权限时降级而不是放弃。** `setAndAllowWhileIdle` 会在到点附近
 *    触发（可能偏差几分钟），但总比「用户以为设了提醒、结果什么都没发生」好。
 * 2. **每个提醒用固定的 requestCode。** requestCode 由 reminderId 派生，
 *    取消时只要重建同一个 PendingIntent 就能对上，不需要额外记录。
 */
object ReminderAlarms {

    private const val ACTION_REMIND = "com.phonlynn.oreplan.action.REMIND"

    fun schedule(context: Context, planned: ReminderPlanner.Planned) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMillis = planned.triggerAt.toEpochMilli()
        val pendingIntent = buildPendingIntent(
            context = context,
            reminderId = planned.reminderId,
            itemId = planned.itemId,
            title = planned.title,
            note = planned.note,
        )

        runCatching {
            if (ReminderPermissions.canScheduleExactAlarms(context)) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
        }
    }

    fun cancel(context: Context, reminderId: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeOf(reminderId),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            manager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    /**
     * requestCode 由 id 派生。
     * 注意：`String.hashCode()` 理论上会撞，但同一条提醒的 id 固定，
     * 撞了也只是两条提醒共用一个闹钟槽，不会崩；相比维护一张映射表，这个代价更划算。
     */
    private fun requestCodeOf(reminderId: String): Int = reminderId.hashCode()

    private fun buildPendingIntent(
        context: Context,
        reminderId: String,
        itemId: String,
        title: String,
        note: String?,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCodeOf(reminderId),
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderReceiver.EXTRA_ITEM_ID, itemId)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_NOTE, note)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Android 12 起需要在设置里授权；这里只暴露一个判断，具体跳转在权限对象里。 */
    fun isExactAllowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            ReminderPermissions.canScheduleExactAlarms(context)
        }
}
