package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.NoteBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteBlockDao {

    @Query("SELECT * FROM note_blocks WHERE ownerId = :ownerId ORDER BY orderIndex, createdAt")
    fun observeOf(ownerId: String): Flow<List<NoteBlockEntity>>

    @Query("SELECT * FROM note_blocks WHERE ownerId = :ownerId ORDER BY orderIndex, createdAt")
    suspend fun findOf(ownerId: String): List<NoteBlockEntity>

    @Query("SELECT * FROM note_blocks ORDER BY ownerId, orderIndex")
    suspend fun findAll(): List<NoteBlockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NoteBlockEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<NoteBlockEntity>)

    @Query("DELETE FROM note_blocks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM note_blocks WHERE ownerId = :ownerId")
    suspend fun deleteOf(ownerId: String)

    /**
     * 保留 [keepIds]，其余删除。
     *
     * 调用方必须先保证 [keepIds] 非空 —— `NOT IN ()` 在 SQLite 里是语法错误。
     * 空列表的语义由 [deleteOf] 承担。
     */
    @Query("DELETE FROM note_blocks WHERE ownerId = :ownerId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(ownerId: String, keepIds: List<String>)

    @Query("DELETE FROM note_blocks")
    suspend fun clearAll()
}
