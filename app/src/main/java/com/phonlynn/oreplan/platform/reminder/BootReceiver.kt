package com.phonlynn.oreplan.platform.reminder

import android.content.BroadcastReceiver
import android.content.Context
import com.phonlynn.oreplan.platform.sync.SyncAlarms
import com.phonlynn.oreplan.platform.sync.SyncEntryPoint
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机后重建全部闹钟。
 *
 * 系统在重启时会清掉所有 AlarmManager 闹钟，不重建的话，用户会发现
 * 「重启一次手机，所有提醒就静默消失了」。这个 Receiver 就是专门堵这个洞的。
 *
 * **云同步的定时闹钟同理** —— 它也走 AlarmManager，也会被重启清掉。
 * `ACTION_MY_PACKAGE_REPLACED`（覆盖安装）也要处理：那是本项目最常见的
 * 交付方式，用户更新一次版本，同步闹钟不该就此消失。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderEntryPoint.resolve(appContext).syncNow()
                rescheduleAutoSync(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 重建云同步的定时闹钟。
     *
     * 只在用户开着"自动同步"时才排 —— 否则每次开机都排一个
     * 用户已经关掉的闹钟，它在后台醒来发现不该跑然后退出，纯属浪费。
     */
    private suspend fun rescheduleAutoSync(context: Context) {
        runCatching {
            val point = SyncEntryPoint.resolve(context)
            val settings = point.syncSettings()
            settings.refresh()
            SyncAlarms.rescheduleIfEnabled(
                context = context,
                enabled = settings.state.value.enabled && settings.state.value.autoSync,
            )
        }
    }
}
