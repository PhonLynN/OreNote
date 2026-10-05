package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.Attachment
import kotlinx.coroutines.flow.Flow

/**
 * 附件索引仓储。它只管**元信息**，文件的复制/读取/删除在
 * `platform/attachment/AttachmentStorage`，两边由用例层配合（先落库行再写文件，
 * 或反之）—— 仓储不碰磁盘，才能保持纯数据层可测。
 */
interface AttachmentRepository {

    /** 全表。界面按 `ownerId` 分组使用，避免为每条清单项各开一个订阅。 */
    fun observeAll(): Flow<List<Attachment>>

    suspend fun getOf(ownerId: String): List<Attachment>

    suspend fun findById(id: String): Attachment?

    suspend fun getAll(): List<Attachment>

    suspend fun upsert(attachment: Attachment)

    suspend fun upsertAll(attachments: List<Attachment>)

    suspend fun delete(id: String)

    suspend fun deleteOf(ownerId: String)

    /** [keepIds] 为空即全删。 */
    suspend fun retainOnly(ownerId: String, keepIds: List<String>)
}
