package com.phonlynn.oreplan.v2.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** 日历视图。 */
/**
 * 日程页的一级视图。
 *
 * **只有月与日**：周视图已删除（用户 2026-09-23 明确要求，它在设计里没有独立作用，
 * 内容与日视图重复）。
 */
enum class CalendarView { Month, Day }

/** 日视图下的二级模式：日程 / 待办。 */
enum class CalendarMode { Schedule, Todo }

/** 从其他页面进入日历时带的一次性意图。 */
data class CalendarRequest(
    val view: CalendarView,
    val mode: CalendarMode = CalendarMode.Schedule,
    val date: LocalDate? = null,
)

/**
 * 一次性跳转意图：今日页「查看全部 / 全部待办」先写入意图再切 Tab，
 * 日历页读取并消费。避免给 Tab 路由加参数（Tab 路由必须保持无参，
 * 否则底栏切换与深链会产生两份目的地）。
 */
object CalendarEntryBus {
    private val _request = MutableStateFlow<CalendarRequest?>(null)
    val request: StateFlow<CalendarRequest?> = _request

    fun openDay(date: LocalDate) {
        _request.value = CalendarRequest(CalendarView.Day, CalendarMode.Schedule, date)
    }

    fun openTodo(date: LocalDate = LocalDate.now()) {
        _request.value = CalendarRequest(CalendarView.Day, CalendarMode.Todo, date)
    }

    fun consume() {
        _request.value = null
    }
}
