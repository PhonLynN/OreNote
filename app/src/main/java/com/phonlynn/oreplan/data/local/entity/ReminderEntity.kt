package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 提醒。`triggerAt` 存绝对的触发时刻而不是「提前 N 分钟」——
 * 这样调度器不必理解重复规则，改规则时重算一遍即可。
 */
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["itemId"]),
        Index(value = ["triggerAt"]),
    ],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val triggerAt: Long,
    val offsetMinutes: Int?,
    val enabled: Boolean,
)
