package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.ChecklistRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * 删除条目的连带清理。
 *
 * 这一条以前是漏的：只删 `items`，而 `checklist_entries` 与 `attachments` 都没有外键约束，
 * 于是留下指向已删条目的死行；更麻烦的是启动时的孤儿清理把「库里任何附件行」当作有效集合，
 * 那些死行会让**附件文件永远清不掉**。
 */
class DeleteItemUseCaseTest {

    private val now: Instant = Instant.parse("2026-09-12T04:00:00Z")

    private fun goal(id: String, parent: Item? = null): Item =
        if (parent == null) {
            Item.newRoot(kind = ItemKind.GOAL, title = id, now = now, id = id)
        } else {
            Item.newChild(parent = parent, kind = ItemKind.GOAL, title = id, now = now, id = id)
        }

    private fun entry(id: String, itemId: String): ChecklistEntry = ChecklistEntry(
        id = id,
        itemId = itemId,
        category = com.phonlynn.oreplan.domain.model.ChecklistCategory.OTHER,
        title = id,
        done = false,
        orderIndex = 0.0,
        createdAt = now,
        updatedAt = now,
    )

    private fun attachment(id: String, ownerId: String): Attachment = Attachment(
        id = id,
        ownerType = AttachmentOwner.CHECKLIST,
        ownerId = ownerId,
        displayName = id,
        mimeType = "text/plain",
        sizeBytes = 1,
        storedPath = "attachments/$id",
        createdAt = now,
    )

    private fun reminder(id: String, itemId: String): Reminder = Reminder(
        id = id,
        itemId = itemId,
        triggerAt = now,
        offsetMinutes = 0,
        enabled = true,
    )

    private fun exception(id: String, itemId: String): RecurrenceException = RecurrenceException(
        id = id,
        itemId = itemId,
        date = java.time.LocalDate.of(2026, 9, 12),
        action = com.phonlynn.oreplan.domain.model.ExceptionAction.DELETED,
    )

    private class Items(private val rows: MutableList<Item>) : ItemRepository {
        var deletedSubtree: String? = null

        override fun observeAll(): Flow<List<Item>> = flowOf(rows.toList())
        override suspend fun getAll(): List<Item> = rows.toList()

        /** 测试里"物理全量"与"可见"是同一批（没有墓碑概念），显式声明意图。 */
        override suspend fun getAllIncludingDeleted(): List<Item> = rows.toList()

        override fun observeChildren(parentId: String?): Flow<List<Item>> = flowOf(emptyList())
        override suspend fun getChildren(parentId: String?): List<Item> = emptyList()
        override fun observeSubtree(rootId: String): Flow<List<Item>> = flowOf(emptyList())
        override fun observeScheduledBetween(since: Instant, until: Instant): Flow<List<Item>> =
            flowOf(emptyList())

        override fun observeDueBetween(since: Instant, until: Instant): Flow<List<Item>> =
            flowOf(emptyList())

        override suspend fun getById(id: String): Item? = rows.firstOrNull { it.id == id }

        override suspend fun create(item: Item): String {
            rows += item
            return item.id
        }

        override suspend fun update(item: Item) = Unit

        override suspend fun deleteSubtree(rootId: String) {
            deletedSubtree = rootId
            val root = rows.firstOrNull { it.id == rootId }
            rows.removeAll { root != null && it.treePath.startsWith(root.treePath) }
        }

        override suspend fun reparent(itemId: String, newParentId: String?, orderIndex: Double?) = Unit

        override suspend fun renormalizeSiblings(parentId: String?): List<Item> = emptyList()
        override suspend fun applyTodoOrder(changes: List<com.phonlynn.oreplan.domain.repository.TodoOrderChange>) = Unit
    }

    private class Checklists(private val rows: MutableList<ChecklistEntry>) : ChecklistRepository {
        override fun observeAll(): Flow<List<ChecklistEntry>> = flowOf(rows.toList())
        override fun observeOf(itemId: String): Flow<List<ChecklistEntry>> = flowOf(emptyList())
        override suspend fun getOf(itemId: String): List<ChecklistEntry> =
            rows.filter { it.itemId == itemId }

        override suspend fun getAll(): List<ChecklistEntry> = rows.toList()
        override suspend fun upsert(entry: ChecklistEntry) = Unit
        override suspend fun upsertAll(entries: List<ChecklistEntry>) = Unit
        override suspend fun delete(id: String) = Unit

