package com.phonlynn.oreplan.core.todo

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemStatus

/**
 * 待办页的一行：分组头 或 待办。
 *
 * 这是**纯数据**（不依赖 Compose / 数据库）：界面拿它直接渲染。
 * 树的形状、嵌套深度、递归统计、折叠都在 [buildTodoRows] 里一次算完 ——
 * 好处是这套装配逻辑能脱离界面单测（嵌套 / 折叠 / 统计 / 排序都能钉住）。
 */
sealed interface TodoRow {
    val depth: Int

    /** 分组头（设计稿 PpQVV：折叠箭头 + 组名/统计 + 组内加号）。 */
    data class GroupRow(
        val groupId: String,
        val title: String,
        /** 该组（**含子组**）内的待办总数。 */
        val total: Int,
        /** 其中未完成的条数。 */
        val undone: Int,
        /** 组内是否还有内容（待办或子组）—— 空组不画折叠箭头。 */
        val hasChildren: Boolean,
        val collapsed: Boolean,
        override val depth: Int,
    ) : TodoRow

    /** 一条待办。 */
    data class TaskRow(val task: AgendaEntry, override val depth: Int) : TodoRow
}

/** 分组头的统计文案（设计稿：「4 项 · 3 未完成」）。 */
fun groupMetaText(total: Int, undone: Int): String = "$total 项 · $undone 未完成"

/**
 * **待办在同一层里的顺序：优先级从高到低，同优先级按手动序号。**
 *
 * ## 为什么是这一条（两版口径的合并，2026-09-30）
 *
 * 这里是本项目**改过两次口径**的地方，改动前务必读完本段：
 *
 * | 时间 | 口径 | 结果 |
 * | --- | --- | --- |
 * | ~09-24 | 按 `priority` 排（今日页至今如此，见 `TodayV2ViewModel`） | 用户认可"高→低"这个方向 |
 * | 09-26 | **改成只按 `orderIndex`** | 因为当时"拖完看不到变化"—— 优先级的排序把拖动写的序号**盖掉了** |
 * | 09-30 | **合并**：先按优先级分档，档内按手动序号 | 用户要的是"高→低"，同时"同优先级内可随意重排" |
 *
 * 两次要求并不矛盾，**前提是拖动必须被限制在"同一优先级档"内**：
 *  - 档内：`orderIndex` 说了算 ⇒ 拖动**看得见效果**；
 *  - 跨档：优先级说了算 ⇒ 若允许把任务丢到别档的位置，落库后会被排序**弹回原位**，
 *    那就又变成 09-26 那个"拖了没反应"的 bug。
 *
 * ⇒ 所以**本文件只负责排序**；"不许跨档落点"那条约束在
 * `TodoDragDrop.resolveTodoDrop` 里（两处必须同口径，见下面 [todoTaskOrder] 的说明）。
 *
 * ⚠️ **这个比较器是唯一真值源**：`TodoDragDrop.planTodoDrop` 算插入下标时也用它。
 * 两处若用不同口径，插入位置会按错的序列去数 ⇒ 落点系统性偏移（不是偶发）。
 */
val todoTaskOrder: Comparator<AgendaEntry> = compareByDescending<AgendaEntry> { it.priority }
    .thenBy { it.orderIndex }
    .thenBy { it.createdAtMillis ?: 0L }
    .thenBy { it.title }

/**
 * 把「扁平的分组列表 + 扁平的待办列表」装配成待办页的行序列。
 *
 * 规则（对齐设计稿 PpQVV 与用户 2026-09-26 / 09-30 的要求）：
 *  - **先输出分组块（深度优先），最后输出无组待办**；
 *  - 同级组按 `orderIndex` 升序（其次 createdAt / title），保证顺序稳定；
 *  - 层内待办与无组待办都按 [todoTaskOrder]（**优先级高→低，档内手动序号**）；
 *  - 折叠的组只输出组头行，**不输出任何子行**（含子组）；
 *  - 组的统计**递归**包含子组里的待办；
 *  - 归属指向「不存在的组」的待办按**无组**处理：组被删掉后任务不该凭空消失。
 */
