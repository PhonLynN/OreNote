package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/**
 * 学期。
 *
 * [startDate] 必须是**第 1 周的周一**（约定见 `TermClock`）。
 * 同时只允许有一个 [isActive] 学期，否则「第几周」会有两个答案。
 */
@Immutable
data class Term(
    val id: String,
    val name: String,
    val startDate: LocalDate,
    val totalWeeks: Int,
    val isActive: Boolean = false,
) {
    init {
        require(totalWeeks >= 1) { "学期周数至少为 1，实际 $totalWeeks" }
    }
}
