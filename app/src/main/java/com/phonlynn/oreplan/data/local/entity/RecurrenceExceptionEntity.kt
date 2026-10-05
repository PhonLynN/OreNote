package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 重复日程的例外：删除本次 / 本次改期。
 *
 * `(itemId, date)` 唯一 —— 同一个条目的同一天只允许一条例外，重复写入会互相覆盖而不是堆积。
 */
@Entity(
    tableName = "recurrence_exceptions",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["itemId", "date"], unique = true)],
)
data class RecurrenceExceptionEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val date: Int,
    val action: String,
    val overrideStartAt: Long?,
    val overrideEndAt: Long?,
    val overrideTitle: String?,
)