fun buildTodoRows(
    groups: List<Item>,
    tasks: List<AgendaEntry>,
    collapsed: Set<String>,
): List<TodoRow> {
    val groupIds = groups.mapTo(HashSet()) { it.id }
    // 自环与跨层异常都要挡住：环会让统计无限递归（爆栈），自环会让组挂在自己下面。
    // ⚠️ **字段分工**（读错这里 = 嵌套永远展平；2026-09-26 真的错过一次）：
    //  · 待办 → 归属组在 `groupId`；
    //  · 待办组 → 它的**父组在 `parentId`**（树形层级字段，与目标/工作区同机制；
    //    编辑器保存走 Item.newChild，写的正是 parentId）。
    // 读错字段的后果很隐蔽：所有组都被当成顶层组，看起来就是「嵌套还没做」。
    fun parentKeyOf(group: Item): String? =
        group.parentId?.takeIf { it in groupIds && it != group.id }

    val childGroups: Map<String?, List<Item>> = groups.groupBy { parentKeyOf(it) }
    val tasksByGroup: Map<String?, List<AgendaEntry>> = tasks.groupBy { entry ->
        entry.groupId?.takeIf { it in groupIds }
    }

    val groupOrder = compareBy<Item>({ it.orderIndex }, { it.createdAt }, { it.title })
    // 层内待办顺序：**优先级高→低，同档按手动序号**。定义在文件顶部的 [todoTaskOrder]，
    // 与 `TodoDragDrop.planTodoDrop` 共用同一份 —— 插入下标必须按同一条序列去数。
    val taskOrder = todoTaskOrder

    // 递归统计（含子组）。`seen` 是环保护：数据异常时退化为 0，不爆栈。
    fun counts(groupId: String, seen: MutableSet<String>): Pair<Int, Int> {
        if (!seen.add(groupId)) return 0 to 0
        val own = tasksByGroup[groupId].orEmpty()
        var total = own.size
        var undone = own.count { it.itemStatus != ItemStatus.DONE }
        childGroups[groupId].orEmpty().sortedWith(groupOrder).forEach { child ->
            val (t, u) = counts(child.id, seen)
            total += t
            undone += u
        }
        return total to undone
    }

    val out = ArrayList<TodoRow>(groups.size + tasks.size)
    /** 已输出的组 id：既用于去重，也是**数据成环时的防死循环闸门**。 */
    val emitted = HashSet<String>()

    fun emitGroup(group: Item, depth: Int) {
        // 已经输出过就不再输出：A 挂在 B 下、B 又挂在 A 下时（脏数据），
        // 没有这行闸门会无限递归；有它则整棵环只会被输出一次。
        if (!emitted.add(group.id)) return
        val children = childGroups[group.id].orEmpty().sortedWith(groupOrder)
        val own = tasksByGroup[group.id].orEmpty().sortedWith(taskOrder)
        val (total, undone) = counts(group.id, mutableSetOf())
        val isCollapsed = group.id in collapsed
        out += TodoRow.GroupRow(
            groupId = group.id,
            title = group.title,
            total = total,
            undone = undone,
            hasChildren = children.isNotEmpty() || own.isNotEmpty(),
            collapsed = isCollapsed,
            depth = depth,
        )
        if (isCollapsed) return
        children.forEach { emitGroup(it, depth + 1) }
        own.forEach { out += TodoRow.TaskRow(it, depth + 1) }
    }

    childGroups[null].orEmpty().sortedWith(groupOrder).forEach { emitGroup(it, 0) }
    tasksByGroup[null].orEmpty().sortedWith(taskOrder).forEach { out += TodoRow.TaskRow(it, 0) }

    // 兜底：数据异常（成环，或父组指向已删掉的组）会让某些组**既不是根、又到不了** ——
    // 那它们连同组内的待办就会整体从界面上消失（用户 2026-09-26 实测：组被拖进自己的后代后湮灭）。
    // 这里把**结构上到不了根**的组按顶层补出来：宁可层级看着不对，也不能让内容不见。
    //
    // ⚠️ 判据必须是「结构可达性」（不看折叠），不能用「有没有被输出过」：
    // 折叠的组本来就不会输出它的子组，用后者会把折叠的内容误当异常补到顶层
    //（这个错被「折叠的组只出行头」那条测试当场抓住）。
    val reachable = HashSet<String>()
    fun markReachable(group: Item) {
        if (!reachable.add(group.id)) return
        childGroups[group.id].orEmpty().forEach { markReachable(it) }
    }
    childGroups[null].orEmpty().forEach { markReachable(it) }
    val leftovers = groups.filterNot { it.id in reachable }
    if (leftovers.isNotEmpty()) {
        leftovers.sortedWith(groupOrder).forEach { emitGroup(it, 0) }
    }
    if (leftovers.isNotEmpty()) {
        leftovers.sortedWith(groupOrder).forEach { emitGroup(it, 0) }
    }
    return out
}
