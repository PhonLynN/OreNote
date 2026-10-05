package com.phonlynn.oreplan.platform.reminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 提醒相关的两个权限。
 *
 * 这两条是日程类应用最容易踩的坑：
 *  - Android 13 起发通知需要运行时授权，没授权时通知会被系统直接丢掉；
 *  - Android 12 起 `setExactAndAllowWhileIdle` 需要用户手动授予「闹钟与提醒」。
 *
 * 都不能假设已经拿到，也都不应该默默失败 —— 拿不到就降级并把入口给出来。
 */
object ReminderPermissions {

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 能否使用精确闹钟。低于 Android 12 的系统默认可以。 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /** 跳到系统的「闹钟与提醒」授权页。 */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
                // 从 Compose 里用应用上下文启动时需要这个标志，否则会抛异常
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            appNotificationSettingsIntent(context)
        }

    /** 跳到本应用的通知设置页。 */
    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
