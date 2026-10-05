package com.phonlynn.oreplan.domain.focus

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate

/**
 * 专注计时方式 —— 0.3.0 新规划的核心。
 *
 * 与既有的 `domain.model.FocusKind` 是**同一组取值**（存库时也共用它的 key），
 * 这里另立一份是为了把「计时行为」和「记录字段」分开：
 * 前者是运行期的东西（有没有计划时长、会不会自动结束），后者是落库的形状。
 */
enum class FocusMode(val key: String, val label: String) {
    /** 倒计时：设一个时长，走完自动结束（设计稿「倒计时」）。 */
    COUNT_DOWN("countdown", "倒计时"),

    /** 正计时：只记开始，手动结束（设计稿「正计时」）。 */
    COUNT_UP("countup", "正计时"),

    /** 番茄钟：25 分钟一段，走完提示休息，可继续下一段（设计稿「番茄钟」）。 */
    POMODORO("pomodoro", "番茄钟"),
    ;

    /** 只有倒计时类型的模式才有「计划时长」概念（正计时没有）。 */
    val hasPlan: Boolean get() = this != COUNT_UP

    /** 到点后是不是自动结束。番茄钟自动进入休息提示，不算结束。 */
    val autoFinish: Boolean get() = this == COUNT_DOWN

    companion object {
        fun fromKey(key: String?): FocusMode = entries.firstOrNull { it.key == key } ?: COUNT_UP
    }
}

/**
 * 专注记录怎么来的。
 *
 * 计时器自己落库的记录不该被当成「用户手填」，否则会把「专注完成」页的
 * 反馈流程重复走一遍。存字符串（[key]）与项目里其他枚举一致。
 */
enum class FocusSource(val key: String) {
    /** 计时器跑完自动/手动结束落库。 */
    TIMER("timer"),

    /** 用户在别处手填的一条记录（暂无入口，保留取值以免以后加时又要迁移）。 */
    MANUAL("manual"),
    ;

    companion object {
        fun fromKey(key: String?): FocusSource = entries.firstOrNull { it.key == key } ?: TIMER
    }
}

/**
 * 一次专注要挂在什么对象上。
 *
 * 设计稿「专注对象」三选一：自由专注 / 关联目标 / 关联日程。
 * 自由专注就是 [None]；日程不落库（它本身就带时间），所以只有目标会写进记录。
 */
@Immutable
sealed interface FocusTarget {
    data object None : FocusTarget

    /** 关联某个目标（规划页里的 GOAL 条目）。 */
    data class Goal(val id: String, val title: String) : FocusTarget

    /** 关联某条今天的日程。 */
    data class Event(val id: String, val title: String) : FocusTarget

    /** 记录上要落的条目 id（日程也落，方便「这条日程我专注过多久」）。 */
    val itemIdOrNull: String?
        get() = when (this) {
            None -> null
            is Goal -> id
            is Event -> id
        }

    val titleOrNull: String?
        get() = when (this) {
            None -> null
            is Goal -> title
            is Event -> title
        }
}

/**
 * 运行中的一次专注（**内存态，不落库**）。
 *
 * 为什么计时器不落库：一次专注从开始到结束是几十秒到几小时的过程，
 * 中间要暂停、要跨页面（规划页 / 专注页 / 悬浮窗），途中落库会写出大量中间态记录，
 * 崩溃后还会留下「永远停在 12:30」的脏数据。这里只在**结束时**写一条。
 */
