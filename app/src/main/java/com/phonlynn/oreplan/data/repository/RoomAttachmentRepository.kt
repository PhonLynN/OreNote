package com.phonlynn.oreplan.data.repository

import com.phonlynn.oreplan.data.local.dao.AttachmentDao
import com.phonlynn.oreplan.data.local.entity.AttachmentEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.TombstoneRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAttachmentRepository @Inject constructor(
    private val dao: AttachmentDao,
    private val tombstones: TombstoneRegistry,
    private val stampWriter: SyncStampWriter,
) : AttachmentRepository {

    /**
     * 附件索引行**不参与同步**（用户拍板：只给顶层实体加墓碑），
     * 所以这里不做软删，读取也不过滤 —— 附件总是随宿主实体一起被处理。
     *
     * ## ⚠️ 但 [getAll] 有一个必须守住的语义
     *
     * 它被 `OrePlanApplication.sweepOrphanAttachments()` 用来判断"哪些文件是孤儿"，
     * 判据是「磁盘上有文件、但**这个集合里没有**对应行 ⇒ 删文件」。
     *
     * 所以 [getAll] 必须返回**物理存在的全部行**，绝不能过滤。
     * 假如它过滤掉任何一行，那个附件文件就会被当成孤儿删掉 ——
     * 而用户的数据还在（条目的墓碑会把删除传到另一台设备），
     * 于是另一台设备上点开附件时**文件已经没了**。这是数据丢失事故。
     *
     * 结论：**这个仓储的读取路径一律不过滤**。以后有人加墓碑到附件上时，
     * 必须先回来看这段注释，并同步改 sweep 的判据。
     */
    override fun observeAll(): Flow<List<Attachment>> =
        dao.observeAll().map { rows -> rows.map(AttachmentEntity::toDomain) }

    override suspend fun getOf(ownerId: String): List<Attachment> =
        dao.findOf(ownerId).map(AttachmentEntity::toDomain)

    override suspend fun findById(id: String): Attachment? = dao.findById(id)?.toDomain()

    /** 见类注释：**必须是物理全量**，孤儿清理依赖它。 */
    override suspend fun getAll(): List<Attachment> =
        dao.findAll().map(AttachmentEntity::toDomain)

    override suspend fun upsert(attachment: Attachment) {
        dao.upsert(attachment.toEntity())
    }

    override suspend fun upsertAll(attachments: List<Attachment>) {
        if (attachments.isEmpty()) return
        dao.upsertAll(attachments.map(Attachment::toEntity))
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun deleteOf(ownerId: String) {
        dao.deleteOf(ownerId)
    }

    override suspend fun retainOnly(ownerId: String, keepIds: List<String>) {
        if (keepIds.isEmpty()) dao.deleteOf(ownerId) else dao.deleteNotIn(ownerId, keepIds)
    }
}
