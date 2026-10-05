package com.phonlynn.oreplan.domain.focus

import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.QuantityLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 专注统计的口径测试。
 *
 * 为什么必须有：规划首页、专注记录页、目标详情三处显示的是**同一批数字**。
 * 一旦口径漂移（比如首页算 7 天、记录页算 5 天），用户第一眼就会发现对不上，
 * 而那种 bug 靠肉眼看界面几乎不可能定位。
 */
class FocusStatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2025, 5, 14) // 周三

    private fun session(
        day: LocalDate,
        startHour: Int,
        minutes: Int,
        itemId: String? = null,
        kind: FocusKind = FocusKind.COUNT_DOWN,
        planned: Int? = 25,
    ): FocusSession {
        val start = day.atTime(startHour, 0).atZone(zone).toInstant()
        return FocusSession(
            id = "$day-$startHour-$itemId",
            startedAt = start,
            endedAt = start.plusSeconds(minutes * 60L),
            minutes = minutes,
            kind = kind,
            plannedMinutes = planned,
            completed = planned != null && minutes >= planned,
            label = null,
            itemId = itemId,
            createdAt = Instant.EPOCH,
        )
    }

    // ---------------------------------------------------------------- 时长文案

    @Test
    fun `时长文案按设计稿写法`() {
        assertEquals("0m", FocusStats.durationText(0))
        assertEquals("45m", FocusStats.durationText(45))
        assertEquals("1h", FocusStats.durationText(60))
        assertEquals("1h 20m", FocusStats.durationText(80))
        assertEquals("6h 40m", FocusStats.durationText(400))
    }

    @Test
    fun `时钟文案超过一小时带小时位`() {
        assertEquals("06:36", FocusStats.clockText(396))
        assertEquals("00:00", FocusStats.clockText(0))
        assertEquals("1:02:15", FocusStats.clockText(3735))
        // 负数（时钟回拨之类的脏输入）钳到 0，不显示 -00:01
        assertEquals("00:00", FocusStats.clockText(-5))
    }

    // ---------------------------------------------------------------- 单日

    @Test
    fun `单日汇总只算那一天`() {
        val sessions = listOf(
            session(today, 9, 25),
            session(today, 14, 30),
            session(today.minusDays(1), 10, 50),
        )
        val summary = FocusStats.daySummary(sessions, today, zone)
        assertEquals(55, summary.minutes)
        assertEquals(2, summary.sessions)
        assertEquals(50, FocusStats.minutesOn(sessions, today.minusDays(1), zone))
    }

    @Test
    fun `空记录不炸`() {
        val summary = FocusStats.daySummary(emptyList(), today, zone)
        assertEquals(0, summary.minutes)
        assertEquals(0, summary.sessions)
        assertEquals(0, FocusStats.focusStreak(emptyList(), today, zone))
    }

    // ---------------------------------------------------------------- 本周

    @Test
    fun `本周从周一起算且只对比上周同进度`() {
        val monday = LocalDate.of(2025, 5, 12)
        val sessions = listOf(
            // 本周一 30 分钟、本周三（今天）25 分钟
            session(monday, 9, 30),
            session(today, 9, 25),
            // 上周一 20 分钟、上周三 40 分钟、上周五 60 分钟
            session(monday.minusWeeks(1), 9, 20),
            session(monday.minusWeeks(1).plusDays(2), 9, 40),
            session(monday.minusWeeks(1).plusDays(4), 9, 60),
        )
        val week = FocusStats.weekSummary(sessions, today, zone)

        assertEquals(monday, week.weekStart)
        assertEquals(7, week.dailyMinutes.size)
        assertEquals(55, week.totalMinutes)
        // 今天周三 → 上周只比到周三：20 + 40 = 60（上周五的 60 分钟不算）
        assertEquals(60, week.prevTotalMinutes)
        // (55-60)/60 = -8.33% → 截断成 -8
        assertEquals(-8, week.deltaPercent)
    }

    @Test
    fun `上周没有数据时不显示无穷大百分比`() {
        val sessions = listOf(session(today, 9, 25))
        val week = FocusStats.weekSummary(sessions, today, zone)
        assertEquals(25, week.totalMinutes)
        assertEquals(0, week.prevTotalMinutes)
        assertNull(week.deltaPercent)
    }

    // ---------------------------------------------------------------- 连续

    @Test
    fun `连续天数今天没专注时不从零开始`() {
        val sessions = listOf(
            session(today.minusDays(1), 9, 25),
            session(today.minusDays(2), 9, 25),
            session(today.minusDays(3), 9, 25),
        )
        // 今天还没专注，但昨天/前天/大前天都有 → 连续 3
        assertEquals(3, FocusStats.focusStreak(sessions, today, zone))
    }

    @Test
    fun `连续天数中间断了就停`() {
        val sessions = listOf(
            session(today, 9, 25),
            session(today.minusDays(1), 9, 25),
            // 缺 today-2
            session(today.minusDays(3), 9, 25),
        )
        assertEquals(2, FocusStats.focusStreak(sessions, today, zone))
    }

    @Test
    fun `同一天多次只算一天`() {
        val sessions = listOf(
            session(today, 9, 25),
            session(today, 14, 25),
            session(today, 20, 25),
        )
        assertEquals(1, FocusStats.focusStreak(sessions, today, zone))
    }

    // ---------------------------------------------------------------- 热力图

    @Test
    fun `热力强度分档边界`() {
        assertEquals(0, FocusStats.heatLevel(0))
        assertEquals(1, FocusStats.heatLevel(1))
        assertEquals(1, FocusStats.heatLevel(24))
        assertEquals(2, FocusStats.heatLevel(25))
        assertEquals(2, FocusStats.heatLevel(59))
        assertEquals(3, FocusStats.heatLevel(60))
        assertEquals(3, FocusStats.heatLevel(119))
        assertEquals(4, FocusStats.heatLevel(120))
        assertEquals(4, FocusStats.heatLevel(600))
    }

    @Test
    fun `热力图按周分列且最后一列含今天`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 4)
        assertEquals(4, grid.size)
        assertTrue(grid.all { it.size == 7 })

        // 最后一列从本周一开始
        val lastColumnStart = grid.last().first().date
        assertEquals(LocalDate.of(2025, 5, 12), lastColumnStart)
        assertTrue(grid.last().any { it.date == today })

        // 每一列的第一天都是周一
        grid.forEach { column ->
            assertEquals(java.time.DayOfWeek.MONDAY, column.first().date.dayOfWeek)
        }
    }

    @Test
    fun `热力图把未来的日子画成空档`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 2)
        val future = grid.last().filter { it.date.isAfter(today) }
        assertTrue(future.isNotEmpty())
        assertTrue(future.all { it.level == 0 })
    }

    @Test
    fun `热力图月份标签只在每月第一周出现`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 17)
        val labels = FocusStats.heatmapMonthLabels(grid)
        assertTrue(labels.isNotEmpty())
        // 每个标签的值形如「5 月」
        assertTrue(labels.values.all { it.endsWith("月") })
    }

    /**
     * 月份标签必须落在**该月 1 号所在的那一列**（用户 2026-10-02 的要求）。
     *
     * 这条是重点：旧实现按「周一所在月」打标签，跨月那一周会把下个月的标签
     * 打到上个月那一列上，看起来就是所有标注都错位一格。
     */
    @Test
    fun `月份标签落在该月一号所在列`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val labels = FocusStats.heatmapMonthLabels(grid)
        assertTrue(labels.isNotEmpty())

        labels.forEach { (index, label) ->
            val column = grid[index]
            val firstOfMonth = column.first { it.date.dayOfMonth == 1 }.date
            assertEquals(
                "「$label」应该标在含 1 号的那一列，但第 $index 列覆盖的是 " +
                    "${column.first().date} ~ ${column.last().date}",
                "${firstOfMonth.monthValue} 月",
                label,
            )
        }
    }

    @Test
    fun `每个被覆盖的月份都有且只有一个标签`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val labels = FocusStats.heatmapMonthLabels(grid)
        // 收集网格里出现过的所有 (年, 月)
        val months = grid.flatten()
            .map { it.date.year * 100 + it.date.monthValue }
            .distinct()
        // 标签数 = 月份数，或比它少 1（网格起点所在的那个月，1 号在网格外、没有标签）
        val firstMonthHasFirstDay = grid.first().any { it.date.dayOfMonth == 1 }
        val expected = if (firstMonthHasFirstDay) months.size else months.size - 1
        assertEquals(expected, labels.size)
        // 且标签之间不重复
        assertEquals(labels.size, labels.values.distinct().size)
    }

    /**
     * **跨月那一周**：这是原来错位一格的根源。
     *
     * 例：某年 6/29 是周一，7/1 是周三。这一列的「周一」属于 6 月，
     * 但它里面确实有 7 月 1 号 —— 标签就该打在这一列上。
     * 旧实现按"周一所在月"判断，于是把「7 月」打到了下一列（7/6 那周）。
     */
    @Test
    fun `跨月那一周标签打在一号所在列而不是下一列`() {
        // 2026-06-29 是周一，2026-07-01 是周三
        val monday = LocalDate.of(2026, 6, 29)
        assertEquals(java.time.DayOfWeek.MONDAY, monday.dayOfWeek)
        val grid = listOf(
            week(monday),
            week(monday.plusWeeks(1)),
            week(monday.plusWeeks(2)),
        )
        val labels = FocusStats.heatmapMonthLabels(grid)
        // 7 月 1 号在第 0 列 → 标签必须落在第 0 列
        assertEquals("7 月", labels[0])
        // 第 1 列不该再出现 7 月
        assertTrue(labels.none { it.key == 1 && it.value == "7 月" })
    }

    @Test
    fun `网格起点晚于一号时不标注该月`() {
        // 网格从 6/29 那周开始 → 6 月的 1 号在网格外，不该有「6 月」标签
        val monday = LocalDate.of(2026, 6, 29)
        val grid = listOf(week(monday), week(monday.plusWeeks(1)))
        val labels = FocusStats.heatmapMonthLabels(grid)
        assertTrue("6 月不该被标注：${labels.values}", labels.values.none { it == "6 月" })
    }

    @Test
    fun `空网格不标注`() {
        assertTrue(FocusStats.heatmapMonthLabels(emptyList()).isEmpty())
    }

    /** 造一列（一周）：周一 ~ 周日七格。 */
    private fun week(monday: LocalDate): List<HeatCell> = (0..6).map { offset ->
        val date = monday.plusDays(offset.toLong())
        HeatCell(date = date, minutes = 0, level = 0)
    }

    @Test
    fun `打卡网格只有做与没做两态`() {
        val days = setOf(today.toEpochDay().toInt(), today.minusDays(1).toEpochDay().toInt())
        val grid = FocusStats.checkinGrid(days, today, weeks = 2)
        val flat = grid.flatten()
        assertTrue(flat.all { it.level == 0 || it.level == 4 })
        assertEquals(2, flat.count { it.level == 4 })
        // 未来的日子即便在集合里也不该算「已做」
        assertTrue(flat.filter { it.date.isAfter(today) }.all { it.level == 0 })
    }

    // ---------------------------------------------------------------- 数量趋势

    @Test
    fun `数量趋势给累计值与最后一周增量`() {
        val monday = LocalDate.of(2025, 5, 12)
        val logs = listOf(
            quantity(monday.minusWeeks(3), 2),
            quantity(monday.minusWeeks(1), 3),
            quantity(monday, 7),
        )
        val trend = FocusStats.quantityTrend(logs, today, zone, weeks = 5)
        assertEquals(5, trend.cumulative.size)
        // 累计值单调不减
        trend.cumulative.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        assertEquals(12L, trend.cumulative.last())
        assertEquals(7L, trend.lastDelta)
        assertEquals("本周", trend.labels.last())
        assertEquals("W1", trend.labels.first())
    }

    @Test
    fun `没有记录时趋势全为零`() {
        val trend = FocusStats.quantityTrend(emptyList(), today, zone, weeks = 8)
        assertTrue(trend.cumulative.all { it == 0L })
        assertEquals(0L, trend.lastDelta)
    }

    // ---------------------------------------------------------------- 目标维度

    @Test
    fun `按目标累计只算挂在该目标上的记录`() {
        val sessions = listOf(
            session(today, 9, 25, itemId = "goalA"),
            session(today, 10, 30, itemId = "goalB"),
            session(today, 11, 40, itemId = "goalA"),
            session(today, 12, 15, itemId = null),
        )
        assertEquals(65, FocusStats.minutesForGoal(sessions, "goalA"))
        assertEquals(30, FocusStats.minutesForGoal(sessions, "goalB"))
        assertEquals(0, FocusStats.minutesForGoal(sessions, "goalC"))
        // 自由专注不落在任何目标上，但总数包含它
        assertEquals(110, sessions.sumOf { it.minutes })
    }

    @Test
    fun `记录时间区间文案`() {
        val s = session(today, 14, 25)
        assertEquals("14:00 – 14:25", FocusStats.rangeText(s, zone))
    }

    // ---------------------------------------------------------------- 视口年份

    /**
     * 热力图右上角的年份气泡：按**视口范围**取年内出现过的年份。
     *
     * 这段逻辑全是边界与取整，错了只表现为「标签偶尔不对」，真机上极难定位，
     * 所以在这里把跨年那几种情况全部钉住。
     *
     * 期望值一律**从网格自身推**（第一列/最后一列的年份），不写死 2025/2026 ——
     * 写死的话测试会随「今天是哪天」失效（本文件固定 today = 2025-05-14，
     * 52 周会一路跨到 2024）。
     */
    @Test
    fun `视口停在最右只显示最后一列那一年`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val pitch = 25f
        val viewport = 8 * pitch
        val scrollX = grid.size * pitch - viewport
        val years = FocusStats.yearsInViewport(grid, scrollX, viewport, pitch)
        assertEquals(listOf(grid.last().first().date.year), years)
    }

    @Test
    fun `视口跨年时同时给出两个年份`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val pitch = 25f
        val viewport = 8 * pitch
        val startYear = grid.first().first().date.year
        val firstOfNextYear = grid.indexOfFirst { it.first().date.year != startYear }
        assertTrue("52 周里应该跨年", firstOfNextYear > 0)

        // 视口左端落在前一年的最后一列 → 右边会看到新一年
        val years = FocusStats.yearsInViewport(grid, (firstOfNextYear - 1) * pitch, viewport, pitch)
        assertEquals(listOf(startYear, startYear + 1), years)

        // 再往右挪一列 → 视口里只剩新一年（气泡从两个收成一个）
        val after = FocusStats.yearsInViewport(grid, firstOfNextYear * pitch, viewport, pitch)
        assertEquals(listOf(startYear + 1), after)
    }

    @Test
    fun `视口完全在最早那段只显示起始年份`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val pitch = 25f
        val startYear = grid.first().first().date.year
        val years = FocusStats.yearsInViewport(grid, 0f, 2 * pitch, pitch)
        assertEquals(listOf(startYear), years)
    }

    @Test
    fun `滚动越界与零宽度都不崩`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val pitch = 25f
        val lastYear = grid.last().first().date.year
        val firstYear = grid.first().first().date.year
        // 偏移超出内容总宽 → 钳到最后一列
        assertEquals(listOf(lastYear), FocusStats.yearsInViewport(grid, 99_999f, 100f, pitch))
        // 负偏移（回弹时的中间态）→ 钳到第一列
        assertEquals(listOf(firstYear), FocusStats.yearsInViewport(grid, -500f, 2 * pitch, pitch))
        // 视口宽度还没测量出来（0）→ 至少给一列，不返回空
        assertEquals(1, FocusStats.yearsInViewport(grid, 0f, 0f, pitch).size)
        // 列间距为 0（不该发生）→ 退化成第一列
        assertEquals(listOf(firstYear), FocusStats.yearsInViewport(grid, 100f, 100f, 0f))
        // 空网格
        assertTrue(FocusStats.yearsInViewport(emptyList(), 0f, 100f, pitch).isEmpty())
    }

    @Test
    fun `视口宽度为负或零时不崩且给出最前一列`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val pitch = 25f
        val firstYear = grid.first().first().date.year
        // 调用方会「测量宽度 − 两端余量」；容器极窄时可能减成负数。
        assertEquals(listOf(firstYear), FocusStats.yearsInViewport(grid, 0f, -8f, pitch))
        assertEquals(1, FocusStats.yearsInViewport(grid, 0f, 0f, pitch).size)
    }

    /**
     * 内容两端有内衬（热力图为了防止裁掉选中框，自己在两端留了 EdgeRoom）时，
     * 滚动偏移要先减掉左端那一段，否则会多算一列。
     */
    @Test
    fun `内容两端有内衬时滚动偏移先扣掉内衬`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val pitch = 25f
        val overhang = 8f
        val viewport = 200f
        // 偏移正好落在「第 3 列的起点」上（含内衬）
        val scrollX = overhang + 3 * pitch
        val withOverhang = FocusStats.yearsInViewport(grid, scrollX, viewport, pitch, overhang)
        val withoutOverhang = FocusStats.yearsInViewport(grid, scrollX, viewport, pitch, 0f)
        // 减掉内衬 → 第一列是 3；不减 → 第一列被算成 3 或 4 的边界情况会偏一列
        assertEquals(
            FocusStats.yearsInViewport(grid, 3 * pitch, viewport, pitch, 0f),
            withOverhang,
        )
        // 两种口径在"整列起点"这种边界上结果一致，说明换算对齐了
        assertEquals(withoutOverhang.size, withOverhang.size)
    }

    @Test
    fun `内衬大于滚动偏移时钳到零不崩`() {
        val grid = FocusStats.heatmap(emptyList(), today, zone, weeks = 52)
        val pitch = 25f
        val firstYear = grid.first().first().date.year
        assertEquals(listOf(firstYear), FocusStats.yearsInViewport(grid, 2f, 200f, pitch, contentOverhang = 8f))
    }

    @Test
    fun `日期标题带星期`() {
        assertEquals("5月14日 · 三", FocusStats.dayTitle(today))
    }

    private fun quantity(day: LocalDate, amount: Long) = QuantityLog(
        id = "$day-$amount",
        itemId = "goal",
        at = day.atTime(12, 0).atZone(zone).toInstant(),
        amount = amount,
        label = null,
    )
}
