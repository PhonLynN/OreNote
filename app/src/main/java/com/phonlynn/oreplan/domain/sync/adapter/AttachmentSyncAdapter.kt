package com.phonlynn.oreplan.domain.sync.adapter

import com.phonlynn.oreplan.domain.backup.BackupCodec
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.sync.SyncEntity
import com.phonlynn.oreplan.domain.sync.SyncEnvelope
import com.phonlynn.oreplan.domain.sync.SyncMeta
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.SyncTableAdapter
import com.phonlynn.oreplan.domain.sync.entityBody
import com.phonlynn.oreplan.domain.sync.wrapPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附件的**索引行**同步（二进制本身走 `BlobStore`，不在这里）。
 *
 * ## 为什么索引与二进制分开同步
 *
 * 一次同步要能"先让数据一致、再让文件按需到达"：
 *  · 索引行很小，几秒钟就能同步完 —— 同步完成后，另一台设备**立刻看得见**
 *    "某条待办有一张图"，只是图还没下载；
 *  · 二进制可能几十 MB，若塞进同一次同步，用户要等到全部传完才看到任何变化，
 *    而且弱网下极易中途失败、整次同步白跑。
 *
 * 所以：**索引随实体同步；二进制按需**（用户点开附件时才拉），
 * 界面上用"未下载"角标表达状态。这也符合附件同步的设计（§2）。
 *
 * ## 内容哈希存在哪
 *
 * 走 `entity_ext` 抽屉的 `sync.blobHash` 键（`SyncMeta.KEY_BLOB_HASH`）——
 * 老表 `attachments` 一列都不加，与全项目的"加新功能不改老表"一致。
 *
 * ## ⚠️ 附件的删除语义与其它表**不同**
 *
 * 附件索引行**不参与软删**（S1 的决策：附件随宿主实体处理、不做墓碑）。
 * 但索引要跨设备一致，所以这里仍然按"能同步的顶层实体"处理：
 *  · 宿主实体被软删时，它的附件索引**不过滤**（S1 已确立：
 *    软删路径不碰附件索引行与文件，否则孤儿清理会把文件删掉）；
 *  · 用户真的删掉某条附件时，本机删索引行 ⇒ 同步会把"云端没有这条对象"
 *    解读为墓碑并传播。
 */
@Singleton
class AttachmentSyncAdapter @Inject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val extRepo: EntityExtRepository,
    private val stampWriter: SyncStampWriter,
) : SyncTableAdapter {

    override val entity: SyncEntity = SyncEntity.ATTACHMENT
    override val payloadKey: String = "attachment"

    override suspend fun readAll(): Map<String, SyncEnvelope> =
        attachmentRepository.getAll().associate { attachment ->
            val meta = SyncMeta.from(extRepo.get(entity.ext, attachment.id))
            attachment.id to SyncEnvelope(
                table = entity.table,
                id = attachment.id,
                rev = meta.rev,
                hlc = meta.hlc?.encode(),
                deviceId = meta.deviceId,
                deletedAt = meta.deletedAt,
                payload = wrapPayload(payloadKey, BackupCodec.attachmentToJson(attachment)),
            )
        }

    /**
     * 落库时**不能直接采用远端的 storedPath**。
     *
     * 路径是"相对本机 filesDir 的"，另一台设备算出来的路径可能不同
     * （不同 id → 不同目录）。所以：
     *  · 保留本机已有的路径（若这条已存在）；
     *  · 新来的按本机规则生成一个路径（复用附件仓储/存储的命名约定）；
     *  · 真正的内容靠 `sync.blobHash` 指向的云端对象，下载后写到那个路径。
     */
    override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
        if (!winner.isTombstone) {
            winner.entityBody(payloadKey)?.let { body ->
                val remote = BackupCodec.attachmentFromJson(body)
                val local = attachmentRepository.findById(remote.id)
                attachmentRepository.upsert(
                    if (local == null) {
                        remote.copy(storedPath = localPathFor(remote))
                    } else {
                        // 保留本机路径与创建时间：这两样是"本机事实"，不该被远端覆盖
                        remote.copy(
                            storedPath = local.storedPath,
                            createdAt = local.createdAt,
                        )
                    },
                )
            }
        }
        stampWriter.writeFromRemote(
            entity,
            winner.id,
            SyncMeta(winner.rev, winner.hlcValue, winner.deviceId, winner.deletedAt),
        )
    }

    /**
     * 本机路径规则，与 `AttachmentStorage.relativePathOf` 保持一致。
     *
     * ⚠️ 与那边是**同一个约定**：`attachments/<id>/<文件名>`。
     * 若将来那边改了规则，这里必须一起改 —— 否则新同步来的附件会落到
     * 一个孤儿清理会误删的位置。这是重复的口径，所以写在这里提醒。
     */
    private fun localPathFor(attachment: Attachment): String {
        val safeName = attachment.displayName
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
            .ifBlank { "附件" }
        return "attachments/${attachment.id}/$safeName"
    }
}
