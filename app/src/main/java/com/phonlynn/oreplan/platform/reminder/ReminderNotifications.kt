package com.phonlynn.oreplan.platform.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phonlynn.oreplan.MainActivity
import com.phonlynn.oreplan.R

/**
 * 提醒通知。
 *
 * 用独立的渠道而不是默认渠道：提醒属于「高优先级、需要打断」的一类，
 * 用户如果想静音它，应该能单独关掉而不影响其他通知。
 */
object ReminderNotifications {

    const val CHANNEL_ID = "oreplan.reminders"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "日程提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "日程与待办在到点前的提醒"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notify(context: Context, notificationId: Int, title: String, text: String?) {
        // 没授权就直接不发。让 notify() 抛异常或静默丢弃都比这更糟：
        // 前者会崩在 BroadcastReceiver 里，后者连日志都没有。
        if (!ReminderPermissions.hasNotificationPermission(context)) return

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text ?: title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching { manager.notify(notificationId, notification) }
    }
}
