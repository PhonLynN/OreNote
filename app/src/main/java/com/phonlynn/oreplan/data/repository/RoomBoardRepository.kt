package com.phonlynn.oreplan.data.repository

import androidx.room.withTransaction
import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.dao.BoardCardDao
import com.phonlynn.oreplan.data.local.dao.BoardCardLinkDao
import com.phonlynn.oreplan.data.local.dao.BoardCardTagDao
import com.phonlynn.oreplan.data.local.dao.BoardTagDao
import com.phonlynn.oreplan.data.local.dao.BoardTodoItemDao
import com.phonlynn.oreplan.data.local.entity.BoardCardEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardLinkEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTagEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardLink
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.domain.sync.SyncEntity
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.TombstoneRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomBoardRepository @Inject constructor(
    private val database: AppDatabase,
    private val cardDao: BoardCardDao,
    private val todoDao: BoardTodoItemDao,
    private val tagDao: BoardTagDao,
    private val cardTagDao: BoardCardTagDao,
    private val cardLinkDao: BoardCardLinkDao,
    private val tombstones: TombstoneRegistry,
    private val stampWriter: SyncStampWriter,
) : BoardRepository {

    /**
     * 读取过滤器：摘掉已软删的卡片 / 标签。
     *
     * 云同步要求删除可传播 ⇒ 删除变成软删（行还在，只是打了墓碑）。
     * `entity_ext` 是独立的表、没法参与这里的 SQL 过滤，所以在这一层按
     * [TombstoneRegistry] 的集合过滤。**每个读取出口都要过它** ——
     * 漏一处，删掉的卡片就会在某个视图里复活（见 `TombstoneFilterTest`）。
     */
    private fun List<BoardCardEntity>.visibleCards(): List<BoardCard> =
        tombstones.filter(SyncEntity.BOARD_CARD, this) { it.id }.map { it.toDomain() }

    private fun List<BoardTagEntity>.visibleTags(): List<BoardTag> =
        tombstones.filter(SyncEntity.BOARD_TAG, this) { it.id }.map { it.toDomain() }

    override fun observeCards(): Flow<List<BoardCard>> =
        tombstones.observing(SyncEntity.BOARD_CARD, cardDao.observeActive())
            .map { list -> list.visibleCards() }

    override fun observeArchivedCards(): Flow<List<BoardCard>> =
        tombstones.observing(SyncEntity.BOARD_CARD, cardDao.observeArchived())
            .map { list -> list.visibleCards() }

    override fun observeCard(id: String): Flow<BoardCard?> =
        cardDao.observeById(id).map { row ->
            if (row != null && tombstones.isDeleted(SyncEntity.BOARD_CARD, row.id)) null
            else row?.toDomain()
        }

    override fun observeTags(): Flow<List<BoardTag>> =
        tombstones.observing(SyncEntity.BOARD_TAG, tagDao.observeAll())
            .map { list -> list.visibleTags() }

    override fun observeTodoItems(): Flow<List<BoardTodoItem>> =
        todoDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 卡片↔标签关联。⚠️ 软删后**必须同时排除指向已删卡片的关联**。
     *
     * 卡片软删时其关联行会被硬删（见 [deleteCard]），但那只覆盖"经本仓库删除"这一条路径。
     * 真正的风险来自**同步**：远端删了一张卡片，本机收到后只打墓碑、不动关联行 ——
     * 于是这里会流出一批"指向不存在卡片"的关联，界面按 cardId 去查会拿到 null，
     * 表现为标签数量对不上、或某张卡片莫名带着一个空标签。
     */
    override fun observeCardTags(): Flow<List<BoardCardTag>> =
        cardTagDao.observeAll().map { list ->
            list.filterNot {
                tombstones.isDeleted(SyncEntity.BOARD_CARD, it.cardId) ||
                    tombstones.isDeleted(SyncEntity.BOARD_TAG, it.tagId)
            }.map { it.toDomain() }
        }

    override fun observeCardLinks(): Flow<List<BoardCardLink>> =
        cardLinkDao.observeAll().map { list ->
            list.filterNot {
                tombstones.isDeleted(SyncEntity.BOARD_CARD, it.cardId) ||
                    tombstones.isDeleted(SyncEntity.BOARD_CARD, it.linkedCardId)
            }.map {
                BoardCardLink(
                    cardId = it.cardId,
                    linkedCardId = it.linkedCardId,
                    createdAt = java.time.Instant.ofEpochMilli(it.createdAt),
                )
            }
        }

    override suspend fun setCardLinks(cardId: String, linkedCardIds: List<String>) {
        database.withTransaction {
            cardLinkDao.deleteByCard(cardId)
            if (linkedCardIds.isNotEmpty()) {
                val now = System.currentTimeMillis()
                cardLinkDao.insertAll(
                    linkedCardIds.map { BoardCardLinkEntity(cardId = cardId, linkedCardId = it, createdAt = now) },
                )
            }
        }
    }

    override suspend fun replaceCardWithRelations(
        card: BoardCard,
        todoItems: List<BoardTodoItem>?,
        tagIds: List<String>?,
    ) {
        // 名字里的 replace 是**真实语义**：先删后重建。两个关联参数为 null 时
        // **跳过**对应的删除/重建，代表「本次不改动该关联」——避免调用方漏传时
        // 静默清空标签/清单（历史事故见接口注释）。
        database.withTransaction {
            cardDao.upsert(card.toEntity())
            if (todoItems != null) {
                todoDao.deleteByCard(card.id)
                if (todoItems.isNotEmpty()) {
                    todoDao.upsertAll(todoItems.map { it.toEntity() })
                }
            }
            if (tagIds != null) {
                cardTagDao.deleteByCard(card.id)
                if (tagIds.isNotEmpty()) {
                    cardTagDao.insertAll(tagIds.map { BoardCardTagEntity(cardId = card.id, tagId = it) })
                }
            }
        }
    }

    /**
     * 删除卡片 —— **软删**（云同步前置，用户 2026-10-02）。
     *
     * 与硬删的差别：卡片行留下并打墓碑，界面靠 [visibleCards] 过滤掉它，
     * 于是「删除」这件事可以作为数据传到另一台设备。
     *
     * 卡片自己的**从属数据**（清单项 / 标签关联 / 卡片关联）仍是**硬删**：
     * 它们语义上必定随卡片一起生灭，没有"父卡还在、某个清单项单独被删还要传播"
     * 的场景（用户拍板的"只给顶层实体加墓碑"）。它们会在卡片被真正回收时一并消失。
     */
    override suspend fun deleteCard(id: String) {
        database.withTransaction {
            stampWriter.stampDeleted(SyncEntity.BOARD_CARD, id)
            todoDao.deleteByCard(id)
            cardTagDao.deleteByCard(id)
            cardLinkDao.deleteInvolving(id)
        }
        tombstones.markDeleted(SyncEntity.BOARD_CARD, id)
    }

    override suspend fun setCardArchived(id: String, archived: Boolean) {
        val card = cardDao.findById(id) ?: return
        cardDao.upsert(card.copy(archived = archived, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun setCardPinned(id: String, pinned: Boolean) {
        val card = cardDao.findById(id) ?: return
        cardDao.upsert(card.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun setCardAutoPinResolvedAt(id: String, at: java.time.Instant) {
        val card = cardDao.findById(id) ?: return
        // 归位记录不改 updatedAt：它记录的是「已处置本次浮起」，
        // 不是对卡片内容的编辑。改了会让「最近更新」时间失真。
        cardDao.upsert(card.copy(autoPinResolvedAt = at.toEpochMilli()))
    }

    override suspend fun setCardSecret(id: String, secret: Boolean) {
        val card = cardDao.findById(id) ?: return
        cardDao.upsert(card.copy(secret = secret, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun setCardBody(id: String, body: String?) {
        // 只更新正文：与 setCardSortIndex 同理，不能走 replaceCardWithRelations（会重建标签/待办）。
        val card = cardDao.findById(id) ?: return
        cardDao.upsert(card.copy(body = body, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun setCardTitleBody(id: String, title: String?, body: String?) {
        // 只更新标题/正文：同 [setCardBody]，不走 replaceCardWithRelations（会重建标签/待办）。
        val card = cardDao.findById(id) ?: return
        cardDao.upsert(
            card.copy(
                title = title,
                body = body,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun setCardSortIndex(id: String, sortIndex: Double) {
        // 只更新排序键：**不能走 replaceCardWithRelations**——后者会先删再重建该卡的标签/待办，
        // 传空列表会把它们清掉（曾是拖动排序清空标签的 bug 根源）。
        val card = cardDao.findById(id) ?: return
        cardDao.upsert(card.copy(sortIndex = sortIndex))
    }

    override suspend fun setCardSortIndices(updates: List<Pair<String, Double>>) {
        if (updates.isEmpty()) return
        // 单事务：Room 在事务提交后才通知观察者，因此整次拖动只发**一次** Flow，
        // 不会因逐张写入而触发多次重组（那是「松手后集体抖动」的一个原因）。
        database.withTransaction {
            updates.forEach { (id, sortIndex) ->
                val card = cardDao.findById(id) ?: return@forEach
                cardDao.upsert(card.copy(sortIndex = sortIndex))
            }
        }
    }

    override suspend fun toggleTodoItem(id: String, done: Boolean) {
        todoDao.setDone(id, done)
    }

    override suspend fun upsertTag(tag: BoardTag) {
        tagDao.upsert(tag.toEntity())
        stampWriter.stampModified(SyncEntity.BOARD_TAG, tag.id)
    }

    /**
     * 删除标签 —— **软删**。
     *
     * 「父标签删除时子标签自动升级」这条 flomo 教训仍要执行（用户已确认的规则），
     * 所以先改完子标签的 parentId，再给自己打墓碑。
     */
        /** 物理全量（含墓碑）。同步打包用 —— 见接口注释。 */
    override suspend fun allCardsIncludingDeleted(): List<BoardCard> =
        cardDao.findAll().map { it.toDomain() }

    /** 物理全量白板标签（含墓碑）。 */
    override suspend fun allBoardTagsIncludingDeleted(): List<BoardTag> =
        tagDao.listAll().map { it.toDomain() }

    override suspend fun deleteTag(id: String) {
        database.withTransaction {
            val tag = tagDao.listAll().firstOrNull { it.id == id }
            tagDao.reparentChildren(id = id, parentId = tag?.parentId)
            cardTagDao.deleteByTag(id)
            stampWriter.stampDeleted(SyncEntity.BOARD_TAG, id)
        }
        tombstones.markDeleted(SyncEntity.BOARD_TAG, id)
    }

    override suspend fun setCardTags(cardId: String, tagIds: List<String>) {
        database.withTransaction {
            cardTagDao.deleteByCard(cardId)
            if (tagIds.isNotEmpty()) {
                cardTagDao.insertAll(tagIds.map { BoardCardTagEntity(cardId = cardId, tagId = it) })
            }
        }
    }
}
