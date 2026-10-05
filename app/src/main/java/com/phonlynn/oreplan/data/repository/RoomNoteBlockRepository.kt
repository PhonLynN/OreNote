package com.phonlynn.oreplan.data.repository

import com.phonlynn.oreplan.data.local.dao.NoteBlockDao
import com.phonlynn.oreplan.data.local.entity.NoteBlockEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.repository.NoteBlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomNoteBlockRepository @Inject constructor(
    private val dao: NoteBlockDao,
) : NoteBlockRepository {

    override fun observeOf(ownerId: String): Flow<List<NoteBlock>> =
        dao.observeOf(ownerId).map { rows -> rows.map(NoteBlockEntity::toDomain) }

    override suspend fun getOf(ownerId: String): List<NoteBlock> =
        dao.findOf(ownerId).map(NoteBlockEntity::toDomain)

    override suspend fun getAll(): List<NoteBlock> =
        dao.findAll().map(NoteBlockEntity::toDomain)

    override suspend fun upsert(block: NoteBlock) {
        dao.upsert(block.toEntity())
    }

    override suspend fun upsertAll(blocks: List<NoteBlock>) {
        if (blocks.isEmpty()) return
        dao.upsertAll(blocks.map(NoteBlock::toEntity))
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun deleteOf(ownerId: String) {
        dao.deleteOf(ownerId)
    }

    override suspend fun retainOnly(ownerId: String, keepIds: List<String>) {
        // `NOT IN ()` 在 SQLite 里是语法错误，空列表必须换成「全删」。
        if (keepIds.isEmpty()) dao.deleteOf(ownerId) else dao.deleteNotIn(ownerId, keepIds)
    }
}
