package com.phonlynn.oreplan.core.todo

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待办页分组树的装配。
 *
 * 盯五件事（都是「错了用户会立刻看见」的）：
 *  1. **嵌套顺序**：先分组块（深度优先），最后无组待办；
 *  2. **递归统计**：组头的「x 项 · y 未完成」要把子组里的待办也算进去；
 *  3. **折叠**：折叠的组只出行头，一条子行都不出；
 *  4. **归属失效**：指着已不存在的组（组被删了）的待办，必须当无组待办显示出来，不能凭空消失；
 *  5. **数据异常不崩**：自环 / 环路（parent 指回自己或后代）不能死循环。
 */
class TodoTreeTest {

    private val today = LocalDate.of(2026, 9, 26)


    @Test
    fun `组之间的环不会让内容消失（补到顶层显示）`() {
        // 脏数据：甲的父是乙、乙的父是甲。两个组都得出现在行序列里，组内待办也要在 ——
        // 宁可层级看着不对，也不能让内容不见（用户 2026-09-26 实测的组湮灭）。
        val rows = buildTodoRows(
            groups = listOf(group("A", "甲", parent = "B"), group("B", "乙", parent = "A")),
            tasks = listOf(task("t1", "甲里的", group = "A"), task("t2", "乙里的", group = "B")),
            collapsed = emptySet(),
        )
        val groupTitles = rows.filterIsInstance<TodoRow.GroupRow>().map { it.title }.sorted()
        assertEquals(listOf("乙", "甲"), groupTitles)
        val taskTitles = rows.filterIsInstance<TodoRow.TaskRow>().map { it.task.title }.sorted()
        assertEquals(listOf("乙里的", "甲里的"), taskTitles)
    }

    // 造数据必须与**真实写入路径**一致：编辑器保存待办组时走 Item.newChild，
    // 所以组的父组写在 parentId 上（groupId 只给待办用）。
    // 之前这里写的是 groupId，结果单测绿着、真机上嵌套永远展平 ——
    // 教训：单测造的数据要照抄生产代码的写法，不能凭印象。
    private fun group(
        id: String,
        title: String,
        parent: String? = null,
        order: Double = 0.0,
    ): Item = Item(
        id = id,
        kind = ItemKind.TODO_GROUP,
        title = title,
        parentId = parent,
        treePath = "/$id/",
        depth = 0,
        orderIndex = order,
        createdAt = Instant.ofEpochSecond(1_000),
        updatedAt = Instant.ofEpochSecond(1_000),
    )

    private fun task(
        id: String,
        title: String,
        group: String? = null,
        done: Boolean = false,
        priority: Int = 0,
        order: Double = 0.0,
    ): AgendaEntry = AgendaEntry(
        key = "task:$id",
        source = AgendaSource.ITEM,
        date = today,
        title = title,
        itemId = id,
        groupId = group,
        itemStatus = if (done) ItemStatus.DONE else ItemStatus.TODO,
        priority = priority,
        orderIndex = order,
    )

    @Test
    fun `组的嵌套读 parentId，与编辑器写入字段一致`() {
        // 给「嵌套永远展平」那个 bug 上的锁：装配必须读 parentId，
        // 因为编辑器保存组时走 Item.newChild —— 它写的就是 parentId。
        val rows = buildTodoRows(
            groups = listOf(
                group("g1", "学习"),
                group("g2", "数学", parent = "g1"),
                group("g3", "高数", parent = "g2"),
            ),
            tasks = emptyList(),
            collapsed = emptySet(),
        )
        val depths = rows.filterIsInstance<TodoRow.GroupRow>().associate { it.groupId to it.depth }
        assertEquals(mapOf("g1" to 0, "g2" to 1, "g3" to 2), depths)
    }

    @Test
    fun `分组在前、无组待办在后，嵌套深度逐级加一`() {
        val rows = buildTodoRows(
            groups = listOf(group("g1", "学习"), group("g2", "数学", parent = "g1")),
            tasks = listOf(
                task("t1", "线性代数", group = "g1"),
                task("t2", "高数", group = "g2"),
                task("t3", "无组的事"),
            ),
            collapsed = emptySet(),
        )

        val shape = rows.map { row ->
            when (row) {
                is TodoRow.GroupRow -> "G:${row.title}@${row.depth}"
                is TodoRow.TaskRow -> "T:${row.task.title}@${row.depth}"
            }
        }
        assertEquals(
            listOf("G:学习@0", "G:数学@1", "T:高数@2", "T:线性代数@1", "T:无组的事@0"),
            shape,
        )
    }

