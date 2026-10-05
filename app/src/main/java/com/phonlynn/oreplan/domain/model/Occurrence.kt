package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate

/**
 * 一次具体发生。日程和重复日程展开后都落到这个类型上，UI 不需要区分两者。
 * [originalDate] 是「原规则会在哪一天发生」，用于定位并编辑对应的例外。
 */
@Immutable
data class Occurrence(
    val itemId: String,
    val title: String,
    val startAt: Instant,
    val endAt: Instant?,
    val originalDate: LocalDate,
    val isOverride: Boolean = false,
)
