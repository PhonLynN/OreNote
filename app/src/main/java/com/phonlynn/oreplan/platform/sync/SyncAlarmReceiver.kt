package com.phonlynn.oreplan.platform.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phonlynn.oreplan.domain.sync.SyncRunner
import com.phonlynn.oreplan.domain.sync.SyncSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 后台自动同步的入口。
 *
 * ## 为什么用 `goAsync()`
 *
 * `onReceive` 返回后系统随时可能杀掉进程。同步要发网络请求、要写数据库，
 * 必须用 `goAsync()` 拿一个 `PendingResult` 告诉系统"我还没做完" ——
 * 否则表现是"自动同步偶尔不生效"，而且**不报错**，极难查。
 *
 * 与项目里既有的 `BootReceiver` 是同一套写法（含 `finally` 里 `finish()`）。
 *
 * ## ⚠️ 时间预算
 *
 * BroadcastReceiver 的 `goAsync()` 只有约 10 秒。附件多的时候一次完整同步
 * 可能超时 —— 超时后进程被回收，同步中断。
 *
 * 但这不是灾难：**同步是幂等的**，中断后下一次会接着做（已传的对象会被跳过）。
 * 所以这里不需要引入前台服务来"保证完成"—— 那会带来常驻通知，
 * 对"每 6 小时同步一次"来说是不必要的打扰。
 */
@AndroidEntryPoint
class SyncAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var runner: SyncRunner
    @Inject lateinit var settings: SyncSettings

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 先刷新配置：进程可能刚被拉起，内存里的 state 还是默认值。
                // 不刷新的话 `enabled` 会是 false，自动同步永远不会执行。
                settings.refresh()
                if (!settings.state.value.enabled || !settings.state.value.autoSync) return@launch
                runner.run(requireEnabled = true)
            } catch (_: Throwable) {
                // 后台同步失败**不通知用户**：他没在看，弹个失败提示只会造成困扰。
                // 结果已经记进 settings.lastSyncResult，打开设置页就能看到。
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.phonlynn.oreplan.action.AUTO_SYNC"
    }
}
