package com.phonlynn.oreplan.domain.focus

import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.QuantityLog
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 专注统计 —— **纯函数，一个地方算完**。
 *
 * 为什么单独抽出来：规划首页的「今日专注 / 本周柱状 / 连续天数」、专注记录页的热力图、
 * 目标详情里的「累计专注」，四处用的是**同一批口径**。分散着写必然出现
 * 「首页说本周 6h40m、记录页说 6h15m」这种对不上的情况，而用户第一眼就会看到它。
 *
 * 这里不碰数据库、不碰 Compose：输入是已经取出来的记录列表，输出是数字与文案。
 * 因此它可以被纯 JVM 单测钉死（见 FocusStatsTest）。
 */
object FocusStats {

    // ---------------------------------------------------------------- 单日 / 本周

    /** 某一天的专注分钟数与次数。 */
    fun daySummary(
        sessions: List<FocusSession>,
        date: LocalDate,
        zone: ZoneId,
    ): FocusDaySummary {
        val ofDay = sessions.filter { sessionDay(it, zone) == date }
        return FocusDaySummary(date, ofDay.sumOf { it.minutes }, ofDay.size)
    }

    /** 某天的总分钟数。 */
    fun minutesOn(sessions: List<FocusSession>, date: LocalDate, zone: ZoneId): Int =
        sessions.sumOf { if (sessionDay(it, zone) == date) it.minutes else 0 }

    /**
     * 本周（周一起算）的构成。
     *
     * [deltaPercent] 是本周与上周同长度区间的对比：上周只算到「与本周同样进度」的那天，
     * 否则周一早上看永远是 −80%，毫无参考价值。
     */
    data class WeekSummary(
        val weekStart: LocalDate,
        /** 周一..周日，7 个值。 */
        val dailyMinutes: List<Int>,
        val totalMinutes: Int,
        val prevTotalMinutes: Int,
    ) {
        /** 与上周对比的百分比（上周为 0 时返回 null，不显示「+∞%」）。 */
        val deltaPercent: Int?
            get() = if (prevTotalMinutes <= 0) {
                if (totalMinutes > 0) null else 0
            } else {
                ((totalMinutes - prevTotalMinutes) * 100.0 / prevTotalMinutes).toInt()
            }
    }

    fun weekSummary(sessions: List<FocusSession>, today: LocalDate, zone: ZoneId): WeekSummary {
        val weekStart = TermClock.weekStartOf(today)
        val daily = (0..6).map { offset -> minutesOn(sessions, weekStart.plusDays(offset.toLong()), zone) }
        val prevStart = weekStart.minusWeeks(1)
        // 同样的「第几天」：今天周三就只比到上周三。
        val daysIntoWeek = ChronoUnit.DAYS.between(weekStart, today).toInt().coerceIn(0, 6)
        val prevTotal = (0..daysIntoWeek).sumOf { offset ->
            minutesOn(sessions, prevStart.plusDays(offset.toLong()), zone)
        }
        return WeekSummary(
            weekStart = weekStart,
            dailyMinutes = daily,
            totalMinutes = daily.sum(),
            prevTotalMinutes = prevTotal,
        )
    }