@Immutable
data class ActiveFocus(
    val target: FocusTarget = FocusTarget.None,
    val mode: FocusMode = FocusMode.COUNT_DOWN,
    /** 计划时长（分钟）。正计时为 null。 */
    val plannedMinutes: Int? = null,
    val startedAt: Instant = Instant.now(),
    /**
     * 已经计入的秒数（不含正在跑的这一段）—— **是整次专注的总时长**，
     * 番茄钟换段时不清零（那会把总时长算丢）。
     */
    val accumulatedSeconds: Long = 0,
    /** 当前这一段的起点；null = 暂停中。 */
    val segmentStartedAt: Instant? = Instant.now(),
    /**
     * **本段**的墙钟基准点。正常运行中它等于 [segmentStartedAt]；
     * 暂停时控制器会把它挪到「暂停那一刻」，于是「本段已走」不会被暂停时长污染。
     */
    val cycleStartedAt: Instant = segmentStartedAt ?: startedAt,
    /** 第几段（番茄钟用；从 1 开始）。 */
    val cycle: Int = 1,
) {
    val isPaused: Boolean get() = segmentStartedAt == null

    /** 从本段锚点到 [now] 走过的墙钟秒数（含暂停时间）。 */
    private fun sinceAnchor(now: Instant): Long = java.time.Duration.between(cycleStartedAt, now).seconds

    /** 到 [now] 为止的净专注秒数（**整次专注的总时长**）。 */
    fun elapsedSeconds(now: Instant): Long =
        accumulatedSeconds + if (isPaused) 0L else sinceAnchor(now)

    /**
     * 本段（番茄钟的当前那一段）已经走了多少秒。
     *
     * 与 [elapsedSeconds] 分开：番茄钟第 2 段的「剩余 15 分钟」是相对**本段**说的，
     * 用总时长去算会得出「已经超时」的荒谬结论。
     */
    fun segmentElapsedSeconds(now: Instant): Long =
        if (isPaused) 0L else sinceAnchor(now)

    /** 倒计时/番茄钟的本段剩余秒数；正计时为 null。 */
    fun remainingSeconds(now: Instant): Long? =
        plannedMinutes?.let { (it * 60L - segmentElapsedSeconds(now)).coerceAtLeast(0L) }

    /** 环形进度 0..1（本段已走的比例）。正计时用一分钟一圈，只为让环在动。 */
    fun progress(now: Instant): Float {
        val plan = plannedMinutes?.times(60L)
        return if (plan != null && plan > 0) {
            (segmentElapsedSeconds(now).toDouble() / plan).coerceIn(0.0, 1.0).toFloat()
        } else {
            val sec = elapsedSeconds(now) % 60L
            (sec / 60.0).toFloat()
        }
    }

    /** 倒计时/番茄是否已经走完本段。 */
    fun isElapsed(now: Instant): Boolean =
        segmentElapsedSeconds(now) >= (plannedMinutes?.times(60L) ?: Long.MAX_VALUE)
}

/** 专注结束后、还没保存的那份结果（「专注完成」页的数据源）。 */
@Immutable
data class FocusResult(
    val target: FocusTarget,
    val mode: FocusMode,
    val plannedMinutes: Int?,
    val elapsedSeconds: Long,
    val startedAt: Instant,
    val endedAt: Instant,
) {
    /**
     * 实际净专注分钟，**至少 1 分钟**（做过就该留下痕迹）。
     *
     * 按 30 秒四舍五入：29 秒 → 1 分钟，30 秒 → 1 分钟，89 秒 → 1 分钟，90 秒 → 2 分钟。
     * 界面上显示的是 `分钟数 × 60`，所以展示与落库口径永远一致。
     */
    val minutes: Int get() = ((elapsedSeconds + 30) / 60).toInt().coerceAtLeast(1)

    /**
     * 记录里那个「结束时刻」。
     *
     * 不能直接用结束那一瞬的墙钟：中途暂停过的话，墙钟跨度会把暂停时长也算进去，
     * 于是「00:50 – 00:50」这种自相矛盾的区间就出现了。改成「起点 + 净时长」，
     * 与 [minutes] 完全对齐。
     */
    val recordedEndAt: Instant get() = startedAt.plusSeconds(minutes * 60L)

    /**
     * 计时器是否走完了计划时长（倒计时/番茄）。正计时恒为 true。
     *
     * 这里的 [minutes] 已经是「净专注分钟」，用它比 [elapsedSeconds] 更稳：
     * `elapsedSeconds` 还包含用户按结束前那一瞬的零头。
     */
    val reachedPlan: Boolean =
        plannedMinutes?.let { minutes >= it } ?: true

    /** 实际与计划之差（分钟，正数=超出）。无计划时为 null。 */
    val overMinutes: Int?
        get() = plannedMinutes?.let { minutes - it }
}

/** 专注记录页/统计要用的「一天」。 */
@Immutable
data class FocusDaySummary(
    val date: LocalDate,
    val minutes: Int,
    val sessions: Int,
)

/** 热力图的一格。 */
@Immutable
data class HeatCell(
    val date: LocalDate,
    val minutes: Int,
    /** 0..4 的强度档（0 = 无）。 */
    val level: Int,
)
