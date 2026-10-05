package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "habit_logs",
    primaryKeys = ["itemId", "epochDay"],
    indices = [Index(value = ["epochDay"])],
)
data class HabitLogEntity(
    val itemId: String,
    val epochDay: Int,
)

@Entity(
    tableName = "quantity_logs",
    indices = [Index(value = ["itemId"])],
)
data class QuantityLogEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val at: Long,
    val amount: Long,
    val label: String?,
)

@Entity(tableName = "daily_reviews")
data class DailyReviewEntity(
    @PrimaryKey val epochDay: Int,
    val text: String,
    val updatedAt: Long,
)
