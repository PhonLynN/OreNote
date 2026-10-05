package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/**
 * 提醒参数的解析与写入 —— **条目工具与卡片工具共用这一套**。
 *
 * ## 用户口径：提醒是三类通用的
 *
 * > 「日程、待办、卡片都可以设置提醒吧」
 *
 * 对。底层都是 `Reminder.itemId → Item.id`，只是**宿主不同**：
 *
 * | 宿主 | 承载提醒的 Item |
 * |---|---|
 * | 日程 / 待办 / 目标 | 就是它自己 |
 * | 白板卡片 | 隐藏条目 `card_alarm_{cardId}` |
 * | 习惯提醒 | 隐藏条目 `habitAlarm_{id}` |
 *
 * 所以"怎么解析提前量"是共用的，**"挂到哪个 itemId 上"由各自决定**（见各自的工具）。
 *
 * ## ⚠️ 为什么收"提前多久"而不是绝对时刻
 *
 * `Reminder.triggerAt` 存的是**绝对时刻**（设计如此：重复日程每个实例的提醒
 * 时刻都不同，存绝对时刻让 AlarmManager 不必理解重复规则）。
 *
 * 但用户说的是「提前 15 分钟」—— **模型也只会说这种话**。
 * 让它自己算 `2026-10-04T14:00` 减 15 分钟，就是明知它不擅长还硬让它做。
 * 所以参数收提前量，换算在这里做一次。
 *
 * ## 三态，和 `clear_time` 同一个套路
 *
 * ```
 * { "remind_before": "15分钟" }   设成提前 15 分钟
 * { "remind_before": "none" }     去掉提醒
 * { 不传 }                        不动 / 用默认
 * ```
 */
internal object Reminders {

    /** 提醒时刻是否有效（必须晚于现在，否则设了也不会响）。 */
    fun isFuture(triggerAt: Instant, now: Instant = Instant.now()): Boolean = triggerAt.isAfter(now)

    /**
     * 解析「提前多久」。
     *
     * @return `null` = **没传**（不动现有的提醒）；
     *   [ReminderSpec.None] = 明确要去掉；[ReminderSpec.Before] = 提前多久。
     *
     * ## 为什么要区分"没传"和"去掉"
     *
     * 与 `show_date` 那个三态同一个道理：**没传**表示"别碰我现有的提醒"，
     * 而 `去掉` 是明确指令。混成一个的话，改个标题就会把提醒删掉。
     */
    fun parse(input: String?): ReminderSpec? {
        val text = input?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null

        return when (text) {
            "none", "no", "off", "false", "不提醒", "不要提醒", "取消提醒", "去掉提醒", "无" ->
                ReminderSpec.None

            "0", "0分钟", "准点", "开始时" -> ReminderSpec.Before(0)

            else -> parseDuration(text)?.let { ReminderSpec.Before(it) }
        }
    }

    /**
     * 解析时长。认这几种写法（模型和用户都可能写）：
     *
     * ```
     * 15分钟 / 15分 / 15m / 15 min
     * 1小时 / 1h / 60分钟
     * 1天 / 1d / 24小时
     * 1周 / 1w
     * ```
     *
     * 认不出来返回 `null` —— 由调用方决定是"忽略"还是"报错给模型"。
     * **不要猜**：猜错的后果是提醒在该响的时候没响，而用户不会怀疑是换算错了。
     */
    fun parseDuration(text: String): Int? {
        val t = text.trim().lowercase().replace(" ", "")

        // 单个数字 = 分钟（"15" 是最常见的简写）
        t.toIntOrNull()?.let { return it.takeIf { n -> n >= 0 } }

        val m = DURATION.find(t) ?: return null
        val value = m.groupValues[1].toIntOrNull() ?: return null
        if (value < 0) return null

        val minutes = when (m.groupValues[2]) {
            "分钟", "分", "min", "mins", "minute", "minutes", "m" -> value
            "小时", "时", "hour", "hours", "hr", "hrs", "h" -> value * 60
            "天", "day", "days", "d" -> value * 24 * 60
            "周", "星期", "week", "weeks", "w" -> value * 7 * 24 * 60
            else -> return null
        }
        // 上限一个月：再长就不是"提前提醒"了，多半是模型理解错了
        return minutes.takeIf { it <= MAX_MINUTES }
    }

