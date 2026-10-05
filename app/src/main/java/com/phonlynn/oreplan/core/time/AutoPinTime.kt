package com.phonlynn.oreplan.core.time

/**
 * 动态置顶的「浮起时刻 / 持续时长 / 下沉时刻」三项换算（纯函数）。
 *
 * ## 为什么单独成文件
 *
 * 这三项在 UI 上是**双向联动**的（改任意一项，另两项跟着变），而联动的
 * 唯一难点是跨日边界。如果把这些换算散在 UI 代码里，每次调交互都要
 * 重新推一遍边界——所以这里先把它作为真值定义清楚并逐项单测钉死。
 *
 * ## 坐标系
 *
 * 时刻统一用「当天分钟数」`0..1439`（与 `VTimePickerDialog` 的返回口径一致）；
 * 时长用分钟数，**允许超过 1440**（跨日）。
 *
 * ## 跨日规则
 *
 * 下沉时刻不晚于浮起时刻时，视为**次日**。例如浮起 22:00、下沉 06:00，
 * 时长是 480 分钟（8 小时）而不是 −960。这样「晚上浮起、次日早晨收回」
 * 这种最常见的用法不需要额外开关。
 */
object AutoPinTime {

    /** 一天的分钟数。 */
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * 由浮起时刻与时长算出**下沉时刻的当天分钟数**（0..1439）。
     *
     * 取模是刻意的：时长可以是 3 天，但界面上的「下沉时间」只关心一天内的钟点，
     * 天数由 [sinkDayOffset] 另行表达。
     */
    fun sinkMinuteOfDay(startMinuteOfDay: Int, durationMinutes: Int): Int {
        val start = startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val dur = durationMinutes.coerceAtLeast(0)
        return (start + dur) % MINUTES_PER_DAY
    }

    /**
     * 下沉相对浮起跨了几天。
     *
     * 返回 0 表示当日（同一天内下沉），1 表示次日，以此类推。
     * 时长为 0 时返回 0。
     */
    fun sinkDayOffset(startMinuteOfDay: Int, durationMinutes: Int): Int {
        val start = startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val dur = durationMinutes.coerceAtLeast(0)
        if (dur == 0) return 0
        return (start + dur) / MINUTES_PER_DAY
    }

    /** 下沉是否落在次日或更晚（供 UI 标注「次日 HH:mm」）。 */
    fun sinkIsNextDay(startMinuteOfDay: Int, durationMinutes: Int): Boolean =
        sinkDayOffset(startMinuteOfDay, durationMinutes) >= 1

    /**
     * 由浮起与下沉时刻**反算时长**（分钟）。
     *
     * 下沉不晚于浮起时按次日处理，所以结果恒为正数：
     *  - 浮起 08:00、下沉 18:00 → 600
     *  - 浮起 22:00、下沉 06:00 → 480
     *  - 浮起 08:00、下沉 08:00 → 1440（整日）
     */
    fun durationBetween(startMinuteOfDay: Int, sinkMinuteOfDay: Int): Int {
        val start = startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val sink = sinkMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val diff = sink - start
        return if (diff > 0) diff else diff + MINUTES_PER_DAY
    }

    /**
     * 格式化下沉时刻供界面回显。
     *
     * 跨日时带「次日」前缀——**这是必须的**：只显示「06:00」会让用户
     * 以为当天下沉，与实际的次日不符。
     *
     * 跨多天时用「N 天后」表示（时长的合法上限是一年，理论上可能出现）。
     */
    fun formatSinkTime(startMinuteOfDay: Int, durationMinutes: Int): String {
        val minutes = sinkMinuteOfDay(startMinuteOfDay, durationMinutes)
        val clock = "%02d:%02d".format(minutes / 60, minutes % 60)
        return when (val days = sinkDayOffset(startMinuteOfDay, durationMinutes)) {
            0 -> clock
            1 -> "次日 $clock"
            else -> "$days 天后 $clock"
        }
    }

    /**
     * 把分钟数格式化成「1 小时 30 分钟」这类文案。
     *
     * 与 `V2Pickers.kt` 的 `durationText` 同款口径，但那份是 UI 层的
     * internal 扩展；这里独立一份是为了让换算逻辑不依赖 UI 包。
     */
    fun durationText(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m == 0 -> "0 分钟"
            m < 60 -> "$m 分钟"
            m % 60 == 0 && m < MINUTES_PER_DAY -> "${m / 60} 小时"
            m == MINUTES_PER_DAY -> "1 天"
            m % MINUTES_PER_DAY == 0 -> "${m / MINUTES_PER_DAY} 天"
            m < MINUTES_PER_DAY -> "${m / 60} 小时 ${m % 60} 分钟"
            else -> "${m / MINUTES_PER_DAY} 天 ${(m % MINUTES_PER_DAY) / 60} 小时"
        }
    }
}