        override suspend fun deleteOf(itemId: String) {
            rows.removeAll { it.itemId == itemId }
        }

        override suspend fun retainOnly(itemId: String, keepIds: List<String>) = Unit
    }

    private class Attachments(private val rows: MutableList<Attachment>) : AttachmentRepository {
        override fun observeAll(): Flow<List<Attachment>> = flowOf(rows.toList())
        override suspend fun getOf(ownerId: String): List<Attachment> =
            rows.filter { it.ownerId == ownerId }

        override suspend fun findById(id: String): Attachment? = rows.firstOrNull { it.id == id }
        override suspend fun getAll(): List<Attachment> = rows.toList()
        override suspend fun upsert(attachment: Attachment) = Unit
        override suspend fun upsertAll(attachments: List<Attachment>) = Unit
        override suspend fun delete(id: String) = Unit

        override suspend fun deleteOf(ownerId: String) {
            rows.removeAll { it.ownerId == ownerId }
        }

        override suspend fun retainOnly(ownerId: String, keepIds: List<String>) = Unit
    }

    /** 提醒的假仓储（只实现本用例用到的 `deleteOf`）。 */
    private class Reminders(private val rows: MutableList<Reminder>) :
        com.phonlynn.oreplan.domain.repository.ReminderRepository {
        override fun observeOf(itemId: String): Flow<List<Reminder>> =
            flowOf(rows.filter { it.itemId == itemId })

        override fun observeEnabled(): Flow<List<Reminder>> = flowOf(rows.filter { it.enabled })

        override suspend fun getEnabled(): List<Reminder> = rows.filter { it.enabled }

        override suspend fun upsert(reminder: Reminder) = Unit

        override suspend fun delete(reminderId: String) {
            rows.removeAll { it.id == reminderId }
        }

        override suspend fun deleteOf(itemId: String) {
            rows.removeAll { it.itemId == itemId }
        }

        /** 测试用：看看还剩哪些（模拟"库里剩下的行"）。 */
        fun remaining(): Set<String> = rows.map { it.id }.toSet()
    }

    /** 重复例外的假仓储。 */
    private class Exceptions(
        private val rows: MutableList<com.phonlynn.oreplan.domain.model.RecurrenceException>,
    ) : com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository {
        override fun observeOf(
            itemId: String,
        ): Flow<List<com.phonlynn.oreplan.domain.model.RecurrenceException>> =
            flowOf(rows.filter { it.itemId == itemId })

        override fun observeAll(): Flow<List<com.phonlynn.oreplan.domain.model.RecurrenceException>> =
            flowOf(rows.toList())

        override suspend fun getAll(): List<com.phonlynn.oreplan.domain.model.RecurrenceException> =
            rows.toList()

        override suspend fun getOf(
            itemId: String,
        ): List<com.phonlynn.oreplan.domain.model.RecurrenceException> =
            rows.filter { it.itemId == itemId }

        override suspend fun upsert(
            exception: com.phonlynn.oreplan.domain.model.RecurrenceException,
        ) = Unit

        override suspend fun deleteOccurrence(itemId: String, date: java.time.LocalDate) = Unit

        override suspend fun deleteOf(itemId: String) {
            rows.removeAll { it.itemId == itemId }
        }

        fun remaining(): Set<String> = rows.map { it.id }.toSet()
    }

    @Test
    fun `删除子树时清掉清单行`() = kotlinx.coroutines.runBlocking {
        val parent = goal("p")
        val child = goal("c", parent = parent)
        val sibling = goal("s")

        val items = Items(mutableListOf(parent, child, sibling))
        val checklists = Checklists(
            mutableListOf(
                entry("p1", "p"),
                entry("c1", "c"),
                entry("s1", "s"),
            ),
        )

        DeleteItemUseCase(items, checklists, Reminders(mutableListOf()), Exceptions(mutableListOf()))("p")

        assertEquals("p", items.deletedSubtree)
        assertEquals(setOf("s1"), checklists.getAll().map { it.id }.toSet())
    }

    // ---------------------------------------------------------------- 孤儿提醒

