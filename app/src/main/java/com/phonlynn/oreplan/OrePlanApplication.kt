package com.phonlynn.oreplan

import android.app.Application
import android.util.Log
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.sync.SyncSettings
import com.phonlynn.oreplan.domain.sync.TombstoneRegistry
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.platform.crash.CrashLog
import com.phonlynn.oreplan.platform.reminder.ReminderCoordinator
import com.phonlynn.oreplan.platform.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口。
 *
 * 在这里启动长期观察者：
 *  - 提醒协调器：条目/提醒一变就重排系统闹钟；
 *  - 小组件刷新器：数据一变就刷新桌面小组件；
 *  - 墓碑登记处：装载"已软删"的实体集合（云同步的过滤依据）；
 *  - 同步身份：恢复本机 deviceId（HLC 的 tie-break 依据）。
 *
 * 放在这里而不是各个界面里，是因为「改完数据要通知谁」这件事不该由每个界面自己记 ——
 * 漏掉一处就会出现「改了却看不到变化」。
 */
@HiltAndroidApp
class OrePlanApplication : Application() {

    @Inject
    lateinit var reminderCoordinator: ReminderCoordinator

    @Inject
    lateinit var widgetUpdater: WidgetUpdater

    @Inject
    lateinit var attachmentStorage: AttachmentStorage

    @Inject
    lateinit var attachmentRepository: AttachmentRepository

    @Inject
    lateinit var tombstones: TombstoneRegistry

    @Inject
    lateinit var syncSettings: SyncSettings

    override fun onCreate() {
        super.onCreate()
        // 越早装越好：装得越晚，越可能漏掉启动阶段的崩溃。
        CrashLog.install(this)
        reminderCoordinator.start()
        widgetUpdater.start()
        // 同步相关的启动准备必须在**任何查询之前**尽早发起：
        // 墓碑装载完成前，TombstoneRegistry 刻意不做过滤（见其文档），
        // 那段时间里已删实体是**可见**的 —— 越短越好。
        prepareSync()
        sweepOrphanAttachments()
    }

    /**
     * 装载同步所需的本地状态。
     *
     * ⚠️ 顺序有讲究：先恢复 deviceId（云端合并要靠它做 tie-break），
     * 再装载墓碑。反过来会出现"装载期间产生的事件用了临时 deviceId"。
     */
    private fun prepareSync() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                syncSettings.restoreIdentity()
                tombstones.load()
            }.onFailure { Log.w("OrePlan", "同步状态装载失败", it) }
        }
    }

    /**
     * 清理孤儿附件文件。
     *
     * 附件是选中时**当场**复制进内部存储的（SAF 授权活不到点「保存」那一刻），
     * 所以「复制了文件但没保存条目」必然留下垃圾。这里在启动时后台对一次：
     * 磁盘上存在、库里没有任何索引行的文件就删掉。
     *
     * 放在后台而不是启动路径上：附件通常只有几十个文件，但没必要让它拖慢冷启动。
     */
    private fun sweepOrphanAttachments() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val valid = attachmentRepository.getAll().map { it.storedPath }.toSet()
                val removed = attachmentStorage.sweep(valid)
                if (removed > 0) Log.d("OrePlan", "清理了 $removed 个孤儿附件文件")
            }
        }
    }
}
