package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * 提醒。
 *
 * [triggerAt] 存的是**算好的绝对时刻**而不是「提前 N 分钟」。原因是重复日程的
 * 每个实例提醒时刻都不同，存绝对时刻让调度器（AlarmManager）不需要理解重复规则，
 * 改规则时重算一遍即可。
 *
 * [offsetMinutes] 仅用于编辑界面回填「提前多久」，真正调度只认 [triggerAt]。
 */
@Immutable
data class Reminder(
    val id: String,
    val itemId: String,
    val triggerAt: Instant,
    val offsetMinutes: Int? = null,
    val enabled: Boolean = true,
)
