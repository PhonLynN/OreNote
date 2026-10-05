package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.Instant

/** 习惯打卡记录：一天一条。 */
@Immutable
data class HabitLog(
    val itemId: String,
    val epochDay: Int,
)

/** 数量目标的记录（读完一本、投递一份…）。 */
@Immutable
data class QuantityLog(
    val id: String,
    val itemId: String,
    val at: Instant,
    val amount: Long,
    val label: String? = null,
)

/** 每日复盘。 */
@Immutable
data class DailyReview(
    val epochDay: Int,
    val text: String,
    val updatedAt: Instant,
)
