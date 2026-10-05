package com.phonlynn.oreplan.data.repository

import com.phonlynn.oreplan.data.local.dao.FocusSessionDao
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.repository.FocusRepository
import com.phonlynn.oreplan.domain.sync.SyncEntity
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.TombstoneRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomFocusRepository @Inject constructor(
    private val dao: FocusSessionDao,
    private val tombstones: TombstoneRegistry,
    private val stampWriter: SyncStampWriter,
) : FocusRepository {

    private fun List<com.phonlynn.oreplan.data.local.entity.FocusSessionEntity>.visible(): List<FocusSession> =
        tombstones.filter(SyncEntity.FOCUS_SESSION, this) { it.id }.map { it.toDomain() }

    override fun observeAll(): Flow<List<FocusSession>> =
        tombstones.observing(SyncEntity.FOCUS_SESSION, dao.observeAll())
            .map { list -> list.visible() }

    override suspend fun getAll(): List<FocusSession> =
        dao.findAll().visible()

    override suspend fun listByItem(itemId: String): List<FocusSession> =
        dao.listByItem(itemId).visible()

    override suspend fun getById(id: String): FocusSession? =
        tombstones.filterOne(SyncEntity.FOCUS_SESSION, dao.findById(id), id)?.toDomain()

        /** 物理全量（含墓碑）。同步打包用 —— 见接口注释。 */
    override suspend fun allIncludingDeleted(): List<FocusSession> =
        dao.findAll().map { it.toDomain() }

override suspend fun save(session: FocusSession) {
        dao.upsert(session.toEntity())
        stampWriter.stampModified(SyncEntity.FOCUS_SESSION, session.id)
    }

    /**
     * 删除专注记录 —— **软删**。
     *
     * 专注记录是「只增不改」的流水（数据层已定：记完就不动），
     * 但删除仍要能传播 —— 用户在 A 设备删掉一条误记的专注，
     * 不能在 B 设备上又冒出来，否则热力图与统计会两边不一致。
     */
    override suspend fun delete(id: String) {
        stampWriter.stampDeleted(SyncEntity.FOCUS_SESSION, id)
        tombstones.markDeleted(SyncEntity.FOCUS_SESSION, id)
    }
}
