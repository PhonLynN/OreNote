package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.QuantityLog
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDurationCell
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 规划模块的**共享口径** —— 进度怎么算、类型怎么配色、日期怎么写。
 *
 * ## 这个文件里不该再出现什么（用户 2026-10-02 的要求）
 *
 * 「和其他页面一致」不是「调用一下公共件」，而是**同一件事只能有一份实现**。
 * 所以这里**不再有**任何与 `v2/components/` 重复的 UI 件。此前那批已全部删除：
 *
 * | 删掉的 | 现在的唯一实现 |
 * | --- | --- |
 * | `PlanFieldLabel` | `VText(label, VTypo.body, color = VColors.ink2)`（就是一行文字） |
 * | `PlanValueRow`（54dp） | `VSettingRow`（50dp，可 `showChevron = false`） |
 * | `PlanSwitchRow`（56dp） | `VSettingSwitchRow`（50dp，可带徽章） |
 * | `PlanSectionHead`（note 11sp） | `VSectionHead`（note 12sp） |
 * | `PlanLinkRow` | `VChipSmall` + `VChevron` |
 * | `PlanEntryRow` | `VRow` |
 * | `PlanDatePickerDialog`（Material3 那版） | `VDatePickerDialog`（点即确认，全站同款） |
 * | `PlanTimePickerDialog`（自造步进盘） | `VTimePickerDialog` |
 * | `PlanBottomSpacer` / `focusDurationText` / `SoftIcon` / `StatusPill` / `TypeBadge` | 本来就没用上，或 `VChip` / `VBadge` |
 *
 * 留下的是**规划独有的业务口径**（进度、配色、日期文案）与**一个弹窗**
 * （[PlanDurationDialog]，公共层确实没有对应件）。
 */
object PlanUi {

    fun typeLabel(type: GoalType): String = when (type) {
        GoalType.STEP -> "分步目标"
        GoalType.QUANTITY -> "数量目标"
        GoalType.HABIT -> "习惯目标"
    }

    /** 类型主色：分步 accent 绿 / 数量 lilac 紫 / 习惯 amber 琥珀。 */
    fun typeColor(type: GoalType): Color = when (type) {
        GoalType.STEP -> VColors.accent
        GoalType.QUANTITY -> VColors.lilac
        GoalType.HABIT -> VColors.amber
    }

    fun typeSoft(type: GoalType): Color = when (type) {
        GoalType.STEP -> VColors.accentSoft
        GoalType.QUANTITY -> VColors.lilacSoft
        GoalType.HABIT -> VColors.amberSoft
    }

    /** 类型徽章图标（设计稿：分步 `list-checks`、数量 `target`、习惯 `repeat`）。 */
    fun typeIcon(type: GoalType): ImageVector = when (type) {
        GoalType.STEP -> Lucide.ListChecks
        GoalType.QUANTITY -> Lucide.Target
        GoalType.HABIT -> Lucide.Repeat
    }

    /** 新建目标页的类型副标题（设计稿：按步骤推进 / 累计数值 / 每日坚持）。 */
    fun typeHint(type: GoalType): String = when (type) {
        GoalType.STEP -> "按步骤推进"
        GoalType.QUANTITY -> "累计数值"
        GoalType.HABIT -> "每日坚持"
    }

    // ---------------------------------------------------------------- 日期 / 文案

    /** "6月30日" */
    fun monthDay(d: LocalDate): String = "${d.monthValue}月${d.dayOfMonth}日"

    /** "3月10日 – 3月16日" */
    fun rangeText(start: LocalDate, end: LocalDate): String =
        "${start.monthValue}月${start.dayOfMonth}日 – ${end.monthValue}月${end.dayOfMonth}日"

    /** 页头副标题："第 3 周 · 3月10日 – 3月16日"（没有学期时只有日期段）。 */
    fun weekOverline(term: com.phonlynn.oreplan.domain.model.Term?, today: LocalDate): String {
        val weekStart = TermClock.weekStartOf(today)
        val range = rangeText(weekStart, weekStart.plusDays(6))
        val week = term?.let { TermClock.weekNumberOn(it.startDate, it.totalWeeks, today) }
        return if (week != null) "第 $week 周 · $range" else range
    }

