package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.EntityExtEntity
import kotlinx.coroutines.flow.Flow

/**
 * 扩展抽屉的读写。
 *
 * 表名一律走 [com.phonlynn.oreplan.domain.model.ExtOwner] 的 `table` 值传入，
 * 调用方不手写字符串。
 */
@Dao
interface EntityExtDao {

    @Query("SELECT * FROM entity_ext WHERE ownerTable = :table AND ownerId = :ownerId")
    suspend fun find(table: String, ownerId: String): EntityExtEntity?

    /** 批量取某张表下所有抽屉行：给「一次要读很多实体」的场景（AI 上下文、备份）用。 */
    @Query("SELECT * FROM entity_ext WHERE ownerTable = :table")
    suspend fun listByTable(table: String): List<EntityExtEntity>

    @Query("SELECT * FROM entity_ext ORDER BY ownerTable ASC, ownerId ASC")
    fun observeAll(): Flow<List<EntityExtEntity>>

    @Query("SELECT * FROM entity_ext ORDER BY ownerTable ASC, ownerId ASC")
    suspend fun findAll(): List<EntityExtEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: EntityExtEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<EntityExtEntity>)

    @Query("DELETE FROM entity_ext WHERE ownerTable = :table AND ownerId = :ownerId")
    suspend fun delete(table: String, ownerId: String)

    @Query("DELETE FROM entity_ext")
    suspend fun clearAll()
}
