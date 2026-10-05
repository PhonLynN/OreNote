package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.RecurrenceExceptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurrenceExceptionDao {

    @Query("SELECT * FROM recurrence_exceptions WHERE itemId = :itemId ORDER BY date")
    fun observeOf(itemId: String): Flow<List<RecurrenceExceptionEntity>>

    /** 全部例外。一次算一整段时间的议程时，避免每个条目都单独查一次数据库。 */
    @Query("SELECT * FROM recurrence_exceptions")
    fun observeAll(): Flow<List<RecurrenceExceptionEntity>>

    @Query("SELECT * FROM recurrence_exceptions")
    suspend fun findAll(): List<RecurrenceExceptionEntity>

    @Query("SELECT * FROM recurrence_exceptions WHERE itemId = :itemId ORDER BY date")
    suspend fun findOf(itemId: String): List<RecurrenceExceptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exception: RecurrenceExceptionEntity)

    @Query("DELETE FROM recurrence_exceptions WHERE itemId = :itemId AND date = :date")
    suspend fun deleteOccurrence(itemId: String, date: Int)

    @Query("DELETE FROM recurrence_exceptions WHERE itemId = :itemId")
    suspend fun deleteOf(itemId: String)

    @Query("DELETE FROM recurrence_exceptions")
    suspend fun clearAll()
}