    /**
     * 目标卡右上角：分步/数量显示**剩余时间**，习惯显示**连续天数**。
     * 设计稿三种写法：`剩余 6 小时` / `剩余 14 天` / `连续 14 天`。
     */
    fun dueText(due: LocalDate?, today: LocalDate, now: LocalTime = LocalTime.now()): String? {
        due ?: return null
        val endOfDue = due.atTime(23, 59, 59)
        val nowAt = today.atTime(now)
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            endOfDue.isBefore(nowAt) -> if (days <= -1) "逾期 ${-days} 天" else "逾期"
            due == today -> {
                val minutes = ChronoUnit.MINUTES.between(nowAt, endOfDue).coerceAtLeast(1)
                if (minutes < 60) "剩余 $minutes 分钟" else "剩余 ${minutes / 60} 小时"
            }
            days == 1L -> "剩余 1 天"
            else -> "剩余 $days 天"
        }
    }

    /** 详情弹层里的「剩余 N 天」数值。 */
    fun remainingDays(due: LocalDate?, today: LocalDate): String? {
        due ?: return null
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            days < 0 -> "逾期 ${-days} 天"
            days == 0L -> "今天"
            else -> "$days 天"
        }
    }

    fun toLocalDate(instant: Instant?, zone: ZoneId): LocalDate? = instant?.atZone(zone)?.toLocalDate()

    /** 截止日期落库时刻：当天 23:59:59。 */
    fun toDueInstant(day: LocalDate, zone: ZoneId): Instant =
        day.atTime(23, 59, 59).atZone(zone).toInstant()

    // ---------------------------------------------------------------- 分步

    fun stepDoneTotal(steps: List<Item>): Pair<Int, Int> {
        val total = steps.size
        val done = steps.count { it.status == ItemStatus.DONE }
        return done to total
    }

    /** 下一步：取第一个未完成（顺序解锁由 ViewModel 决定能不能勾）。 */
    fun nextStep(steps: List<Item>): Item? = steps.firstOrNull { it.status != ItemStatus.DONE }

    /**
     * 里程碑数：设计稿的 `4 / 6`。
     * 子项数不一定等于里程碑数，这里按每 2 步一个里程碑折算 —— 与详情弹层里
     * 「里程碑路线」的分组保持一致（路线里每 2 个节点之间画一段连接线）。
     */
    fun milestoneDone(done: Int): Int = done / MILESTONE_STEP

    fun milestoneTotal(total: Int): Int = if (total <= 0) 0 else (total + MILESTONE_STEP - 1) / MILESTONE_STEP

    private const val MILESTONE_STEP = 2

    // ---------------------------------------------------------------- 数量

    fun quantityTotal(logs: List<QuantityLog>): Long = logs.sumOf { it.amount }

    /** 数量目标的百分比（0..100）。 */
    fun quantityPercent(goal: Item, logs: List<QuantityLog>): Int {
        val target = goal.targetValue ?: 0L
        if (target <= 0) return 0
        return ((quantityTotal(logs).toDouble() / target) * 100).roundToInt().coerceIn(0, 100)
    }

    /** 数量目标的卡片脚注：`数量 · 已读 7 / 12 本`（设计稿）。 */
    fun quantityFoot(goal: Item, logs: List<QuantityLog>): String {
        val unit = goal.unit.orEmpty()
        val current = quantityTotal(logs)
        val target = goal.targetValue ?: 0L
        val verb = quantityVerb(goal.title)
        val head = if (verb == null) "数量 ·" else "数量 · $verb"
        val sep = if (unit.isBlank()) "" else " "
        return "$head $current / $target$sep$unit"
    }

    /** 从目标标题里猜一个动词给脚注用（`读完 12 本书` → 「已读」）。认不出就不加动词。 */
    private fun quantityVerb(title: String): String? = when {
        title.contains("读") || title.contains("书") -> "已读"
        title.contains("投") || title.contains("简历") -> "已投"
        title.contains("写") || title.contains("笔记") -> "已写"
        else -> null
    }

    // ---------------------------------------------------------------- 习惯

    /**
     * 连续天数：今天没打卡就从昨天开始往前数 ——
     * 「今天还没打卡」不该把连续清零（用户上午打开应用就会看到连续归零，那是错的）。
     */
    fun streak(epochDays: Set<Int>, todayEpoch: Int): Int {
        var cursor = if (todayEpoch in epochDays) todayEpoch else todayEpoch - 1
        var streak = 0
        while (cursor in epochDays) {
            streak++
            cursor--
        }
        return streak
    }

    fun maxStreak(epochDays: Set<Int>): Int {
        var max = 0
        var cur = 0
        var prev: Int? = null
        for (d in epochDays.sorted()) {
            cur = if (prev != null && d == prev + 1) cur + 1 else 1
            max = maxOf(max, cur)
            prev = d
        }
        return max
    }

    fun weekDone(epochDays: Set<Int>, today: LocalDate): Int {
        val monday = TermClock.weekStartOf(today)
        return (0..6).count { monday.plusDays(it.toLong()).toEpochDay().toInt() in epochDays }
    }

    fun monthDone(epochDays: Set<Int>, today: LocalDate): Int {
        val first = today.withDayOfMonth(1)
        val total = today.lengthOfMonth()
        return (0 until total).count { first.plusDays(it.toLong()).toEpochDay().toInt() in epochDays }
    }

    /** 习惯卡脚注：`习惯 · 本周 5 / 7 天`（设计稿）。 */
    fun habitFoot(weeklyTarget: Int, weekDone: Int): String =
        "习惯 · 本周 $weekDone / ${weeklyTarget.coerceAtLeast(1)} 天"

    /** 习惯卡百分比：本周完成 / 本周目标。 */
    fun habitPercent(weekDone: Int, weeklyTarget: Int): Int =
        if (weeklyTarget <= 0) 0 else (weekDone * 100.0 / weeklyTarget).roundToInt().coerceIn(0, 100)

    // ---------------------------------------------------------------- 通用百分比

    /** 分步目标的百分比。 */
    fun stepPercent(done: Int, total: Int): Int =
        if (total <= 0) 0 else (done * 100.0 / total).roundToInt().coerceIn(0, 100)

    /** 「每天目标」的进度文案：`今日 10 / 30 分钟`。 */
    fun dailyFoot(dailyDone: Int, dailyTarget: Int, unit: String): String =
        if (unit == "分钟") "今日 $dailyDone / $dailyTarget 分钟" else "今日 $dailyDone / $dailyTarget $unit"

    /** 目标行副标题统一走这里，保证三种类型措辞一致（设计稿的三种「Goal Foot」）。 */
    fun goalFootText(
        type: GoalType,
        stepDone: Int,
        stepTotal: Int,
        quantityFoot: String?,
        habitFoot: String?,
    ): String = when (type) {
        GoalType.STEP -> "分步 · 已完成 $stepDone / $stepTotal 步"
        GoalType.QUANTITY -> quantityFoot.orEmpty()
        GoalType.HABIT -> habitFoot.orEmpty()
    }

    /** 专注时长的文案（与统计口径共用一份实现）。 */
    fun durationText(minutes: Int): String = FocusStats.durationText(minutes)
}

