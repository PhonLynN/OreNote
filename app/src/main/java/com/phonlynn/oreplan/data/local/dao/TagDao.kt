package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.ItemTagCrossRef
import com.phonlynn.oreplan.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun findAll(): List<TagEntity>

    @Query("SELECT * FROM item_tags")
    suspend fun findAllLinks(): List<ItemTagCrossRef>

    @Query("DELETE FROM item_tags")
    suspend fun clearAllLinks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 清掉某标签的全部条目关联（但不动条目本身）。
     *
     * 云同步下标签是**软删**，`item_tags` 是复合主键的子表、不参与同步，
     * 所以它的清理必须单独做：「删除标签绝不删除内容」这条 flomo 教训要成立，
     * 靠的就是这里只删关联、不碰 `items`。
     */
    @Query("DELETE FROM item_tags WHERE tagId = :tagId")
    suspend fun clearTagItems(tagId: String)

    @Query("DELETE FROM tags")
    suspend fun clearAll()

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN item_tags ON tags.id = item_tags.tagId
        WHERE item_tags.itemId = :itemId
        ORDER BY tags.name
        """,
    )
    fun observeTagsOf(itemId: String): Flow<List<TagEntity>>

    @Query("DELETE FROM item_tags WHERE itemId = :itemId")
    suspend fun clearItemTags(itemId: String)

    /** 先清后插实现「整体替换」。用 IGNORE 是因为重复的 (itemId, tagId) 无意义但不该报错。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemTags(refs: List<ItemTagCrossRef>)
}
