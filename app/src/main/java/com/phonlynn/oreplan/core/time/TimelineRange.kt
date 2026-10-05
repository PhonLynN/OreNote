package com.phonlynn.oreplan.core.time

/**
 * 时间轴的显示区间，单位是小时。
 *
 * **固定用整天（0:00–24:00）**。早先的版本是「按当天实际内容推算区间」，理由是那样
 * 空白时段不会占屏；但实际使用下来代价更大：早上 8 点想加一个 7 点的日程，时间轴上
 * 根本没有那一格可点，必须先从别处新建再改时间。整天铺开之后任何一个时刻都能直接点，
 * 这才是「便捷地添加日程」。
 *
 * 保留成数据类而不是常量，是为了让「整天」这件事在类型上仍然显式 ——
 * 哪天要恢复成按内容推算，只需要在这里加一个工厂方法，调用点不用改。
 */
data class TimelineRange(
    val startHour: Int,
    val endHour: Int,
) {
    val hourCount: Int get() = endHour - startHour

    companion object {
        /** 整天：0:00 到 24:00。日视图、周视图与今日页都用它。 */
        val FullDay = TimelineRange(0, 24)
    }
}
