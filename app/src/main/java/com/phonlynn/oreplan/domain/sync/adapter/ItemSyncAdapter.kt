package com.phonlynn.oreplan.domain.sync.adapter

import com.phonlynn.oreplan.domain.backup.BackupCodec
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
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
 * `items` 表（规划目标 / 子项 / 日程 / 待办 / 习惯提醒载体）的同步适配器。
 *
 * 复用 `BackupCodec.itemToJson/itemFromJson` —— 那份 20+ 字段的映射已有
 * `BackupCompletenessTest` 用编译器生成的 schema 反向校验，是全项目唯一
 * 能抓出"漏字段"的机制。同步复用即自动继承这道防线；另写一份必然漂移，
 * 而漂移的后果是**静默丢字段**。
 */
@Singleton
class ItemSyncAdapter @Inject constructor(
    private val itemRepository: ItemRepository,
    private val extRepo: EntityExtRepository,
    private val stampWriter: SyncStampWriter,
) : SyncTableAdapter {

    override val entity: SyncEntity = SyncEntity.ITEM

    override val payloadKey: String = "item"

    override suspend fun readAll(): Map<String, SyncEnvelope> =
        itemRepository.getAllIncludingDeleted().associate { item ->
            val meta = SyncMeta.from(extRepo.get(entity.ext, item.id))
            item.id to SyncEnvelope(
                table = entity.table,
                id = item.id,
                rev = meta.rev,
                hlc = meta.hlc?.encode(),
                deviceId = meta.deviceId,
                deletedAt = meta.deletedAt,
                payload = wrapPayload(payloadKey, BackupCodec.itemToJson(item)),
            )
        }

    /**
     * ⚠️ **顺序：业务行先写，同步元数据后写。**
     *
     * 直觉上应该"先写元数据，这样即使业务写失败也记得状态"，但那样会坏在一件事上：
     * 仓储的 `create/update` 内部会调 `SyncStampWriter.stampModified`
     * **给这条盖上一个本机的新 HLC**。若元数据先写、业务后写，
     * 那次盖章会把**远端的 HLC 覆盖成本机的** —— 于是本机看起来"刚改过"，
     * 下次同步又把它传回去，来回震荡、永不收敛。
     */
    override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
        if (!winner.isTombstone) {
            winner.entityBody(payloadKey)?.let { body ->
                val item = BackupCodec.itemFromJson(body)
                if (existsLocally) itemRepository.update(item) else itemRepository.create(item)
            }
        }
        // 覆盖掉仓储写入时盖上的本地时钟，改为承认远端的状态。
        stampWriter.writeFromRemote(
            entity,
            winner.id,
            SyncMeta(
                rev = winner.rev,
                hlc = winner.hlcValue,
                deviceId = winner.deviceId,
                deletedAt = winner.deletedAt,
            ),
        )
    }
}
