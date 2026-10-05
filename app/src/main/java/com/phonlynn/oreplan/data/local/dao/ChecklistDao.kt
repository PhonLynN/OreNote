package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.ChecklistEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChecklistDao {

    @Query("SELECT * FROM checklist_entries WHERE itemId = :itemId ORDER BY orderIndex, createdAt")
    fun observeOf(itemId: String): Flow<List<ChecklistEntryEntity>>

    /** 全表观察：条目列表要显示「清单 2/4」的进度徽标，需要一次拿到所有计数。 */
    @Query("SELECT * FROM checklist_entries ORDER BY itemId, orderIndex")
    fun observeAll(): Flow<List<ChecklistEntryEntity>>

    @Query("SELECT * FROM checklist_entries WHERE itemId = :itemId ORDER BY orderIndex, createdAt")
    suspend fun findOf(itemId: String): List<ChecklistEntryEntity>

    @Query("SELECT * FROM checklist_entries ORDER BY itemId, orderIndex")
    suspend fun findAll(): List<ChecklistEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChecklistEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChecklistEntryEntity>)

    @Query("DELETE FROM checklist_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM checklist_entries WHERE itemId = :itemId")
    suspend fun deleteOf(itemId: String)

    /** [keepIds] 必须非空，理由同 [NoteBlockDao.deleteNotIn]。 */
    @Query("DELETE FROM checklist_entries WHERE itemId = :itemId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(itemId: String, keepIds: List<String>)

    @Query("DELETE FROM checklist_entries")
    suspend fun clearAll()
}
