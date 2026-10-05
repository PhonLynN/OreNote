package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次专注记录。
 *
 * ## 外键为什么是 `SET_NULL` 而不是 `CASCADE`
 *
 * `CASCADE`（如 `reminders`）意味着「删了条目，提醒一起删」—— 那对提醒是对的，
 * 提醒是条目的附属品。
 *
 * 但**专注历史不是附属品**：它记录的是「我确实专注了 25 分钟」这件已经发生的事，
 * 本身就值得留着（专注统计要靠它）。所以删掉关联的待办时，记录保留、
 * 只是把 [itemId] 置空。这样统计不会因为清理任务列表而凭空缩水。
 *
 * `itemId` 可空是 `SET_NULL` 的前提条件。
 */
@Entity(
    tableName = "focus_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["itemId"]),
        Index(value = ["startedAt"]),
    ],
)
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long,
    /** 净专注分钟（已扣暂停）。 */
    val minutes: Int,
    /** 计时方式：`countup` / `countdown` / `pomodoro`。基本类型，不做枚举约束。 */
    val kind: String,
    val plannedMinutes: Int?,
    val completed: Boolean,
    val label: String?,
    /** 关联的待办/目标条目。条目被删除时由外键自动置空。 */
    val itemId: String?,
    val createdAt: Long,
)
