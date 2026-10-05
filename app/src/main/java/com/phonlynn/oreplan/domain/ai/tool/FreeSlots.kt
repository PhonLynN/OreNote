package com.phonlynn.oreplan.domain.ai.tool

/**
 * 从「已占用的时间段」里算出空闲时段。
 *
 * ## 为什么是纯函数
 *
 * 这是 AI 回答"我什么时候有空"的**唯一依据**，算错不会报错、只会给错建议。
 * 抽成纯函数就能用单测把边界钉死（相邻、包含、跨天窗口、刚好等于最小时长…），
 * 而不用去真机上试。
 *
 * ## 区间约定：**半开** `[start, end)`
 *
 * 分钟数从零点算起。[AgendaEntry][com.phonlynn.oreplan.domain.model.AgendaEntry]
 * 的 `startMinute` / `endMinute` 直接就用这个单位，不用换算。
 *
 * 半开而不是闭区间，是因为 `09:00–10:00` 和 `10:00–11:00` 在现实中**首尾相接、
 * 中间没有空档**；闭区间会算出"10:00 到 10:00 有一段空闲"这种零长度结果。
 *（用 `IntRange` 表达时，`a until b` 就是 `a..b-1`，即半开。）
 *
 * @param occupied 已占用的时段，**不必有序、允许重叠**
 * @param dayFrom 窗口起点（含）
 * @param dayTo 窗口终点（**不含**）
 * @param minMinutes 短于它的空档直接丢弃 —— 15 分钟的缝不值得建议给用户
 */
internal fun freeSlots(
    occupied: List<IntRange>,
    dayFrom: Int,
    dayTo: Int,
    minMinutes: Int,
): List<IntRange> {
    if (dayTo - dayFrom < minMinutes) return emptyList()

    // 裁到窗口内；完全在窗口外的丢掉
    val clipped = occupied
        .map { maxOf(it.first, dayFrom)..minOf(it.last, dayTo - 1) }
        .filter { it.first <= it.last }
        .sortedBy { it.first }

    // 合并重叠与相接的（`r.first <= last.last + 1` 把首尾相接也算成一整块）
    val merged = ArrayList<IntRange>()
    for (r in clipped) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
        } else {
            merged += r
        }
    }

    // 取补集
    val free = ArrayList<IntRange>()
    var cursor = dayFrom
    for (r in merged) {
        if (r.first - cursor >= minMinutes) free += cursor..(r.first - 1)
        cursor = maxOf(cursor, r.last + 1)
    }
    if (dayTo - cursor >= minMinutes) free += cursor..(dayTo - 1)
    return free
}

/** 分钟数 → `HH:mm`。 */
internal fun minuteLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** 分钟数 → `9 小时 30 分` / `45 分钟`。 */
internal fun durationLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m 分钟"
        m == 0 -> "$h 小时"
        else -> "$h 小时 $m 分"
    }
}
