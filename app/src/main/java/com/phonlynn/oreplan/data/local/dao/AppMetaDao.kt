package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity

@Dao
interface AppMetaDao {

    @Query("SELECT COUNT(*) FROM app_meta")
    suspend fun count(): Int

    @Query("SELECT * FROM app_meta WHERE meta_key = :key LIMIT 1")
    suspend fun find(key: String): AppMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AppMetaEntity)
}
