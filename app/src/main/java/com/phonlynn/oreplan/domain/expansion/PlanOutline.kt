package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.PlanNode
import com.phonlynn.oreplan.domain.model.PlanTree

/**
 * 规划页的**投影模型**：一次遍历产出界面要渲染的全部数据，以及拖动落点要用的下标空间。
 *
 * ## 为什么要有这一层
 *
 * 改造前，同一棵规划树被三处各算一遍：
 *
 *  - `PlanTreeBuilder.flatten` 摊平出「全部可见行」；
 *  - `PlanViewModel` 再把树拆成工作区分区，各自摊平一遍；
 *  - `visibleRowIdsIn` 为了把「可见第几行」换算成「库内第几项」，**又照着界面的渲染顺序
 *    重算了一遍行序**。
 *
 * 最后那一份是最危险的：它必须与 `PlanScreen` 登记拖动槽位的顺序一字不差，而两者是
 * 完全独立的代码。任何一边改了顺序（多渲染一行、少渲染一行、分区先后调整），换算就整体
 * 错位，落点随之错乱 —— 而这类错误既没有编译期保护，在手机上也很难一眼看出来。
 *
 * 现在 [Outline.dropOrder] 同时供两边使用：界面按 [Block.rows] 渲染，拖动按下标空间换算，
 * 两者天生同源。
 *
 * ## 容器 id 的两个哨兵
 *
 * 拖动落点用「容器 id」表达，而库里根层级条目的 `parentId` 是 `null`。若直接把根层级
 * 叫 `null`，工作区（根层级）与「未分组」里的根目标会共用同一个下标空间 ——
 * 把未分组目标往上拖时，下标会算进工作区之间，落点莫名其妙。
 * 所以这里把它们拆成两个**不落库**的哨兵：
 *
 *  - [ROOT]：工作区之间排序的下标空间（一个工作区块就是一行）；
 *  - [UNGROUPED]：未分组分区里根目标的下标空间。
 *
 * 两者在 `PlanViewModel` 里都翻译回 `parentId = null`。
 */
object PlanOutline {

    /** 工作区之间排序的虚拟容器。不落库。 */
    const val ROOT: String = "plan:root"

    /** 「未分组」分区里根目标的虚拟容器。不落库。 */
    const val UNGROUPED: String = "plan:ungrouped"

    /** 一个分区：一个工作区（或虚拟的「未分组」）以及它下面可见的行。 */
    data class Block(
        /** 这一块在下标空间里的 id：工作区块就是工作区条目 id；未分组块是 [UNGROUPED]。 */
        val id: String,
        /** 这一块所在的下标空间：[ROOT]。 */
        val container: String,
        /** 工作区条目 id；未分组块为 null。 */
        val workspaceId: String?,
        val title: String,
        val virtual: Boolean,
        val rows: List<Row>,
        /** 这一块里的条目数（不含块自身的工作区标题、不含待办），不随展开状态变化。 */
        val nodeCount: Int,
    )

    /**
     * 一可见行。
     *
     * [dropContainer] 是这一行**作为放置位置**时使用的容器 id，也就是它的父容器。
     * 于是「拖到某一行旁边」天然就等于「插进它所在的那一层」，不需要界面再自己判断。
     */
    data class Row(
        val item: Item,
        /** 块内缩进层级，块的第一层是 0。 */
        val depth: Int,
        val dropContainer: String,
        val hasChildren: Boolean,
        val isExpanded: Boolean,
        val progress: Float,
    )

    /** 计数。三个数各有明确含义，界面不要互相混用。 */
    data class Counts(
        /** 顶层目标数 = 所有块里 depth 为 0 的目标行（工作区是分区标题，不计入）。 */
        val topLevelGoals: Int,
        /** 规划里的条目总数（含工作区、不含待办）。与展开状态无关。 */
        val totalNodes: Int,
        /** 当前实际渲染出来的行数。会随展开/折叠变化。 */
        val visibleRows: Int,
    )

    data class Outline(
        val blocks: List<Block>,
        /** 容器 id → 该容器下**可见行**的有序 id 列表。拖动落点的下标空间就是它。 */
        val dropOrder: Map<String, List<String>>,
        val counts: Counts,
        /** 建投影时用的那棵树。甘特图直接复用，不再重建一遍。 */
        val tree: PlanTree,
    ) {
        val isEmpty: Boolean get() = blocks.isEmpty()

        /** 某个容器下可见行的 id 列表。[container] 为 null 时按「未分组」处理。 */
        fun dropOrderOf(container: String?): List<String> =
            dropOrder[container ?: UNGROUPED].orEmpty()

        companion object {
            val Empty = Outline(
                blocks = emptyList(),
                dropOrder = emptyMap(),
                counts = Counts(topLevelGoals = 0, totalNodes = 0, visibleRows = 0),
                tree = PlanTree.Empty,
            )
        }
    }

