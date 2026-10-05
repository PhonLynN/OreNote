package com.phonlynn.oreplan.data.repository

import com.phonlynn.oreplan.data.local.dao.ChecklistDao
import com.phonlynn.oreplan.data.local.entity.ChecklistEntryEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.repository.ChecklistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomChecklistRepository @Inject constructor(
    private val dao: ChecklistDao,
) : ChecklistRepository {

    override fun observeAll(): Flow<List<ChecklistEntry>> =
        dao.observeAll().map { rows -> rows.map(ChecklistEntryEntity::toDomain) }

    override fun observeOf(itemId: String): Flow<List<ChecklistEntry>> =
        dao.observeOf(itemId).map { rows -> rows.map(ChecklistEntryEntity::toDomain) }

    override suspend fun getOf(itemId: String): List<ChecklistEntry> =
        dao.findOf(itemId).map(ChecklistEntryEntity::toDomain)

    override suspend fun getAll(): List<ChecklistEntry> =
        dao.findAll().map(ChecklistEntryEntity::toDomain)

    override suspend fun upsert(entry: ChecklistEntry) {
        dao.upsert(entry.toEntity())
    }

    override suspend fun upsertAll(entries: List<ChecklistEntry>) {
        if (entries.isEmpty()) return
        dao.upsertAll(entries.map(ChecklistEntry::toEntity))
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun deleteOf(itemId: String) {
        dao.deleteOf(itemId)
    }

    override suspend fun retainOnly(itemId: String, keepIds: List<String>) {
        if (keepIds.isEmpty()) dao.deleteOf(itemId) else dao.deleteNotIn(itemId, keepIds)
    }
}
