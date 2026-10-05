package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * 卡片提醒的读写。
 *
 * ## 卡片自己挂不了提醒
 *
 * 提醒调度器（`ReminderPlanner`）只认「有 `startAt` 的**条目** + 提醒」。
 * 而卡片是独立实体（`BoardCard`，不挂 `Item`）—— 所以卡片要提醒，
 * 必须**先建一条不可见的载体条目**：
 *
 * ```
 * 卡片  →  载体条目 HABIT_ALARM（id = card_alarm_{cardId}）  →  Reminder
 * ```
 *
 * 载体在 `AgendaBuilder` 里被显式跳过，所以时间轴、待办、规划列表都看不到它。
 *
 * ## ⚠️ 前缀以前硬编码在 4 个地方
 *
 * `"card_alarm_"` 原本散落在 `BoardCardScreenV2`（3 处）与 `BoardScreenV2`（1 处）。
 * 任何一处写错，提醒就会**静默对不上** —— 不报错，只是不响。
 * 现在只此一处（[alarmIdOf]），其余全部改调它。
 *
 * ## 为什么放在 domain 而不是抽 UseCase
 *
 * 原本这套逻辑写在卡片编辑页的 ViewModel 里。要让 AI 工具也用上，
 * 有两条路：
 *
 * · 抽成一个 UseCase，注入给两边 —— 更"正统"，但要动现有页面
 * · 抽成一个**无状态的领域函数**，两边都调它 —— 改动最小
 *
 * 选了后者：这里的逻辑本来就是纯的（给定 id / 标题 / 时刻，写两条记录），
 * 没有需要持有的状态，做成 object 更贴切，也避免为"抽依赖"而改页面构造函数。
 */
object CardReminders {

    /** 载体条目的 id 前缀。**只在这里定义一次。** */
    private const val PREFIX = "card_alarm_"

    /** 卡片 id → 承载它提醒的载体条目 id。 */
    fun alarmIdOf(cardId: String): String = PREFIX + cardId

    /**
     * 把卡片的提醒设成 [at]，或（传 null）清掉。
     *
     * 全量覆盖语义：**先清掉旧的载体与提醒，再按新值重建**。
     * 这样"改了时刻""去掉了提醒""本来就没有"三种情况走同一条路，
     * 不会出现"改时刻后残留两条提醒"这种脏状态。
     *
     * ⚠️ 载体条目本身也要删干净 —— 只删 `Reminder` 会留下一个
     * 不可见、但会参与同步、还会占着 id 的孤儿条目。
     */
    suspend fun set(
        items: ItemRepository,
        reminders: ReminderRepository,
        cardId: String,
        title: String?,
        at: Instant?,
    ) {
        val alarmId = alarmIdOf(cardId)

        // 先清旧的（载体 + 提醒一起）
        items.getById(alarmId)?.let { items.deleteSubtree(it.id) }
        reminders.deleteOf(alarmId)

        if (at == null) return

        val carrier = Item.newRoot(
            kind = ItemKind.HABIT_ALARM,
            title = "卡片提醒：${title?.takeIf { it.isNotBlank() } ?: "未命名卡片"}",
            now = Instant.now(),
            id = alarmId,
        ).copy(startAt = at)

        items.create(carrier)
        reminders.upsert(
            Reminder(
                id = Ids.newId(),
                itemId = alarmId,
                triggerAt = at,
                // 绝对时刻没有"提前量"可言，存 0 表示准点
                offsetMinutes = 0,
                enabled = true,
            ),
        )
    }

    /**
     * 删卡片时清理它留下的载体条目与提醒。
     *
     * **不清理的话会留下孤儿**：不可见、但会参与云同步、还会在该响的时候响。
     */
    suspend fun clear(items: ItemRepository, reminders: ReminderRepository, cardId: String) {
        set(items, reminders, cardId, title = null, at = null)
    }

    /** 读卡片当前的提醒时刻（没有则 null）。 */
    suspend fun triggerOf(reminders: ReminderRepository, cardId: String): Instant? =
        reminders.observeOf(alarmIdOf(cardId)).first().firstOrNull()?.triggerAt
}
