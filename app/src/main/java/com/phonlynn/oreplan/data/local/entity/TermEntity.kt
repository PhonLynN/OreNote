package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 学期。`startDate` 以 epochDay 存，避免时区把「开学那天」整体挪动一天。
 */
@Entity(tableName = "terms")
data class TermEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 第 1 周的周一，以 1970-01-01 起算的天数表示。 */
    val startDate: Int,
    val totalWeeks: Int,
    val isActive: Boolean,
)
