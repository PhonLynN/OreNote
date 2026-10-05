package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.TermEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TermDao {

    @Query("SELECT * FROM terms WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<TermEntity?>

    @Query("SELECT * FROM terms ORDER BY startDate DESC")
    fun observeAll(): Flow<List<TermEntity>>

    @Query("SELECT * FROM terms ORDER BY startDate DESC")
    suspend fun findAll(): List<TermEntity>

    @Query("SELECT * FROM terms WHERE isActive = 1 LIMIT 1")
    suspend fun findActive(): TermEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(term: TermEntity)

    /** 激活某个学期前先把其他学期全部取消 —— 同时只允许一个活动学期，否则「第几周」会有两个答案。 */
    @Query("UPDATE terms SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE terms SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Query("DELETE FROM terms")
    suspend fun clearAll()

    @Query("DELETE FROM terms WHERE id = :id")
    suspend fun deleteById(id: String)
}
