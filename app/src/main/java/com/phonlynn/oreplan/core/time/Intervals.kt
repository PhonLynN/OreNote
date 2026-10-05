package com.phonlynn.oreplan.core.time

import java.time.LocalTime

/**
 * 一天内的分钟区间，**左闭右开**：`[startMinute, endMinute)`。
 *
 * 用半开区间是有原因的：一个会 10:00 结束、另一个 10:00 开始，这是**不重叠**的。
 * 如果按闭区间判断，时间轴上这两个块会被算成重叠并被迫并排摆放，白白浪费横向空间。
 */
data class Interval(
    val startMinute: Int,
    val endMinute: Int,
) {
    init {
        require(endMinute >= startMinute) { "结束分钟不能小于开始分钟：$startMinute..$endMinute" }
    }

    val durationMinutes: Int get() = endMinute - startMinute

    /**
     * 相交判断。
     *
     * 用 `max(起点) < min(终点)` 而不是常见的 `a.start < b.end && b.start < a.end`：
     * 后者对**零长度区间**会误判成重叠。零长度会在「用户只填了开始时间、还没填结束时间」
     * 时出现，它不应该在时间轴上挤占别人的横向空间。
     */
    fun overlaps(other: Interval): Boolean =
        maxOf(startMinute, other.startMinute) < minOf(endMinute, other.endMinute)

    companion object {
        fun of(start: LocalTime, end: LocalTime): Interval = Interval(
            startMinute = start.hour * 60 + start.minute,
            endMinute = end.hour * 60 + end.minute,
        )
    }
}

object Intervals {

    /** 一个区间在并排布局中占的列。 */
    data class ColumnSpan(val index: Int, val column: Int, val columns: Int)

    /**
     * 给定一批区间，算出每个区间应占的列序号与该组总列数。
     *
     * **按下标返回而不是按区间值返回**：两个日程的时间完全相同时，按值会得到相同的 key，
     * 被当成同一个而叠在一起；按下标则各占一列。
     */
    fun layout(items: List<Interval>): List<ColumnSpan> {
        if (items.isEmpty()) return emptyList()

        val order = items.indices.sortedWith(
            compareBy({ items[it].startMinute }, { items[it].endMinute }, { it }),
        )
        val spans = MutableList(items.size) { ColumnSpan(it, 0, 1) }

        var cursor = 0
        while (cursor < order.size) {
            // 拉出连通的一组：下一个区间的开始时间早于当前组内的最大结束时间就连通
            var end = cursor
            var maxEnd = items[order[cursor]].endMinute
            while (end + 1 < order.size && items[order[end + 1]].startMinute < maxEnd) {
                end++
                maxEnd = maxOf(maxEnd, items[order[end]].endMinute)
            }

            val columnEnds = mutableListOf<Int>()
            for (position in cursor..end) {
                val index = order[position]
                val start = items[index].startMinute
                val reusable = columnEnds.indexOfFirst { it <= start }
                val column = if (reusable >= 0) {
                    columnEnds[reusable] = items[index].endMinute
                    reusable
                } else {
                    columnEnds += items[index].endMinute
                    columnEnds.lastIndex
                }
                spans[index] = ColumnSpan(index, column, 1)
            }

            val total = columnEnds.size.coerceAtLeast(1)
            for (position in cursor..end) {
                val index = order[position]
                spans[index] = spans[index].copy(columns = total)
            }
            cursor = end + 1
        }
        return spans
    }

    fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    fun toLocalTime(minuteOfDay: Int): LocalTime {
        require(minuteOfDay in 0..24 * 60) { "minuteOfDay 超出一天：$minuteOfDay" }
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    }

    /**
     * 把一天内的多个区间按重叠关系分组，供时间轴把重叠的日程并排摆放。
     *
     * 用扫描线：按开始时间排序，只要下一个区间的开始时间早于「当前组内的最大结束时间」，
     * 它就与当前组连通，归入同一组；否则另起一组。
     *
     * 返回的每组内部保证两两连通，组与组之间不重叠，组内按开始时间升序。
     */
    fun groupOverlapping(intervals: List<Interval>): List<List<Interval>> {
        if (intervals.isEmpty()) return emptyList()

        val sorted = intervals.sortedWith(compareBy({ it.startMinute }, { it.endMinute }))
        val groups = mutableListOf<MutableList<Interval>>()
        var current = mutableListOf(sorted.first())
        var currentMaxEnd = sorted.first().endMinute

        for (interval in sorted.drop(1)) {
            if (interval.startMinute < currentMaxEnd) {
                current += interval
                currentMaxEnd = maxOf(currentMaxEnd, interval.endMinute)
            } else {
                groups += current
                current = mutableListOf(interval)
                currentMaxEnd = interval.endMinute
            }
        }
        groups += current
        return groups
    }

    /**
     * 组内每个区间需要占的列数与自己的列序号，用于并排摆放。
     * 返回 `区间 -> (列序号, 总列数)`。
     *
     * 贪心：每个区间放进第一个「最后一个区间已结束」的列；否则新开一列。
     */
    fun assignColumns(group: List<Interval>): Map<Interval, Pair<Int, Int>> {
        val columnEnds = mutableListOf<Int>()
        val assignment = LinkedHashMap<Interval, Int>()

        for (interval in group.sortedWith(compareBy({ it.startMinute }, { it.endMinute }))) {
            val reusable = columnEnds.indexOfFirst { it <= interval.startMinute }
            if (reusable >= 0) {
                columnEnds[reusable] = interval.endMinute
                assignment[interval] = reusable
            } else {
                columnEnds += interval.endMinute
                assignment[interval] = columnEnds.lastIndex
            }
        }

        val total = columnEnds.size.coerceAtLeast(1)
        return assignment.mapValues { (_, column) -> column to total }
    }
}
