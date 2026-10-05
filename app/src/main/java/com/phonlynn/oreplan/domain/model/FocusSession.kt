package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * 一次专注的计时方式。
 *
 * 存字符串键而不是序号：加新方式（比如以后的自定义时长）不需要迁移，
 * 与项目里 `ItemKind` / `BoardCardType` 同一套约定。
 */
enum class FocusKind(val key: String, val label: String) {
    /** 正计时：只记开始，手动结束。 */
    COUNT_UP("countup", "正计时"),

    /** 倒计时：设一个时长，到点结束。 */
    COUNT_DOWN("countdown", "倒计时"),

    /** 番茄：经典的「专注一段 + 休息」，`plannedMinutes` 记专注段长度。 */
    POMODORO("pomodoro", "番茄"),
    ;

    companion object {
        /** 认不出就按正计时处理 —— 脏数据降级显示，不崩溃（与 Mapper 层一致）。 */
        fun fromKey(key: String?): FocusKind = entries.firstOrNull { it.key == key } ?: COUNT_UP
    }
}

/**
 * 一次专注记录。
 *
 * ## 为什么单独一张表，而不是复用 `habit_logs`
 *
 * `habit_logs` 的语义是「一天一条打勾」，一次专注是「一次一条有起止」。
 * 硬塞进去会让两边都别扭：打卡要按天去重、专注需要净时长与是否走完。
 *
 * ## 它是「数据打通」的一环
 *
 * [itemId] 把一次专注挂到具体的待办/目标上 —— 这正是「课表 → 日程 → 待办 → 专注 → 规划」
 * 这条链上原本缺失的那一段（此前专注与条目之间**没有任何关联**）。
 *
 * 关联被删除的条目时，数据库会**自动把该列置空**（外键 `SET_NULL`）：
 * 专注历史本身有价值，不该跟着任务一起消失。
 */
@Immutable
data class FocusSession(
    val id: String,
    /** 开始时刻。 */
    val startedAt: Instant,
    /** 结束时刻。 */
    val endedAt: Instant,
    /** 净专注分钟数（已扣除暂停）。 */
    val minutes: Int,
    val kind: FocusKind,
    /** 计划时长（倒计时/番茄用）。正计时为 null。 */
    val plannedMinutes: Int? = null,
    /** 是否走完全程。中途放弃 = false。 */
    val completed: Boolean = false,
    /** 本次专注的名字（用户可留空）。 */
    val label: String? = null,
    /** 关联的待办/目标条目 id。可空。条目被删除时自动置空。 */
    val itemId: String? = null,
    val createdAt: Instant,
)
