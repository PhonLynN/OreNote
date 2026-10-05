package com.phonlynn.oreplan.data.repository

import androidx.room.withTransaction
import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.dao.TermDao
import com.phonlynn.oreplan.data.local.entity.TermEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.Term
import com.phonlynn.oreplan.domain.repository.TermRepository
import com.phonlynn.oreplan.domain.sync.SyncEntity
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.TombstoneRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTermRepository @Inject constructor(
    private val database: AppDatabase,
    private val termDao: TermDao,
    private val tombstones: TombstoneRegistry,
    private val stampWriter: SyncStampWriter,
) : TermRepository {

    private fun List<TermEntity>.visible(): List<Term> =
        tombstones.filter(SyncEntity.TERM, this) { it.id }.map(TermEntity::toDomain)

    override fun observeActiveTerm(): Flow<Term?> =
        termDao.observeActive().map { row ->
            // 「当前学期」这个位置也要过滤：软删掉的学期若还占着 active 位，
            // 课表页会显示一个已被删除的学期名，而且再也选不回来。
            if (row != null && tombstones.isDeleted(SyncEntity.TERM, row.id)) null
            else row?.toDomain()
        }

    override fun observeAll(): Flow<List<Term>> =
        tombstones.observing(SyncEntity.TERM, termDao.observeAll())
            .map { rows -> rows.visible() }

    override suspend fun getActiveTerm(): Term? =
        tombstones.filterOne(SyncEntity.TERM, termDao.findActive(), termDao.findActive()?.id ?: "")
            ?.toDomain()

        /** 物理全量（含墓碑）。同步打包用 —— 见接口注释。 */
    override suspend fun allTermsIncludingDeleted(): List<Term> =
        termDao.findAll().map(TermEntity::toDomain)

override suspend fun upsert(term: Term) {
        database.withTransaction {
            if (term.isActive) termDao.deactivateAll()
            termDao.upsert(term.toEntity())
            stampWriter.stampModified(SyncEntity.TERM, term.id)
        }
    }

    override suspend fun activate(termId: String) {
        database.withTransaction {
            termDao.deactivateAll()
            termDao.activate(termId)
            // 「哪一个是当前学期」是学期实体自身的状态，改了它也算一次修改。
            stampWriter.stampModified(SyncEntity.TERM, termId)
        }
    }

    override suspend fun delete(termId: String) {
        database.withTransaction {
            stampWriter.stampDeleted(SyncEntity.TERM, termId)
        }
        tombstones.markDeleted(SyncEntity.TERM, termId)
    }
}
