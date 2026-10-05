package com.phonlynn.oreplan.domain.routine

/**
 * 作息时间配置 —— 决定「第 N 节几点开始」。
 *
 * 课表与今日页的节次标签都从这里推导，**不落库成每节一行**：
 * 改一次时长/数量就整表重算，不存的话永远一致；存了就要跑一次批量更新。
 * 由 [RoutineSchedule.compute] 纯函数算出一张节次表，可单测。
 */
data class RoutineConfig(
    /** 第一节开始时间（一天中的分钟数），默认 08:00。 */
    val startMinute: Int = 8 * 60,
    /** 单节课时长（分钟）。 */
    val periodMinutes: Int = 45,
    /** 小课间（分钟）。 */
    val smallBreakMinutes: Int = 5,
    /** 午休时长（分钟），在上午最后一节之后。 */
    val lunchBreakMinutes: Int = 75,
    /** 晚休时长（分钟），在下午最后一节之后。 */
    val dinnerBreakMinutes: Int = 45,
    val morningCount: Int = 5,
    val afternoonCount: Int = 5,
    val eveningCount: Int = 4,
    /** 大课间：在第 N 节之后额外休息的时长。 */
    val bigBreaks: List<BigBreak> = DEFAULT_BIG_BREAKS,
) {
    val totalPeriods: Int get() = morningCount + afternoonCount + eveningCount

    companion object {
        val DEFAULT_BIG_BREAKS = listOf(BigBreak(afterPeriod = 2, minutes = 15))

        val Default = RoutineConfig()
    }
}

data class BigBreak(
    /** 在第几节之后（1 起）。 */
    val afterPeriod: Int,
    val minutes: Int,
)

/** 一节课的起止时间（分钟数）。 */
data class PeriodTime(
    /** 第几节（1 起）。 */
    val period: Int,
    val startMinute: Int,
    val endMinute: Int,
) {
    /** 该节之后的课间时长。 */
    var breakAfter: Int = 0
        internal set
}

object RoutineSchedule {

    /**
     * 计算全部节次的起止时间。
     *
     * 规则：每节 [RoutineConfig.periodMinutes]；课后休息按优先级取
     * 大课间 → 午休 → 晚休 → 小课间。上午最后一节后是午休，
     * 下午最后一节后是晚休。
     */
    fun compute(config: RoutineConfig): List<PeriodTime> {
        val periods = mutableListOf<PeriodTime>()
        var cursor = config.startMinute
        val total = config.totalPeriods
        if (total <= 0) return periods
        val morningEnd = config.morningCount
        val afternoonEnd = config.morningCount + config.afternoonCount

        for (p in 1..total) {
            val start = cursor
            val end = start + config.periodMinutes
            val period = PeriodTime(period = p, startMinute = start, endMinute = end)
            periods += period

            val rest = when {
                p == total -> 0
                else -> {
                    val big = config.bigBreaks.firstOrNull { it.afterPeriod == p }?.minutes
                    when {
                        big != null -> big
                        p == morningEnd -> config.lunchBreakMinutes
                        p == afternoonEnd -> config.dinnerBreakMinutes
                        else -> config.smallBreakMinutes
                    }
                }
            }
            period.breakAfter = rest
            cursor = end + rest
        }
        return periods
    }

    /** 第 [period] 节的开始分钟数；越界时返回 null。 */
    fun startOf(config: RoutineConfig, period: Int): Int? =
        compute(config).firstOrNull { it.period == period }?.startMinute

    /**
     * 把一节当前分钟数换算成节次（1 起）：分钟数落在第 N 节的 [start, end) 内返回 N；
     * 落在课间里返回它后面那一节（正在等待下一节上课）；全部之后返回 null。用于时间轴标签。
     */
    fun periodAt(config: RoutineConfig, minuteOfDay: Int): Int? {
        val list = compute(config)
        list.forEach { p ->
            if (minuteOfDay >= p.startMinute && minuteOfDay < p.endMinute) return p.period
        }
        list.forEach { p ->
            if (minuteOfDay >= p.endMinute && minuteOfDay < p.endMinute + p.breakAfter) return p.period + 1
        }
        return null
    }

    /**
     * 找到一节「第几节」对应的显示文本（用于课程块/今日 hero）：
     * 输入课程开始的分钟数，输出形如 "第 3-4 节"（若连排）或 "第 3 节"。
     * [endMinute] 用于判断连排。找不到时返回 null。
     */
    fun periodLabel(config: RoutineConfig, startMinute: Int, endMinute: Int): String? {
        val list = compute(config)
        val startPeriod = list.firstOrNull { startMinute >= it.startMinute - 1 && startMinute < it.endMinute + it.breakAfter + 1 }?.period
            ?: return null
        // 结束时刻落在哪一节的区间内/边界上
        val endPeriod = list.lastOrNull { endMinute > it.startMinute && endMinute <= it.endMinute + 5 }?.period
        return if (endPeriod != null && endPeriod > startPeriod) {
            "第 $startPeriod-$endPeriod 节"
        } else {
            "第 $startPeriod 节"
        }
    }

    /** 形如 08:00 的显示文本。 */
    fun formatMinute(minuteOfDay: Int): String {
        val h = (minuteOfDay / 60) % 24
        val m = minuteOfDay % 60
        return "%02d:%02d".format(h, m)
    }

    /** 汇总文本：按当前设置共 N 节课程：08:00 开始，22:25 结束。 */
    fun summary(config: RoutineConfig): String {
        val list = compute(config)
        if (list.isEmpty()) return "按当前设置没有节次。"
        return "按当前设置共 ${list.size} 节课程：${formatMinute(list.first().startMinute)} 开始，${formatMinute(list.last().endMinute)} 结束。"
    }

    /** 上午 / 下午 / 晚上 的区间文本：上午 1–5 节 · 下午 6–10 节 · 晚上 11–14 节。 */
    fun rangeText(config: RoutineConfig): String {
        val parts = mutableListOf<String>()
        if (config.morningCount > 0) parts += "上午 1–${config.morningCount} 节"
        val aStart = config.morningCount + 1
        val aEnd = config.morningCount + config.afternoonCount
        if (config.afternoonCount > 0) parts += "下午 $aStart–$aEnd 节"
        val eStart = aEnd + 1
        val eEnd = config.totalPeriods
        if (config.eveningCount > 0) parts += "晚上 $eStart–$eEnd 节"
        return parts.joinToString(" · ")
    }
}
