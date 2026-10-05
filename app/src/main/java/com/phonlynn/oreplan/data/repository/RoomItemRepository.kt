package com.phonlynn.oreplan.data.repository

import androidx.room.withTransaction
import com.phonlynn.oreplan.core.order.OrderKeys
import com.phonlynn.oreplan.core.tree.TreePath
import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.dao.ItemDao
import com.phonlynn.oreplan.data.local.entity.ItemEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.expansion.PlanOrder
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.TodoOrderChange
import com.phonlynn.oreplan.domain.sync.SyncEntity
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.TombstoneRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomItemRepository @Inject constructor(
    private val database: AppDatabase,
    private val itemDao: ItemDao,
    private val tombstones: TombstoneRegistry,
    private val stampWriter: SyncStampWriter,
) : ItemRepository {

    /**
     * 统一的读取过滤器。
     *
     * ## 为什么每个出口都要过它
     *
     * 云同步要求删除可传播 ⇒ 删除变成**软删**（行还在，只是打了墓碑）。
     * `entity_ext` 是独立的表，没法参与这里的 SQL 过滤，
     * 所以只能在这一层按 [TombstoneRegistry] 的集合过滤。
     *
     * 漏掉任何一个出口，被删的东西就会在某个界面**复活** ——
     * 而且往往只在"删过这一类数据"的设备上复现，极难定位。
     * `TombstoneFilterTest` 是这一层的警报器。
     */
    private fun List<ItemEntity>.visible(): List<Item> =
        tombstones.filter(SyncEntity.ITEM, this) { it.id }.map(ItemEntity::toDomain)

    private fun ItemEntity?.visible(): Item? =
        tombstones.filterOne(SyncEntity.ITEM, this, this?.id ?: "")?.toDomain()

    /*
     * ⚠️ 下面每个 `observeXxx` 都套了 `tombstones.observing(...)`。
     *
     * 光写 `dao.observeXxx().map { it.visible() }` 是**不够的**：软删只往
     * `entity_ext` 写墓碑，主表一行没动，于是那个流再也不会发射，
     * `visible()` 也就没机会重跑 —— 界面在删除后不刷新（用户报过）。
     * 详见 `TombstoneRegistry.observing` 的注释。
     */

    override fun observeAll(): Flow<List<Item>> =
        tombstones.observing(SyncEntity.ITEM, itemDao.observeAll())
            .map { rows -> rows.visible() }

    override suspend fun getAll(): List<Item> = itemDao.findAll().visible()

    /** 物理全量（含墓碑）。只给删除路径与同步上传用 —— 见接口注释。 */
    override suspend fun getAllIncludingDeleted(): List<Item> =
        itemDao.findAll().map(ItemEntity::toDomain)

    override fun observeChildren(parentId: String?): Flow<List<Item>> =
        tombstones.observing(SyncEntity.ITEM, itemDao.observeChildren(parentId))
            .map { rows -> rows.visible() }

    override suspend fun getChildren(parentId: String?): List<Item> =
        itemDao.findChildren(parentId).visible()

    override fun observeSubtree(rootId: String): Flow<List<Item>> =
        tombstones.observing(SyncEntity.ITEM, itemDao.observeSubtree(rootId))
            .map { rows -> rows.visible() }

    override fun observeScheduledBetween(since: Instant, until: Instant): Flow<List<Item>> =
        tombstones.observing(
            SyncEntity.ITEM,
            itemDao.observeScheduledBetween(since.toEpochMilli(), until.toEpochMilli()),
        ).map { rows -> rows.visible() }

    override fun observeDueBetween(since: Instant, until: Instant): Flow<List<Item>> =
        tombstones.observing(
            SyncEntity.ITEM,
            itemDao.observeDueBetween(since.toEpochMilli(), until.toEpochMilli()),
        ).map { rows -> rows.visible() }

    override suspend fun getById(id: String): Item? = itemDao.findById(id).visible()

    override suspend fun create(item: Item): String {
        itemDao.upsert(item.toEntity())
        // 新建 = 一次本地修改：盖上 rev/hlc，让同步知道"这条本机是新的"。
        stampWriter.stampModified(SyncEntity.ITEM, item.id)
        return item.id
    }

    override suspend fun update(item: Item) {
        itemDao.upsert(item.toEntity())
        stampWriter.stampModified(SyncEntity.ITEM, item.id)
    }

    /**
     * 删除子树 —— **软删**（用户 2026-10-02 定为云同步前置）。
     *
     * 行为上与硬删等价：打上墓碑后，[visible] 会把整棵子树过滤掉，
     * 界面上立刻消失、数量统计也对。差别只在于**行还在表里**，
     * 于是删除这件事能作为数据传到另一台设备。
     *
     * ⚠️ 三件事必须在**同一个事务**里：
     *  ① 查子树 id、② 写墓碑（抽屉）、③ 更新内存登记处。
     * 顺序错了会出现「行删了但墓碑没写上」⇒ 下次同步它**复活**。
     */
    override suspend fun deleteSubtree(rootId: String) {
        val ids = database.withTransaction {
            val root = itemDao.findById(rootId) ?: return@withTransaction emptyList<String>()
            val subtreeIds = itemDao.findSubtreeIds(TreePath.subtreePrefix(root.treePath))
            stampWriter.stampDeletedAll(SyncEntity.ITEM, subtreeIds)
            subtreeIds
        }
        tombstones.markDeletedAll(SyncEntity.ITEM, ids)
    }

    /**
     * 改挂整棵子树。
     *
     * 放在一个事务里，是因为要同时改三处：`treePath`（整棵子树）、`depth`（整棵子树）、
     * `parentId`（只有子树的根）。这三个一旦不同步，树就会错位 ——
     * 分散到多个调用点维护迟早会不一致。
     *
     * [orderIndex] 不为 null 时在同一个事务里一并写：拖动落位是「换父 + 换位置」一件事，
     * 分两个事务会出现「位置写好了但还没换父」的中间态。
     */
    override suspend fun reparent(
        itemId: String,
        newParentId: String?,
        orderIndex: Double?,
    ): Unit = database.withTransaction {
        val item = itemDao.findById(itemId) ?: return@withTransaction
        val newParent = newParentId?.let { itemDao.findById(it) }

        // 不变量：不能把节点挂进**自己的子树**。
        // 一旦成环，物化路径自相矛盾（A 在 B 里、B 又在 A 里），整棵树既不是根、
        // 也到不了，于是从界面上整体消失（用户 2026-09-26 实测的「整个组湣灭」）。
        // 界面已经拦了这种落点，这里是数据层最后一道 —— 这类破坏落库后很难恢复。
        if (newParent != null && newParent.treePath.startsWith(item.treePath)) {
            return@withTransaction
        }

        val oldPrefix = item.treePath
        val newParentPath = newParent?.treePath ?: TreePath.SEPARATOR.toString()
        val newPrefix = newParentPath + TreePath.idOf(oldPrefix) + TreePath.SEPARATOR
        val newDepth = (newParent?.depth ?: -1) + 1
        val depthDelta = newDepth - item.depth

        itemDao.rewriteSubtreePaths(
            oldPrefix = oldPrefix,
            newPrefix = newPrefix,
            oldPrefixLength = oldPrefix.length,
            depthDelta = depthDelta,
        )
        itemDao.updateParent(itemId, newParentId)
        if (orderIndex != null) itemDao.updateOrderIndex(itemId, orderIndex)
    }

    override suspend fun renormalizeSiblings(parentId: String?): List<Item> =
        database.withTransaction {
            val rows = itemDao.findChildren(parentId)
                .map(ItemEntity::toDomain)
                .sortedWith(siblingOrder)
            val keys = OrderKeys.rebalanced(rows.size)
            rows.forEachIndexed { index, item ->
                itemDao.updateOrderIndex(item.id, keys[index])
            }
            rows.mapIndexed { index, item -> item.copy(orderIndex = keys[index]) }
        }

    /** 同级顺序：[PlanOrder.sibling]，与建树、界面投影必须是同一套。 */
    private val siblingOrder: Comparator<Item> = PlanOrder.sibling

    /**
     * 待办页拖动排序落库。
     *
     * 全程一个事务：序号批量写 + 换组（单列）+ 换父组（要重写物化路径，走 reparent）。
     * 事务内 Room 只在提交时发一次失效通知 → 界面只重排一次。
     */
    override suspend fun applyTodoOrder(changes: List<TodoOrderChange>) {
        if (changes.isEmpty()) return
        database.withTransaction {
            itemDao.applyOrderIndices(
                ids = changes.map { it.itemId },
                orderIndices = changes.map { it.orderIndex },
            )
            changes.forEach { change ->
                if (change.setGroup) itemDao.updateGroupId(change.itemId, change.groupId)
                // 组的父子关系不是单列：换父组要同步重写物化路径（深度 + 子树路径）。
                if (change.setParent) reparent(change.itemId, change.parentId, change.orderIndex)
            }
        }
    }
}
