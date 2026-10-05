package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: String?,
)

/**
 * 条目与标签的多对多关系。
 *
 * 这张关系表也是**以后接入笔记的伏笔**：笔记进来时只要再加 `notes` 与
 * `note_links(subjectRef, objectRef)`，就能做「日程关联笔记 / 笔记引用日程」，
 * 完全不用动现有结构。
 */
@Entity(
    tableName = "item_tags",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tagId"])],
)
data class ItemTagCrossRef(
    val itemId: String,
    val tagId: String,
)
