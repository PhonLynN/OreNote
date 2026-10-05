package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.DailyReviewEntity
import com.phonlynn.oreplan.data.local.entity.HabitLogEntity
import com.phonlynn.oreplan.data.local.entity.QuantityLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitLogDao {

    @Query("SELECT * FROM habit_logs WHERE itemId = :itemId ORDER BY epochDay DESC")
    fun observeByItem(itemId: String): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_logs ORDER BY epochDay DESC")
    fun observeAll(): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_logs")
    suspend fun listAll(): List<HabitLogEntity>

    @Query("DELETE FROM habit_logs")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: HabitLogEntity)

    @Query("DELETE FROM habit_logs WHERE itemId = :itemId AND epochDay = :epochDay")
    suspend fun delete(itemId: String, epochDay: Int)

    @Query("DELETE FROM habit_logs WHERE itemId = :itemId")
    suspend fun deleteByItem(itemId: String)
}

@Dao
interface QuantityLogDao {

    @Query("SELECT * FROM quantity_logs WHERE itemId = :itemId ORDER BY at DESC")
    fun observeByItem(itemId: String): Flow<List<QuantityLogEntity>>

    @Query("SELECT * FROM quantity_logs WHERE itemId = :itemId ORDER BY at DESC")
    suspend fun listByItem(itemId: String): List<QuantityLogEntity>

    @Query("SELECT * FROM quantity_logs")
    suspend fun listAll(): List<QuantityLogEntity>

    @Query("DELETE FROM quantity_logs")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: QuantityLogEntity)

    @Query("DELETE FROM quantity_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM quantity_logs WHERE itemId = :itemId")
    suspend fun deleteByItem(itemId: String)
}

@Dao
interface DailyReviewDao {

    @Query("SELECT * FROM daily_reviews WHERE epochDay = :epochDay")
    fun observeByDay(epochDay: Int): Flow<DailyReviewEntity?>

    @Query("SELECT * FROM daily_reviews")
    suspend fun listAll(): List<DailyReviewEntity>

    @Query("DELETE FROM daily_reviews")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(review: DailyReviewEntity)

    @Query("DELETE FROM daily_reviews WHERE epochDay = :epochDay")
    suspend fun deleteByDay(epochDay: Int)
}
