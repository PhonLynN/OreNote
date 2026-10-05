package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 计划笔记的小节。
 *
 * `ownerId` 指向条目（也可以指向工作区条目）—— 不建外键：条目被删除时小节由仓储
 * 一并清理，而外键在这里只会让「先删条目还是先删小节」变成一个必须记住的顺序问题。
 */
@Entity(
    tableName = "note_blocks",
    indices = [Index(value = ["ownerId"])],
)
data class NoteBlockEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val heading: String,
    val body: String,
    val orderIndex: Double,
    val createdAt: Long,
    val updatedAt: Long,
)