    fun build(items: List<Item>, expanded: Set<String>): Outline {
        if (items.isEmpty()) return Outline.Empty

        // 树规则（根层只放目标与工作区、待办不进树、孤儿提升为根、环保护、同级按
        // PlanOrder 排序）全部复用 PlanTreeBuilder —— 那些规则已有单测钉住，不在这里重写。
        val tree: PlanTree = PlanTreeBuilder.build(items)
        if (tree.roots.isEmpty()) {
            return Outline.Empty.copy(counts = Counts(0, tree.totalCount, 0), tree = tree)
        }

        val workspaces = tree.roots.filter { it.item.kind == ItemKind.WORKSPACE }
        val ungrouped = tree.roots.filter { it.item.kind != ItemKind.WORKSPACE }

        val dropOrder = mutableMapOf<String, MutableList<String>>()
        val blocks = mutableListOf<Block>()

        workspaces.forEach { workspace ->
            // 工作区自己是一条**块**：它在下标空间里占一行（供工作区之间排序），
            // 但它不作为普通行渲染 —— 否则标题下面会多出一条与自己同名的行。
            dropOrder.getOrPut(ROOT) { mutableListOf() } += workspace.item.id
            blocks += Block(
                id = workspace.item.id,
                container = ROOT,
                workspaceId = workspace.item.id,
                title = workspace.item.title,
                virtual = false,
                rows = rowsOf(workspace.children, container = workspace.item.id, expanded = expanded),
                nodeCount = workspace.children.sumOf(::subtreeCount),
            )
        }

        if (ungrouped.isNotEmpty()) {
            blocks += Block(
                id = UNGROUPED,
                container = ROOT,
                workspaceId = null,
                title = "未分组",
                virtual = true,
                rows = rowsOf(ungrouped, container = UNGROUPED, expanded = expanded),
                nodeCount = ungrouped.sumOf(::subtreeCount),
            )
        }

        blocks.forEach { block ->
            block.rows.forEach { row ->
                dropOrder.getOrPut(row.dropContainer) { mutableListOf() } += row.item.id
            }
        }

        val counts = Counts(
            topLevelGoals = blocks.sumOf { block -> block.rows.count { it.depth == 0 } },
            totalNodes = tree.totalCount,
            visibleRows = blocks.sumOf { it.rows.size },
        )

        return Outline(
            blocks = blocks,
            // 冻结成不可变列表，避免调用方拿到内部可变集合
            dropOrder = dropOrder.mapValues { (_, ids) -> ids.toList() },
            counts = counts,
            tree = tree,
        )
    }

    /**
     * 默认展开的节点集合：所有**有子节点**的纲要节点。
     *
     * 提成独立函数是因为它不需要展开状态 —— ViewModel 在第一次拿到数据时用它初始化，
     * 从而避免「先建空树、再展开」的两步。
     *
     * 工作区不算在内：它是分区标题，它下面的行永远渲染，没有「展开」这回事。
     */
    fun allExpandableIds(items: List<Item>): Set<String> {
        val visible = items.filter { it.kind != ItemKind.TASK && it.kind != ItemKind.WORKSPACE }
        val hasChildren = visible
            .filter { it.id != it.parentId }
            .groupBy { it.parentId }
        return visible
            .filter { !hasChildren[it.id].isNullOrEmpty() }
            .mapTo(HashSet()) { it.id }
    }

    // ------------------------------------------------------------------ 内部

    private fun rowsOf(
        nodes: List<PlanNode>,
        container: String,
        expanded: Set<String>,
    ): List<Row> {
        val rows = mutableListOf<Row>()

        fun walk(node: PlanNode, depth: Int, dropContainer: String) {
            val isExpanded = node.item.id in expanded
            rows += Row(
                item = node.item,
                depth = depth,
                dropContainer = dropContainer,
                hasChildren = node.hasChildren,
                isExpanded = isExpanded,
                progress = node.progress,
            )
            if (isExpanded) {
                node.children.forEach { child -> walk(child, depth + 1, node.item.id) }
            }
        }

        nodes.forEach { node -> walk(node, 0, container) }
        return rows
    }

    /** 子树条目数（含自身）。用于分区的「N 项」，与展开状态无关。 */
    private fun subtreeCount(node: PlanNode): Int =
        1 + node.children.sumOf(::subtreeCount)
}
