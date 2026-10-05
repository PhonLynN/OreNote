package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE ownerId = :ownerId ORDER BY createdAt")
    fun observeOf(ownerId: String): Flow<List<AttachmentEntity>>

    /** 全表观察。界面按 `ownerId` 在内存里分组，避免为每条清单项各开一个订阅。 */
    @Query("SELECT * FROM attachments ORDER BY ownerId, createdAt")
    fun observeAll(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE ownerId = :ownerId ORDER BY createdAt")
    suspend fun findOf(ownerId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun findById(id: String): AttachmentEntity?

    /**
     * 全表。孤儿文件清理要拿它和磁盘上的文件对比 ——
     * 库里没有对应行的文件就是「编辑到一半放弃」留下的垃圾。
     */
    @Query("SELECT * FROM attachments ORDER BY ownerId, createdAt")
    suspend fun findAll(): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM attachments WHERE ownerId = :ownerId")
    suspend fun deleteOf(ownerId: String)

    /** [keepIds] 必须非空，理由同 [NoteBlockDao.deleteNotIn]。 */
    @Query("DELETE FROM attachments WHERE ownerId = :ownerId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(ownerId: String, keepIds: List<String>)

    @Query("DELETE FROM attachments")
    suspend fun clearAll()
}