/** 习惯频率。Custom 存 ISO 星期序号集合（1=周一 … 7=周日）。 */
sealed interface HabitFrequency {
    data object Daily : HabitFrequency
    data object Weekdays : HabitFrequency
    data object Weekend : HabitFrequency
    data class Custom(val days: Set<Int>) : HabitFrequency
}

/**
 * 习惯频率的持久化。
 *
 * 复用 `items.rrule` 列存一个「本应用自己的方言」：`DAILY` / `WEEKDAYS` / `WEEKEND` /
 * `CUSTOM:1,3,5`。目标上的这份是**给界面读的**；给提醒调度器读的那份在
 * 不可见载体条目（`ItemKind.HABIT_ALARM`）上，用标准 `FREQ=DAILY` 语法。
 */
object HabitFrequencyCodec {
    fun encode(f: HabitFrequency): String = when (f) {
        HabitFrequency.Daily -> "DAILY"
        HabitFrequency.Weekdays -> "WEEKDAYS"
        HabitFrequency.Weekend -> "WEEKEND"
        is HabitFrequency.Custom -> "CUSTOM:" + f.days.sorted().joinToString(",")
    }

    fun decode(raw: String?): HabitFrequency {
        if (raw.isNullOrBlank()) return HabitFrequency.Daily
        return when {
            raw == "WEEKDAYS" -> HabitFrequency.Weekdays
            raw == "WEEKEND" -> HabitFrequency.Weekend
            raw.startsWith("CUSTOM:") -> {
                val days = raw.removePrefix("CUSTOM:")
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 1..7 }
                    .toSet()
                if (days.isEmpty()) HabitFrequency.Daily else HabitFrequency.Custom(days)
            }
            else -> HabitFrequency.Daily
        }
    }
}

