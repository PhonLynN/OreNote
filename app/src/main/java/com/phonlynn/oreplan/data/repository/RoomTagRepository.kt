package com.phonlynn.oreplan.data.repository

import androidx.room.withTransaction
import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.dao.TagDao
import com.phonlynn.oreplan.data.local.entity.ItemTagCrossRef
import com.phonlynn.oreplan.data.local.entity.TagEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.Tag
import com.phonlynn.oreplan.domain.repository.TagRepository
import com.phonlynn.oreplan.domain.sync.SyncEntity
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.TombstoneRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTagRepository @Inject constructor(
    private val database: AppDatabase,
    private val tagDao: TagDao,
    private val tombstones: TombstoneRegistry,
    private val stampWriter: SyncStampWriter,
) : TagRepository {

    private fun List<TagEntity>.visible(): List<Tag> =
        tombstones.filter(SyncEntity.TAG, this) { it.id }.map(TagEntity::toDomain)

    override fun observeTags(): Flow<List<Tag>> =
        tombstones.observing(SyncEntity.TAG, tagDao.observeTags())
            .map { rows -> rows.visible() }

    /**
     * 某条目身上的标签。⚠️ **同时要排除已删标签本身** ——
     * 标签软删后关联行（`item_tags`，复合主键、不参与同步）还在，
     * 不过滤就会在条目的标签列表里显示一个已经删掉的标签。
     */
    override fun observeTagsOf(itemId: String): Flow<List<Tag>> =
        tombstones.observing(SyncEntity.TAG, tagDao.observeTagsOf(itemId))
            .map { rows -> rows.visible() }

        /** 物理全量（含墓碑）。同步打包用 —— 见接口注释。 */
    override suspend fun allTagsIncludingDeleted(): List<Tag> =
        tagDao.findAll().map(TagEntity::toDomain)

override suspend fun upsert(tag: Tag) {
        tagDao.upsert(tag.toEntity())
        stampWriter.stampModified(SyncEntity.TAG, tag.id)
    }

    /**
     * 删除标签 —— **软删**。
     *
     * 用户已确认的 flomo 教训第 1 条「删除标签绝不删除内容」仍成立：
     * 这里只给标签本身打墓碑，`item_tags` 关联行清掉（子表、硬删），
     * 条目一条都不会少。
     */
    override suspend fun delete(tagId: String) {
        database.withTransaction {
            stampWriter.stampDeleted(SyncEntity.TAG, tagId)
            tagDao.clearTagItems(tagId)
        }
        tombstones.markDeleted(SyncEntity.TAG, tagId)
    }

    /** 先清后插实现整体替换，放在一个事务里以避免中途失败留下「半个标签集合」。 */
    override suspend fun setTagsOf(itemId: String, tagIds: List<String>) = database.withTransaction {
        tagDao.clearItemTags(itemId)
        if (tagIds.isNotEmpty()) {
            tagDao.insertItemTags(tagIds.distinct().map { ItemTagCrossRef(itemId = itemId, tagId = it) })
        }
    }
}
