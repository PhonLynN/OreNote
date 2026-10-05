package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 附件的索引行。**文件本体不在库里** —— 在 `filesDir/attachments/…`，
 * 这里只记元信息与相对路径。理由见 `domain/model/Attachment`。
 */
@Entity(
    tableName = "attachments",
    indices = [Index(value = ["ownerId"])],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val ownerType: String,
    val ownerId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** 相对 `filesDir` 的路径。存绝对路径会在恢复备份后失效。 */
    val storedPath: String,
    val createdAt: Long,
)
