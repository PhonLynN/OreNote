package com.phonlynn.oreplan.data.repository

import com.phonlynn.oreplan.data.local.dao.EntityExtDao
import com.phonlynn.oreplan.data.mapper.ExtCodec
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.data.mapper.toRecord
import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomEntityExtRepository @Inject constructor(
    private val dao: EntityExtDao,
) : EntityExtRepository {

    override suspend fun get(owner: ExtOwner, ownerId: String): ExtMap =
        ExtCodec.decode(dao.find(owner.table, ownerId)?.ext)

    override suspend fun listByTable(owner: ExtOwner): List<ExtRecord> =
        dao.listByTable(owner.table).mapNotNull { it.toRecord() }

    override fun observeAll(): Flow<List<ExtRecord>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toRecord() } }

    override suspend fun getAll(): List<ExtRecord> =
        dao.findAll().mapNotNull { it.toRecord() }

    override suspend fun set(owner: ExtOwner, ownerId: String, map: ExtMap) {
        val json = ExtCodec.encode(map)
        if (json == null) {
            // 空抽屉不留空壳行：这样「这一行在不在」就等价于「有没有抽屉」，
            // 表里不会积累一堆 {}。
            dao.delete(owner.table, ownerId)
            return
        }
        dao.upsert(ExtRecord(owner = owner, ownerId = ownerId, map = map).toEntity(now()))
    }

    override suspend fun update(
        owner: ExtOwner,
        ownerId: String,
        block: (ExtMap) -> ExtMap,
    ): ExtMap {
        // 先读后写：不会覆盖别的功能写进同一个抽屉的键。
        val next = block(get(owner, ownerId))
        set(owner, ownerId, next)
        return next
    }

    override suspend fun remove(owner: ExtOwner, ownerId: String) {
        dao.delete(owner.table, ownerId)
    }

    private fun now(): Long = System.currentTimeMillis()
}