    /**
     * ⚠️ **删除条目时必须一并删掉它的提醒**（用户 2026-10-03 报的
     * 「删除条目时也要删提醒（孤儿提醒会响）」）。
     *
     * ## 为什么"不会响"也得删
     *
     * `ReminderPlanner` 确实会跳过"条目已不存在"的提醒
     *（`itemsById[reminder.itemId] ?: return@mapNotNull null`），
     * 所以孤儿提醒**今天不会响**。但留下的行有三个真实代价：
     *
     * 1. **参与云同步** —— 会被传到别的设备，白占流量与体积
     * 2. **永久累积** —— 每次删条目留一批，没有任何路径能清掉
     * 3. **条目恢复后诈尸** —— 同步/回滚让同 id 的条目回来时，
     *    旧提醒立刻复活并开始响。那种"我明明删过它"的不确定性最难排查
     *
     * ## 与附件索引的对比（为什么提醒能删、附件不能）
     *
     * 提醒是**纯元数据**，删掉不丢用户内容，重建即可（可逆）；
     * 附件的二进制在磁盘上，索引行是它与 App 的唯一联系，
     * 删索引等于把用户文件交给清理器（**不可逆**）。
     *
     * 这两条测试合起来就是那条界线的守卫。
     */
    @Test
    fun `删除子树时清掉提醒`() = kotlinx.coroutines.runBlocking {
        val parent = goal("p")
        val child = goal("c", parent = parent)
        val sibling = goal("s")

        val items = Items(mutableListOf(parent, child, sibling))
        val reminders = Reminders(
            mutableListOf(
                reminder("r-p", "p"),
                reminder("r-c", "c"),
                reminder("r-s", "s"),
            ),
        )

        DeleteItemUseCase(items, Checklists(mutableListOf()), reminders, Exceptions(mutableListOf()))("p")

        assertEquals(
            "被删子树上的提醒必须一并清掉（否则会累积、会同步、恢复条目后会诈尸）",
            setOf("r-s"),
            reminders.remaining(),
        )
    }

    /** 重复例外同理：它也是没有外键、随条目生灭的子表。 */
    @Test
    fun `删除子树时清掉重复例外`() = kotlinx.coroutines.runBlocking {
        val parent = goal("p")
        val child = goal("c", parent = parent)
        val sibling = goal("s")

        val items = Items(mutableListOf(parent, child, sibling))
        val exceptions = Exceptions(
            mutableListOf(
                exception("e-p", "p"),
                exception("e-c", "c"),
                exception("e-s", "s"),
            ),
        )

        DeleteItemUseCase(items, Checklists(mutableListOf()), Reminders(mutableListOf()), exceptions)("p")

        assertEquals(
            "被删子树上的重复例外必须一并清掉",
            setOf("e-s"),
            exceptions.remaining(),
        )
    }

    /**
     * **云同步带来的关键回归**：软删路径**不得**再删附件索引行。
     *
     * 原先这里是 `attachmentRepository.deleteOf(...)`。改成软删后那样做会造成
     * 不可逆的数据丢失：
     *  ① 索引行没了 ⇒ 附件文件变成"孤儿" ⇒ 下次启动被 `AttachmentStorage.sweep` 从磁盘删掉；
     *  ② 而条目的墓碑仍会把"删除"同步到另一台设备 ⇒ 另一端点开附件，文件已经不在。
     *
     * 现在的口径：附件索引行与文件都留着，随宿主条目一起被墓碑过滤（界面看不见），
     * 等墓碑过期回收时再真正清理。这条测试就是那个口径的守卫。
     */
    @Test
    fun `软删不再删除附件索引行`() = kotlinx.coroutines.runBlocking {
        val parent = goal("p")
        val checklists = Checklists(mutableListOf(entry("p1", "p")))
        val attachments = Attachments(
            mutableListOf(
                attachment("a-p1", "p1"),
                attachment("a-other", "other"),
            ),
        )

        DeleteItemUseCase(
            Items(mutableListOf(parent)),
            checklists,
            Reminders(mutableListOf()),
            Exceptions(mutableListOf()),
        )("p")

        assertEquals(
            "附件索引行必须原样保留（否则文件会被孤儿清理删掉）",
            setOf("a-p1", "a-other"),
            attachments.getAll().map { it.id }.toSet(),
        )
    }

    @Test
    fun `根不存在时什么都不做`() = kotlinx.coroutines.runBlocking {
        val items = Items(mutableListOf(goal("p")))
        val checklists = Checklists(mutableListOf(entry("p1", "p")))
        val reminders = Reminders(mutableListOf(reminder("r-p", "p")))

        DeleteItemUseCase(items, checklists, reminders, Exceptions(mutableListOf()))("missing")

        assertEquals(null, items.deletedSubtree)
        assertEquals(1, checklists.getAll().size)
        assertEquals("根都不存在，什么都不该删", 1, reminders.remaining().size)
    }
}
