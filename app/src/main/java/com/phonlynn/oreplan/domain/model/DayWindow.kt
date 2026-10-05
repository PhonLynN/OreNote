package com.phonlynn.oreplan.domain.model

/**
 * 一天里「可用时段」的默认窗口。
 *
 * ## 为什么它必须是一个共享常量
 *
 * 用户对空闲时间工具的要求是：
 *
 * > 「直接根据日程页的时间轴部分空闲区域算出空闲时间，然后返回给 ai」
 *
 * 也就是说 **AI 说的"有空"必须和用户在日程页上看到的一致**。
 * 两边各自写一份 `8 * 60` / `22 * 60` 的话，将来有人把时间轴改成
 * 06:00–24:00，AI 就会开始推荐界面上根本没有的空档 —— 而且没有任何报错。
 *
 * 所以窗口只定义一次：[DayTimeline][com.phonlynn.oreplan.v2.screens.DayTimelineCard]
 * 和空闲时间工具都读这里。原来是时间轴文件里的两个 `private const`。
 *
 * ## 为什么是 08:00–22:00
 *
 * 设计稿 Section Head 的范围文案就是它。**不是**"一天的物理边界"，
 * 而是一天里值得拿来安排事情的时段 —— 凌晨 3 点算出来的"空闲"没有意义。
 * 工具允许调用方用 `day_window` 参数覆盖。
 */
object DayWindow {
    /** 08:00，单位是零点起的分钟数。 */
    const val DEFAULT_FROM = 8 * 60

    /** 22:00。 */
    const val DEFAULT_TO = 22 * 60

    /** 展示用，如 `08:00 – 22:00`。 */
    fun label(from: Int = DEFAULT_FROM, to: Int = DEFAULT_TO): String =
        "%02d:%02d – %02d:%02d".format(from / 60, from % 60, to / 60, to % 60)
}
