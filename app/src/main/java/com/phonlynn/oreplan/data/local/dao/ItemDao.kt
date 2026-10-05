package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.phonlynn.oreplan.data.local.entity.ItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun findById(id: String): ItemEntity?

    @Query("SELECT * FROM items ORDER BY treePath, orderIndex")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items ORDER BY treePath, orderIndex")
    suspend fun findAll(): List<ItemEntity>

    /** 某一层的子节点。与 [observeChildren] 同一排序，供拖动落位计算排序键。 */
    @Query("SELECT * FROM items WHERE parentId IS :parentId ORDER BY orderIndex, createdAt")
    suspend fun findChildren(parentId: String?): List<ItemEntity>

    /** `IS` 而不是 `=`：parentId 可为 null，而 SQL 里 `= NULL` 永远不成立。 */
    @Query("SELECT * FROM items WHERE parentId IS :parentId ORDER BY orderIndex, createdAt")
    fun observeChildren(parentId: String?): Flow<List<ItemEntity>>

    /**
     * 整棵子树（含自身）。前缀直接由子查询算出来，避免「先查路径再查子树」的两步竞态。
     * 根节点不存在时子查询为 NULL，`NULL || '%'` 仍为 NULL，条件不成立，自然返回空表。
     */
    @Query(
        """
        SELECT * FROM items
        WHERE treePath LIKE (SELECT treePath FROM items WHERE id = :rootId) || '%'
        ORDER BY treePath, orderIndex
        """,
    )
    fun observeSubtree(rootId: String): Flow<List<ItemEntity>>

    /**
     * 与 [since, until) 有时间交集的已排期条目。
     * 左闭右开：一个 10:00 结束的日程与一个 10:00 开始的日程不重叠，不该互相挤占横向空间。
     */
    @Query(
        """
        SELECT * FROM items
        WHERE startAt IS NOT NULL
          AND startAt < :until
          AND (endAt IS NULL OR endAt > :since)
        ORDER BY startAt
        """,
    )
    fun observeScheduledBetween(since: Long, until: Long): Flow<List<ItemEntity>>

    @Query(
        """
        SELECT * FROM items
        WHERE softDueAt IS NOT NULL
          AND softDueAt BETWEEN :since AND :until
          AND status != 'CANCELLED'
        ORDER BY softDueAt
        """,
    )
    fun observeDueBetween(since: Long, until: Long): Flow<List<ItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ItemEntity)

    @Query("UPDATE items SET parentId = :parentId WHERE id = :id")
    suspend fun updateParent(id: String, parentId: String?)

    /** 只改同级排序键。拖动排序每次只动一行，不必重写整层。 */
    @Query("UPDATE items SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun updateOrderIndex(id: String, orderIndex: Double)

    @Query("UPDATE items SET groupId = :groupId WHERE id = :id")
    suspend fun updateGroupId(id: String, groupId: String?)

    /**
     * 批量写序号（拖动排序落库用）。
     *
     * 为什么必须一次事务：事务内 Room 只在提交时发一次失效通知。
     * 逐条写（各自一个事务）= N 次发射 = N 次重排，整个列表会抖一下（0.1.8 的教训）。
     */
    @Transaction
    suspend fun applyOrderIndices(ids: List<String>, orderIndices: List<Double>) {
        ids.forEachIndexed { index, id -> updateOrderIndex(id, orderIndices[index]) }
    }

    /**
     * 一次性重写整棵子树的物化路径与层级。
     *
     * 这是选物化路径换来的核心收益：移动一棵子树只有**一条 UPDATE**，
     * 而不是逐个节点读改写。`substr(treePath, 前缀长度+1)` 取出路径中前缀之后的部分，
     * 再拼上新前缀。
     */
    @Query(
        """
        UPDATE items
        SET treePath = :newPrefix || substr(treePath, :oldPrefixLength + 1),
            depth = depth + :depthDelta
        WHERE treePath LIKE :oldPrefix || '%'
        """,
    )
    suspend fun rewriteSubtreePaths(
        oldPrefix: String,
        newPrefix: String,
        oldPrefixLength: Int,
        depthDelta: Int,
    )

    @Query("DELETE FROM items WHERE treePath LIKE :prefix || '%'")
    suspend fun deleteSubtree(prefix: String)

    /**
     * 取子树的 **id 列表**（而不是直接删）。
     *
     * 云同步要求删除可传播 ⇒ 删除改为软删：先要知道"要删哪些"，
     * 才能给它们逐个写墓碑（见 `RoomItemRepository.deleteSubtree`）。
     *
     * 与 [deleteSubtree] 的关系：那个是硬删（只在真正清理时才用，如墓碑过期回收），
     * 这个是软删的第一步。**日常删除路径一律走这个**。
     */
    @Query("SELECT id FROM items WHERE treePath LIKE :prefix || '%'")
    suspend fun findSubtreeIds(prefix: String): List<String>

    @Query("DELETE FROM items")
    suspend fun clearAll()
}
