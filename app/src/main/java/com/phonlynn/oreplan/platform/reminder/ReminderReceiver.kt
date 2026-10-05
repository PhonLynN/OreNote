package com.phonlynn.oreplan.platform.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟到点。
 *
 * 做完通知之后要**再排下一次**：重复日程平时只排了「下一次」那一个闹钟，
 * 响完如果不补排，这条重复提醒就永久失效了 —— 这是重复提醒实现里最常见的漏洞。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val note = intent.getStringExtra(EXTRA_NOTE)

        ReminderNotifications.notify(
            context = context,
            notificationId = reminderId.hashCode(),
            title = title,
            text = note,
        )

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderEntryPoint.resolve(appContext).syncNow()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_NOTE = "note"
    }
}
