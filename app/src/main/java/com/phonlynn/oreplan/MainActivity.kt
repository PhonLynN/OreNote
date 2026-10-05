package com.phonlynn.oreplan

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.phonlynn.oreplan.platform.reminder.ReminderPermissions
import com.phonlynn.oreplan.v2.V2Root
import com.phonlynn.oreplan.v2.theme.VTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * 通知权限的**运行时申请**（Android 13+）。
     *
     * ## 这里曾经是一个致命缺口（问题 #2「通知/提醒完全无效」的根因）
     *
     * 全项目**只有检查、没有任何申请**：
     *  - `ReminderPermissions.hasNotificationPermission()` 只是个 `checkSelfPermission`；
     *  - 它唯一的调用点是 `ReminderNotifications.notify()` 的**开头守卫**：
     *    `if (!hasNotificationPermission(context)) return` —— 没权限就**静默返回**；
     *  - 全仓库 grep 不到 `RequestPermission` / `requestPermissions`（逐个确认过），
     *    也就是说应用**从来不会弹出授权对话框**。
     *
     * Android 13 起 `POST_NOTIFICATIONS` 默认**拒绝** ⇒ 上面那条守卫永远成立
     * ⇒ **每一条提醒都被丢掉，且没有任何日志**。这正是用户报的「完全无效」。
     *
     * 设备实测（改动前）：`cmd appops get … POST_NOTIFICATION` → `ignore`。
     *
     * ## 为什么放在启动时申请
     *
     * 提醒可以在任何页面被设置（日程/待办/卡片），没有一个必然经过的"设置提醒"入口，
     * 所以启动时申请覆盖最完整。重复调用安全：已授权时下面会提前 return；
     * 被拒绝两次后系统自己不再弹窗。
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 结果无需在此处理：真实状态随时由 ReminderPermissions 复查。
        // （不做提示打扰：这一刻用户可能根本没在设提醒。）
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 状态栏与导航栏全部透明，让极光背景铺满整屏；图标明暗跟随系统深浅色自动切换。
        // 不透明会截断背景，玻璃面板的「透」就失去参考物。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            VTheme {
                V2Root()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ReminderPermissions.hasNotificationPermission(this)) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