    /** 提前量 → 展示文案（`15 分钟` / `1 小时` / `1 天`）。 */
    fun label(minutes: Int): String = when {
        minutes == 0 -> "准点"
        minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} 天"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "$minutes 分钟"
    }

    /**
     * 把提醒设成"提前 [minutes] 分钟"，或（传 null）去掉。
     *
     * ## 只处理"提前量"，不处理宿主
     *
     * 调用方给出 [hostItemId] —— 条目工具给条目自己的 id，
     * 卡片工具给 `card_alarm_{cardId}` 那个隐藏条目。**这里不认识卡片**。
     *
     * @param reference 相对于哪个时刻提前（日程的 startAt）。
     *   为 null（如无时刻的待办）时**不做任何事**并返回 false ——
     *   没有参照点就算不出提醒时刻，硬设一个只会乱响。
     * @return 是否真的写入了
     */
    suspend fun set(
        repository: ReminderRepository,
        hostItemId: String,
        reference: Instant?,
        minutes: Int?,
    ): Boolean {
        // 去掉提醒
        if (minutes == null) {
            repository.deleteOf(hostItemId)
            return true
        }

        // 没有参照时刻 → 设不了。调用方据此告诉模型"这条待办没有时间，设不了提醒"
        val referenceAt = reference ?: return false
        val triggerAt = referenceAt.minus(Duration.ofMinutes(minutes.toLong()))

        /*
         * ⚠️ 提醒时刻已经过去时不写入。
         *
         * 用户说"提前 1 天提醒"而日程是两小时后 —— 算出来的时刻在过去，
         * 系统不会响。写进去只会让用户以为设好了。
         */
        if (!isFuture(triggerAt)) return false

        val existing = repository.observeOf(hostItemId).first().firstOrNull()
        repository.upsert(
            if (existing != null) {
                // 已有就改时刻与提前量，保留 id（重新插入会换 id，调度器那边要重排）
                existing.copy(triggerAt = triggerAt, offsetMinutes = minutes, enabled = true)
            } else {
                Reminder(
                    id = Ids.newId(),
                    itemId = hostItemId,
                    triggerAt = triggerAt,
                    offsetMinutes = minutes,
                    enabled = true,
                )
            },
        )
        return true
    }

    /** `15分钟` / `1h` / `2 天` → 1 / 2。 */
    private val DURATION = Regex("^(\\d+)(分钟|分|min|mins|minute|minutes|m|小时|时|hour|hours|hr|hrs|h|天|day|days|d|周|星期|week|weeks|w)$")

    /** 提前量上限：一个月。再长就不像"提醒"了。 */
    private const val MAX_MINUTES = 30 * 24 * 60

    /**
     * 直接按**绝对时刻**设提醒 —— 待办走这条。
     *
     * ## 为什么待办需要它
     *
     * 「提前多久」需要一个参照点（日程的 `startAt`）。而**待办没有时刻** ——
     * 它是"没有确定时间的事"。所以对待办来说，唯一能表达提醒的方式就是
     * 给一个绝对时刻（「明天九点提醒我」）。
     *
     * 用户口径是「不管什么时候，我告诉 AI 添加一条带提醒的待办」——
     * 所以这条路径**必须存在**，否则带提醒的待办永远建不出来。
     *
     * @return 是否真的写入了（时刻在过去时返回 false）
     */
    suspend fun setAt(repository: ReminderRepository, hostItemId: String, triggerAt: Instant): Boolean {
        if (!isFuture(triggerAt)) return false

        val existing = repository.observeOf(hostItemId).first().firstOrNull()
        repository.upsert(
            if (existing != null) {
                existing.copy(triggerAt = triggerAt, enabled = true)
            } else {
                Reminder(
                    id = Ids.newId(),
                    itemId = hostItemId,
                    triggerAt = triggerAt,
                    // 绝对时刻没有"提前量"可言，存 0 表示准点
                    offsetMinutes = 0,
                    enabled = true,
                )
            },
        )
        return true
    }
}

/** 「提前多久」的解析结果。 */
internal sealed interface ReminderSpec {
    /** 明确要去掉提醒。 */
    data object None : ReminderSpec

    /** 提前 [minutes] 分钟（0 = 准点）。 */
    data class Before(val minutes: Int) : ReminderSpec
}
