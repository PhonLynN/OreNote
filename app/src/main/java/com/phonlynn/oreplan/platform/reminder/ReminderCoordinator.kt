package com.phonlynn.oreplan.platform.reminder

import android.content.Context
import com.phonlynn.oreplan.data.local.dao.AppMetaDao
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import com.phonlynn.oreplan.domain.reminder.ReminderPlanner
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒与系统闹钟之间的同步器。
 *
 * 策略是**全量重排**而不是增量维护：条目、提醒、例外任何一处变化，就把「应该排哪些闹钟」
 * 整个重算一遍。理由是数据量小（几十条），全量重排的正确性远好于增量 ——
 * 增量维护要在增删改三个路径上分别处理，漏一个就会出现「改了时间但提醒还是旧时刻」。
 *
 * 已排的提醒 id 记在 `app_meta` 里：AlarmManager 无法枚举自己有哪些闹钟，
 * 不记下来就没法取消「已经删掉的提醒」留下的幽灵闹钟。
 */
@Singleton
class ReminderCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemRepository: ItemRepository,
    private val reminderRepository: ReminderRepository,
    private val exceptionRepository: RecurrenceExceptionRepository,
    private val appMetaDao: AppMetaDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 应用启动时调用一次，之后数据一变就自动重排。 */
    fun start() {
        ReminderNotifications.ensureChannel(context)
        scope.launch {
            combine(
                itemRepository.observeAll(),
                reminderRepository.observeEnabled(),
                exceptionRepository.observeAll(),
            ) { items, reminders, exceptions -> Triple(items, reminders, exceptions) }
                .collect { (items, reminders, exceptions) ->
                    applyPlan(items, reminders, exceptions)
                }
        }
    }

    /** 一次性重排。开机、闹钟响过之后、以及从接收器里都走这里。 */
    suspend fun syncNow() {
        applyPlan(
            items = itemRepository.getAll(),
            reminders = reminderRepository.getEnabled(),
            exceptions = exceptionRepository.getAll(),
        )
    }

    private suspend fun applyPlan(
        items: List<com.phonlynn.oreplan.domain.model.Item>,
        reminders: List<com.phonlynn.oreplan.domain.model.Reminder>,
        exceptions: List<com.phonlynn.oreplan.domain.model.RecurrenceException>,
    ) {
        val planned = ReminderPlanner.plan(
            items = items,
            reminders = reminders,
            exceptionsByItem = exceptions.groupBy { it.itemId },
            now = Instant.now(),
            zone = ZoneId.systemDefault(),
        )

        val plannedIds = planned.mapTo(mutableSetOf()) { it.reminderId }
        val previouslyScheduled = readScheduledIds()

        (previouslyScheduled - plannedIds).forEach { staleId ->
            ReminderAlarms.cancel(context, staleId)
        }
        planned.forEach { ReminderAlarms.schedule(context, it) }

        writeScheduledIds(plannedIds)
    }

    private suspend fun readScheduledIds(): Set<String> =
        appMetaDao.find(KEY_SCHEDULED_REMINDERS)
            ?.value
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()

    private suspend fun writeScheduledIds(ids: Set<String>) {
        appMetaDao.upsert(AppMetaEntity(KEY_SCHEDULED_REMINDERS, ids.joinToString(",")))
    }

    private companion object {
        const val KEY_SCHEDULED_REMINDERS = "scheduled_reminder_ids"
    }
}