    @Test
    fun `组头统计递归包含子组`() {
        val rows = buildTodoRows(
            groups = listOf(group("g1", "学习"), group("g2", "数学", parent = "g1")),
            tasks = listOf(
                task("t1", "线性代数", group = "g1"),
                task("t2", "高数", group = "g2", done = true),
                task("t3", "线代作业", group = "g2"),
            ),
            collapsed = emptySet(),
        )
        val parent = rows.filterIsInstance<TodoRow.GroupRow>().first { it.groupId == "g1" }
        // 自己 1 条 + 子组 2 条；未完成 = 1 + 1
        assertEquals(3, parent.total)
        assertEquals(2, parent.undone)
        assertEquals("3 项 · 2 未完成", groupMetaText(parent.total, parent.undone))
        // 子组自己的统计只算自己的
        val child = rows.filterIsInstance<TodoRow.GroupRow>().first { it.groupId == "g2" }
        assertEquals(2, child.total)
        assertEquals(1, child.undone)
    }

    @Test
    fun `折叠的组只出行头，子组与待办都不出`() {
        val rows = buildTodoRows(
            groups = listOf(group("g1", "学习"), group("g2", "数学", parent = "g1")),
            tasks = listOf(task("t1", "线性代数", group = "g1"), task("t2", "高数", group = "g2")),
            collapsed = setOf("g1"),
        )
        val shape = rows.map { it.toString().substringBefore("@") }
        assertEquals(1, rows.size)
        assertTrue(rows.first() is TodoRow.GroupRow)
        val head = rows.first() as TodoRow.GroupRow
        assertEquals("g1", head.groupId)
        assertTrue(head.collapsed)
        // 统计仍然照算（折叠不影响数字）
        assertEquals(2, head.total)
        assertTrue(shape.isNotEmpty())
    }

    @Test
    fun `归属指向已不存在的组时按无组处理`() {
        val rows = buildTodoRows(
            groups = listOf(group("g1", "学习")),
            tasks = listOf(task("t1", "孤儿待办", group = "deleted-group")),
            collapsed = emptySet(),
        )
        val tasks = rows.filterIsInstance<TodoRow.TaskRow>()
        assertEquals(1, tasks.size)
        assertEquals(0, tasks.first().depth)
        assertEquals("孤儿待办", tasks.first().task.title)
    }

    @Test
    fun `层内待办按优先级高到低排，同优先级内按手动序号`() {
        // 口径合并（2026-09-30）：**先按优先级分档，档内按手动序号**。
        // 历史：09-24 用优先级排 → 09-26 改成纯手动序号（因为拖动被排序盖掉）→ 09-30 合并。
        // 合并的前提是"拖动被限制在同一档内"，见 TodoTree.todoTaskOrder 的注释。
        val rows = buildTodoRows(
            groups = listOf(group("g2", "生活", order = 2.0), group("g1", "学习", order = 1.0)),
            tasks = listOf(
                // 高优先级：手动序号(2.0)在低优先档之后，但**优先级说了算**，仍排最前。
                task("t1", "高优先", group = "g1", priority = 3, order = 2.0),
                // 低优先档三条：档内**只**由 orderIndex 决定 ⇒ 1.0 → 2.5 → 3.0。
                task("tA", "低优先档·第一", group = "g1", priority = 1, order = 1.0),
                task("tC", "低优先档·第三", group = "g1", priority = 1, order = 3.0),
                task("tB", "低优先档·第二", group = "g1", priority = 1, order = 2.5),
            ),
            collapsed = emptySet(),
        )
        val titles = rows.mapNotNull { (it as? TodoRow.TaskRow)?.task?.title }
        assertEquals(
            listOf("高优先", "低优先档·第一", "低优先档·第二", "低优先档·第三"),
            titles,
        )
        val groupTitles = rows.filterIsInstance<TodoRow.GroupRow>().map { it.title }
        assertEquals(listOf("学习", "生活"), groupTitles)
    }

    @Test
    fun `自环与环路不会死循环`() {
        // g2 的父是自己；g3 与 g4 互为父子 —— 两种异常数据都不能把统计拖进无限递归。
        val rows = buildTodoRows(
            groups = listOf(
                group("g1", "正常"),
                group("g2", "自环", parent = "g2"),
                group("g3", "环A", parent = "g4"),
                group("g4", "环B", parent = "g3"),
            ),
            tasks = listOf(
                task("t1", "正常里的", group = "g1"),
                task("t2", "自环里的", group = "g2"),
            ),
            collapsed = emptySet(),
        )
        val g1 = rows.filterIsInstance<TodoRow.GroupRow>().first { it.groupId == "g1" }
        assertEquals(1, g1.total)
        // 自环的组按「根的组」出现（parent 无效 → 当作顶层），统计仍然正常
        val g2 = rows.filterIsInstance<TodoRow.GroupRow>().first { it.groupId == "g2" }
        assertEquals(1, g2.total)
    }
}