    /**
     * 全局专注连续天数：从今天（今天还没专注就从昨天）往回数，中间断一天就停。
     * 与习惯打卡的连续口径一致（见 `PlanUi.habitStreak`）。
     */
    fun focusStreak(sessions: List<FocusSession>, today: LocalDate, zone: ZoneId): Int {
        if (sessions.isEmpty()) return 0
        val days = sessions.map { sessionDay(it, zone).toEpochDay() }.toHashSet()
        var cursor = if (days.contains(today.toEpochDay())) today.toEpochDay() else today.toEpochDay() - 1
        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor--
        }
        return streak
    }

    // ---------------------------------------------------------------- 热力图

    /** 热力图强度分档（分钟）：0 / >0 / ≥25 / ≥60 / ≥120。 */
    fun heatLevel(minutes: Int): Int = when {
        minutes <= 0 -> 0
        minutes < 25 -> 1
        minutes < 60 -> 2
        minutes < 120 -> 3
        else -> 4
    }

    /**
     * 生成热力图网格：**按周分列**（GitHub 同款）——一列一周、7 行星期。
     *
     * [weeks] 是从左到右的列数；最后一列是包含 [today] 的那一周。
     * 起点对齐到周日或周一？这里对齐**周一**：与全应用其他地方的周起点一致
     * （`TermClock.weekStartOf` 也是周一），避免同一屏里两套周口径。
     */
    fun heatmap(
        sessions: List<FocusSession>,
        today: LocalDate,
        zone: ZoneId,
        weeks: Int = 17,
    ): List<List<HeatCell>> {
        val byDay = sessions.groupBy { sessionDay(it, zone) }
        val lastWeekStart = TermClock.weekStartOf(today)
        return (0 until weeks).map { column ->
            val start = lastWeekStart.minusWeeks((weeks - 1 - column).toLong())
            (0..6).map { row ->
                val date = start.plusDays(row.toLong())
                val minutes = byDay[date]?.sumOf { it.minutes } ?: 0
                HeatCell(
                    date = date,
                    minutes = minutes,
                    level = if (date.isAfter(today)) 0 else heatLevel(minutes),
                )
            }
        }
    }

    /**
     * 打卡热力图：与 [heatmap] 同样的网格，但每格只有「做了/没做」两态。
     *
     * 习惯目标详情要的是这个（设计稿里习惯热力图只有 `$accent` 与 `#CFE4DA` 两色），
     * 而专注记录页要的是五档强度。共用网格形状、各自给颜色。
     */
    fun checkinGrid(
        doneDays: Set<Int>,
        today: LocalDate,
        weeks: Int = 17,
    ): List<List<HeatCell>> {
        val lastWeekStart = TermClock.weekStartOf(today)
        return (0 until weeks).map { column ->
            val start = lastWeekStart.minusWeeks((weeks - 1 - column).toLong())
            (0..6).map { row ->
                val date = start.plusDays(row.toLong())
                val done = date.toEpochDay().toInt() in doneDays
                HeatCell(
                    date = date,
                    minutes = if (done) 1 else 0,
                    // 未来的日子画成空白档，不画成「没打卡」
                    level = if (date.isAfter(today)) 0 else if (done) 4 else 0,
                )
            }
        }
    }

    /**
     * 热力图每列顶部的月份标签。返回 列序号 → "4 月"。
     *
     * ## 规则（用户 2026-10-02 明确的要求）
     *
     * 标签打在**该月 1 号所在的那一列**上 —— 不是"月份变化的第一列"，
     * 也不是"月初那一周的周一"。一周可能跨月（如 6/29 周一 ~ 7/5 周日），
     * 按"周一"去判断就会把 7 月的标签打到 6 月那一列上，看着就是错位一格。
     *
     * 做法：先给每列算出它的身份日期（该列第一格 = 那一周的周一），
     * 再对每个月**找到包含它 1 号的那一列**。每个月至多一个标签；
     * 1 号被完全排除在网格外（网格起点晚于它）的月份没有标签 —— 那是对的。
     *
     * ## 修过的两个 bug（都表现为"标注位置有偏移"）
     *
     * 1. 旧实现要求 `周一.dayOfMonth <= 7` 才打标签，于是**月初周一在 8 号以后
     *    的月份被整个跳过**（下个月的标签提前出现），所有标签看起来都错了一格；
     * 2. 旧实现按"周一所在月"打标签，跨月那一周会把下个月的标签打到上个月那列。
     */
    fun heatmapMonthLabels(columns: List<List<HeatCell>>): Map<Int, String> {
        if (columns.isEmpty()) return emptyMap()
        val columnStart: List<LocalDate> = columns.map { it.firstOrNull()?.date ?: return emptyMap() }
        val out = LinkedHashMap<Int, String>()
        // 网格覆盖的月份区间：从第一列到最后一列。
        var cursor = columnStart.first().withDayOfMonth(1)
        val end = columnStart.last()
        while (!cursor.isAfter(end)) {
            val firstOfMonth = cursor
            // 1 号落在哪一列：最后一列满足"这一列的周一不晚于 1 号"。
            val index = columnStart.indexOfLast { !it.isAfter(firstOfMonth) }
            // 只有这一列真的覆盖到 1 号（1 号 < 下一列的周一）才算它是"本月第一列"。
            val covers = index >= 0 && (index == columnStart.lastIndex || columnStart[index + 1].isAfter(firstOfMonth))
            if (covers) out[index] = "${firstOfMonth.monthValue} 月"
            cursor = cursor.plusMonths(1)
        }
        return out
    }

    /**
     * 热力图**当前滑到哪一年** —— 返回视口里出现过的年份，按时间顺序、已去重。
     *
     * 为什么需要它（用户 2026-10-02）：热力图跨一整年（52 周），右上角那个年份
     * 不能写死今年 —— 往左拖到去年那一段时标签必须是去年。
     *
     * 口径：**视口范围内**（左端列 → 右端列）所有列里出现过的年份。
     * 跨年那一段会同时出现两个（如 `2025 年` `2026 年`）—— 那几周里
     * 「当前是哪一年」本来就没有唯一答案，只给一个是半真半假的结论。
     *
     * 抽成纯函数（而不是写在 Composable 里）的理由：这段换算里全是**边界与取整**，
     * 而它错了只会表现为「标签偶尔不对」—— 那种 bug 在真机上极难定位。
     * 这里不碰 Compose、不读时钟，因此可以被 FocusStatsTest 直接钉住。
     *
     * @param columns 热力图列（每列 7 格，`.first()` 是那一周的周一）。
     * @param scrollX 横向滚动偏移（px）。
     * @param viewportWidth 可视宽度（px）；<= 0 时按「只看一列」处理。
     * @param columnPitch px 单位的列间距（格宽 + 列间 gap）；<= 0 时退化成只看一列。
     * @param contentOverhang 内容相对滚动容器的**两端内衬**（px，默认 0）。
     *   `FocusHeatmap` 为了不裁掉选中框，自己在两端留了 `EdgeRoom` ——
     *   那部分属于内衬，不是"能看见列的地方"，所以从滚动偏移里要先减掉。
     *   不减的话，滚动位置会被算成多出一列（只在跨年那几周看得出来，但它是错的）。
     */
    fun yearsInViewport(
        columns: List<List<HeatCell>>,
        scrollX: Float,
        viewportWidth: Float,
        columnPitch: Float,
        contentOverhang: Float = 0f,
    ): List<Int> {
        if (columns.isEmpty()) return emptyList()
        val lastIndex = columns.lastIndex
        if (columnPitch <= 0f) {
            return listOfNotNull(columns.first().firstOrNull()?.date?.year)
        }
        // 左端内衬之后的那一点才是第一列真正的起点。
        val contentX = (scrollX - contentOverhang).coerceAtLeast(0f)
        val first = (contentX / columnPitch).toInt().coerceIn(0, lastIndex)
        val span = if (viewportWidth > 0f) viewportWidth else columnPitch
        val last = ((contentX + span) / columnPitch).toInt().coerceIn(first, lastIndex)
        return columns.subList(first, last + 1)
            .mapNotNull { column -> column.firstOrNull()?.date?.year }
            .distinct()
            .sorted()
    }

    // ---------------------------------------------------------------- 目标维度

    /** 某个目标上的累计专注分钟。 */
    fun minutesForGoal(sessions: List<FocusSession>, goalId: String): Int =
        sessions.sumOf { if (it.itemId == goalId) it.minutes else 0 }

    /**
     * 数量目标的「近 N 周累计」折线柱：每格是该周末的累计值。
     *
     * 用**累计值**而不是「本周新增」：设计稿标注「近 8 周 +7」，
     * 那个 +7 是最后一周的增量，柱高是累计——两者必须一起给出才读得懂趋势。
     */
    data class QuantityTrend(
        /** 每格的柱高（累计值）。 */
        val cumulative: List<Long>,
        /** 最后一格相对上一格的增量。 */
        val lastDelta: Long,
        /** 每格的短标签：`W1`…`W7` + `本周`。 */
        val labels: List<String>,
    )

    fun quantityTrend(
        logs: List<QuantityLog>,
        today: LocalDate,
        zone: ZoneId,
        weeks: Int = 8,
    ): QuantityTrend {
        val lastWeekStart = TermClock.weekStartOf(today)
        val buckets = (0 until weeks).map { index ->
            val start = lastWeekStart.minusWeeks((weeks - 1 - index).toLong())
            val end = start.plusDays(6)
            logs.filter { log ->
                val day = log.at.atZone(zone).toLocalDate()
                !day.isBefore(start) && !day.isAfter(end)
            }.sumOf { it.amount }
        }
        var running = 0L
        val cumulative = buckets.map { running += it; running }
        val labels = (0 until weeks).map { index ->
            if (index == weeks - 1) "本周" else "W${index + 1}"
        }
        return QuantityTrend(
            cumulative = cumulative,
            lastDelta = buckets.lastOrNull() ?: 0L,
            labels = labels,
        )
    }

    // ---------------------------------------------------------------- 文案

    /** 设计稿的时长文案：`1h 20m` / `45m` / `0m`。 */
    fun durationText(minutes: Int): String {
        if (minutes <= 0) return "0m"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** 秒数文案：`06:36` / `1:02:15`。 */
    fun clockText(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, sec)
        } else {
            "%02d:%02d".format(m, sec)
        }
    }

    /** `14:05 – 14:30`。 */
    fun rangeText(session: FocusSession, zone: ZoneId): String {
        val s = session.startedAt.atZone(zone)
        val e = session.endedAt.atZone(zone)
        return "%02d:%02d – %02d:%02d".format(s.hour, s.minute, e.hour, e.minute)
    }

    /** 记录页某天的标题：`5月12日 · 周三`。 */
    fun dayTitle(date: LocalDate): String =
        "${date.monthValue}月${date.dayOfMonth}日 · ${TermClock.weekdayLabel(date.dayOfWeek)}"

    private fun sessionDay(session: FocusSession, zone: ZoneId): LocalDate =
        session.startedAt.atZone(zone).toLocalDate()
}
