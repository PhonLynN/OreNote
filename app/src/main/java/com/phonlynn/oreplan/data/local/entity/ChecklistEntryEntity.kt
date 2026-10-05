package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 备忘清单的一条。
 *
 * `category` 与 `done` 都是基本类型：分类存字符串、布尔存 INTEGER，
 * 都不做数据库枚举约束（与 `items.kind` 同一套约定），以后加分类不用迁移。
 */
@Entity(
    tableName = "checklist_entries",
    indices = [Index(value = ["itemId"])],
)
data class ChecklistEntryEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val category: String,
    val title: String,
    val done: Boolean,
    val orderIndex: Double,
    val createdAt: Long,
    val updatedAt: Long,
)
