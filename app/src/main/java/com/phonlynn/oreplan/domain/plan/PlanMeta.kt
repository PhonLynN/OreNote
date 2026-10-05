package com.phonlynn.oreplan.domain.plan

import com.phonlynn.oreplan.domain.model.ExtMap
import java.time.LocalDate

/**
 * 规划模块的「扩展抽屉」读写口。
 *
 * ## 为什么 0.3.0 的新字段放抽屉而不是加列
 *
 * 项目有一条硬性约定（并有 `SchemaFreezeTest` 守着）：**加新功能不改老表、不做迁移**。
 * 0.3.0 需要给目标补几个新字段：
 *
 * | 字段 | 为什么要新字段 |
 * | --- | --- |
 * | `plan.dailyMinutes` | 习惯目标的「每天目标」（如「每日晨跑 30 分钟」的 30）—— 历史上没存过 |
 * | `plan.checkInTime` | 习惯的提醒时刻（既有的提醒表只存绝对时刻，跨天要重排） |
 * | `plan.remindLeads` | 一个目标最多 3 条提前提醒（既有 `reminders` 一行只对应一条） |
 * | `plan.linkedEvents` | 「关联日程」的多选结果 |
 *
 * 它们都是**附属信息**（删掉不影响目标本身能不能用），正合抽屉的定位。
 * 索引/排序要用的字段才值得那次迁移，这些都不需要。
 *
 * ## 读的成本
 *
 * 抽屉是「一行 JSON、按 owner 精确取」。规划页一次要读几百个目标 —— 因此
 * 这里只提供 **解析**（纯函数），取数据由 ViewModel 一次 `observeAll()` 后建索引。
 */
object PlanMeta {

    const val KEY_DAILY_MINUTES = "plan.dailyMinutes"
    const val KEY_CHECK_IN_TIME = "plan.checkInTime"
    const val KEY_REMIND_LEADS = "plan.remindLeads"
    const val KEY_LINKED_EVENTS = "plan.linkedEvents"

    /** 「每天目标」的分钟数。没有就是 null（目标卡上不显示这一项）。 */
    fun dailyMinutes(map: ExtMap): Int? = map.num(KEY_DAILY_MINUTES)?.toInt()?.takeIf { it > 0 }

    /** 习惯的提醒时刻（HH:mm）。没有就是 null。 */
    fun checkInTime(map: ExtMap): String? = map.text(KEY_CHECK_IN_TIME)?.takeIf { isClock(it) }

    /**
     * 提前提醒档位（分钟列表，最多 3 条，按升序）。
     *
     * 为什么存成字符串而不是数组：抽屉的扁平结构只支持文本/数字/真假三种值
     * （见 [com.phonlynn.oreplan.domain.model.ExtValue]），数组会被转成不可解析的文本。
     * 逗号分隔是这里最朴素也最稳的编码。
     */
    fun remindLeads(map: ExtMap): List<Int> =
        map.text(KEY_REMIND_LEADS)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.distinct()
            ?.sorted()
            ?.take(MAX_REMINDERS)
            .orEmpty()

    fun encodeRemindLeads(leads: List<Int>): String =
        leads.filter { it > 0 }.distinct().sorted().take(MAX_REMINDERS).joinToString(",")

    /** 关联的日程 id。 */
    fun linkedEvents(map: ExtMap): List<String> =
        map.text(KEY_LINKED_EVENTS)
            ?.split(",")
            ?.mapNotNull { it.trim().ifBlank { null } }
            ?.distinct()
            .orEmpty()

    fun encodeLinkedEvents(ids: List<String>): String =
        ids.filter { it.isNotBlank() }.distinct().joinToString(",")

    /**
     * 提醒档位的中文文案：`提前 15 分钟` / `提前 1 小时` / `提前 3 天`。
     * 只有 15/30 分钟、1/2 小时、1/3 天这几档是设计稿给的，其它值按分钟兜底。
     */
    fun leadLabel(minutes: Int): String = when {
        minutes % (24 * 60) == 0 -> "提前 ${minutes / (24 * 60)} 天"
        minutes % 60 == 0 -> "提前 ${minutes / 60} 小时"
        else -> "提前 $minutes 分钟"
    }

    /** 设计稿预设档位（按类型给）：习惯看分钟/小时，有截止日期的看天。 */
    val MINUTE_LEADS = listOf(15, 30, 60, 120)
    val DAY_LEADS = listOf(15, 30, 60, 1440, 4320)

    /** 设计稿底部那行提示：一个目标最多 3 次提醒、两次至少隔 30 分钟。 */
    const val MAX_REMINDERS = 3
    const val MIN_GAP_MINUTES = 30

    /**
     * 加一档提醒。间距不足 30 分钟、或已满 3 档就原样返回
     * （界面据此提示，而不是静默丢弃）。
     */
    fun addLead(current: List<Int>, candidate: Int): List<Int> {
        if (current.size >= MAX_REMINDERS) return current
        if (current.any { kotlin.math.abs(it - candidate) < MIN_GAP_MINUTES }) return current
        return (current + candidate).sorted()
    }

    /** 某天是不是某个习惯的「应打卡日」（频率来自目标的 rrule）。 */
    fun isHabitDay(activeDays: Set<Int>, date: LocalDate): Boolean =
        date.dayOfWeek.value in activeDays

    private fun isClock(text: String): Boolean {
        val parts = text.split(":")
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return h in 0..23 && m in 0..59
    }
}