/** 习惯频率的中文标签。 */
fun freqLabel(freq: HabitFrequency): String = when (freq) {
    HabitFrequency.Daily -> "每天"
    HabitFrequency.Weekdays -> "工作日"
    HabitFrequency.Weekend -> "周末"
    is HabitFrequency.Custom -> "自定义"
}

/** 习惯频率对应的活跃星期（ISO 1=周一…7=周日）。 */
fun freqActiveDays(freq: HabitFrequency): Set<Int> = when (freq) {
    HabitFrequency.Daily -> (1..7).toSet()
    HabitFrequency.Weekdays -> (1..5).toSet()
    HabitFrequency.Weekend -> setOf(6, 7)
    is HabitFrequency.Custom -> freq.days
}

/** 习惯的每周目标天数（没有单独设「周期时限」时由频率推导）。 */
fun habitWeeklyTarget(freq: HabitFrequency): Int = when (freq) {
    HabitFrequency.Daily -> 7
    HabitFrequency.Weekdays -> 5
    HabitFrequency.Weekend -> 2
    is HabitFrequency.Custom -> freq.days.size.coerceAtLeast(1)
}

// ---------------------------------------------------------------- 规划独有的控件

/**
 * 规划表单里的**内嵌单行输入框**（白底 r14、聚焦 accent 描边）。
 *
 * 为什么它还能留在规划层：它**不是一行**，而是一个输入控件 —— 公共层里
 * 对应的是 `VSearchField`（带放大镜的搜索框）与 `VFloatingInputDialog`（整屏浮层输入），
 * 两者都不是「弹窗里的一格输入」。这里用它的也只有弹窗内部（记一笔的金额/备注、
 * 添加子项的名称），整份实现十几行。
 *
 * 反面例子（**已被删掉**、不要再加回来）：把「一行设置项」再写一遍。
 * 那种情况一律用 `VSettingRow` / `VSettingSwitchRow`。
 */
@Composable
fun PlanTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    height: Dp = 48.dp,
    textStyle: TextStyle = VTypo.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(VColors.surface, RoundedCornerShape(14.dp))
            .border(1.5.dp, if (focused) VColors.accent else VColors.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && !focused) {
            VText(placeholder, VTypo.body.copy(fontSize = textStyle.fontSize), color = VColors.ink3, maxLines = if (singleLine) 1 else 2)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            textStyle = textStyle.copy(color = VColors.ink),
            singleLine = singleLine,
            cursorBrush = SolidColor(VColors.accent),
        )
    }
}

/**
 * 自定义时长弹窗（设计稿「专注时长 … 自定义」）。
 *
 * 档位格走公用的 [VDurationCell]；「步进 + 快捷档」这套交互（含下面那个
 * `StepButton`）**是这个弹窗自己的**，公共层没有对应件 —— 它是规划模块
 * 唯一保留的弹窗实现。
 */
@Composable
fun PlanDurationDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var minutes by remember { mutableStateOf(initialMinutes.coerceIn(5, 600)) }
    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("自定义时长", VTypo.dialogTitle, color = VColors.ink)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                StepButton(Lucide.Minus, enabled = minutes > 5) { minutes = (minutes - 5).coerceAtLeast(5) }
                Box(Modifier.width(120.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        VText(minutes.toString(), VTypo.numHero, color = VColors.ink)
                        VText("分钟", VTypo.caption12, color = VColors.ink3)
                    }
                }
                StepButton(Lucide.Plus, enabled = minutes < 600) { minutes = (minutes + 5).coerceAtMost(600) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 15, 25, 45, 60, 90).forEach { preset ->
                    VDurationCell(
                        label = "$preset",
                        selected = preset == minutes,
                        onClick = { minutes = preset },
                        idleBackground = VColors.surface2,
                        showBorder = false,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(VColors.surface2, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("取消", VTypo.button, color = VColors.ink2)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(VColors.accent, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) { onConfirm(minutes) },
                    contentAlignment = Alignment.Center,
                ) {
                    VText("确定", VTypo.button, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .background(if (enabled) VColors.surface2 else VColors.bg, RoundedCornerShape(13.dp))
            .vPressable(enabled = enabled, scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (enabled) VColors.ink else VColors.ink3)
    }
}
