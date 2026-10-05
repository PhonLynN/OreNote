package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.AgendaEntry

/**
 * 「当天清单」（日历月/周视图下方那块）的显示顺序：**课程在前、按时间；条目在后、按手动顺序**。
 *
 * 为什么不整份按时间排：这份清单是**可以拖动排序的列表**（用户 2026-09-12 选定「整份我自己排」），
 * 而「能拖」与「按时间排」不能同时成立 —— 排完的顺序得能存下来、下次打开还是那个顺序，
 * 否则拖完就跳回去，看起来就是「拖了没反应」。课程来自课表模板，本身没有手动顺序，
 * 只能按时间固定排在最前面（当作这一天的骨架），可拖的部分集中在其后。
 *
 * **条目那一段的比较键必须与 `ReorderItemsUseCase` 的全局顺序完全一致**
 * （orderIndex → createdAt → title）。两处不一致时，「看到的是第几行」换算到
 * 「库里的第几项」就会错位 —— 症状是拖了没反应、或者旁边那一项莫名跳位置。
 *
 * 纯函数，单测覆盖。
 */
fun dayListOrder(entries: List<AgendaEntry>): List<AgendaEntry> {
    val courses = entries
        .filter { it.isCourse }
        .sortedWith(
            compareBy(
                { it.startMinute ?: Int.MAX_VALUE },
                { it.endMinute ?: Int.MAX_VALUE },
                { it.title },
            ),
        )
    val items = entries
        .filterNot { it.isCourse }
        .sortedWith(itemOrder)
    return courses + items
}

/** 条目的手动顺序。与 `ReorderItemsUseCase` 里的排序键保持一致。 */
internal val itemOrder: Comparator<AgendaEntry> =
    compareBy({ it.orderIndex }, { it.createdAtMillis }, { it.title })
