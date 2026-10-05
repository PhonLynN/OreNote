package com.phonlynn.oreplan.core.time

/**
 * 单双周。
 *
 * 大学课程里「单周上、双周不上」是常态，所以它必须是课表模板的一等字段，
 * 而不是靠排两条重复日程来凑。
 */
enum class WeekParity {
    ALL,
    ODD,
    EVEN,
    ;

    /** 第 [weekNumber] 周（1 起）是否落在这个单双周约定上。 */
    fun matches(weekNumber: Int): Boolean = when (this) {
        ALL -> true
        ODD -> weekNumber % 2 == 1
        EVEN -> weekNumber % 2 == 0
    }
}
