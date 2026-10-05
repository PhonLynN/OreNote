package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate

/**
 * 重复日程的例外。
 *
 * 只做两种动作 —— 「删除本次」和「本次改期」。**不做「只改本次的某些字段」**：
 * 那是日程类应用里复杂度最高的部分（要区分系列级与实例级字段的覆盖优先级），
 * 对大学生场景收益有限，却会显著拖慢主线。
 */
enum class ExceptionAction {
    /** 删除本次。展开时跳过这一天。 */
    DELETED,

    /** 本次改期。展开时用 [RecurrenceException.overrideStartAt] 等字段替换。 */
    OVERRIDDEN,
}

@Immutable
data class RecurrenceException(
    val id: String,
    val itemId: String,
    /** 原规则会在此日产生一次发生，这一天就是被改动的目标。 */
    val date: LocalDate,
    val action: ExceptionAction,
    val overrideStartAt: Instant? = null,
    val overrideEndAt: Instant? = null,
    val overrideTitle: String? = null,
)
