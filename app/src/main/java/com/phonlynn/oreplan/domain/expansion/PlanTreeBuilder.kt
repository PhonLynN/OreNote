package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.PlanNode
import com.phonlynn.oreplan.domain.model.PlanTree

/**
 * 由条目列表构建规划树。纯函数，便于把层级规则钉在单测里。
 *
 * 三条规则值得说明：
 *
 * 1. **只有目标和待办能当根节点。** 根层级的日程属于日历/时间轴，如果再出现在规划页，
 *    同一条内容会在两个地方各出现一次，用户不知道改哪个才对。但日程**可以作为子节点**出现
 *    （挂在某个目标下面），那时它带时钟标记 —— 这样既不会重复，也不会让子项凭空消失。
 *
 * 2. **父节点不存在的条目会提升为根节点**，而不是被丢掉。`items.parentId` 上没建外键，
 *    所以数据被外部改坏时可能出现孤儿；孤儿应该可见，而不是静默消失。
 *
 * 3. **带环保护。** 树结构一旦成环，递归会直接栈溢出。用 visiting 集合兜住，
 *    遇到环时把该分支截断 —— 宁可少显示一层，也不能崩。
 *
 * 4. **待办（TASK）不进规划树**。待办是「不限时、随想随做」的清单，只在今日页呈现；
 *    规划页放的是纲要（目标与工作区）。两边都显示同一件事会让用户不知道从哪里改才对，
 *    所以规划页的「添加子项」也创建目标而不是待办。
 *
 * `totalCount` 数的是**树里实际有的节点**，不是传进来的行数。差别在于根层级的日程、
 * 成环而进不了树的条目都不会被渲染，把它们算进「共 N 项」会让计数与看到的内容对不上。
 *
 * 摊平成行、分成分区、算出拖动落点的下标空间，都是 [PlanOutline] 的活 ——
 * 树只负责层级规则，投影只负责「界面会看到什么」。
 */
object PlanTreeBuilder {

    fun build(items: List<Item>): PlanTree {
        if (items.isEmpty()) return PlanTree.Empty

        val progress = GoalProgress.rollup(items)
        val childrenOf = items.groupBy { it.parentId }
        val existingIds = items.mapTo(HashSet()) { it.id }

        val visiting = HashSet<String>()
        var nodeCount = 0

        fun toNode(item: Item): PlanNode {
            nodeCount++
            if (!visiting.add(item.id)) {
                // 成环了：这一层截断，不再往下走
                return PlanNode(item = item, progress = progress[item.id] ?: 0f, children = emptyList())
            }
            val children = childrenOf[item.id]
                .orEmpty()
                .filter { it.id != item.id }
                // 待办不进规划树（理由见类注释）
                .filter { it.kind != ItemKind.TASK }
                .sortedWith(nodeOrder)
                .map(::toNode)
            visiting.remove(item.id)
            return PlanNode(
                item = item,
                progress = progress[item.id] ?: 0f,
                children = children,
            )
        }

        val roots = items
            // 自我引用（parentId == 自己的 id）没有任何意义，当作没有父节点处理。
            // 否则它会被当成「父节点存在」，于是既不是根、也没人会渲染它 —— 直接消失。
            .filter { it.parentId == null || it.parentId == it.id || it.parentId !in existingIds }
            // 根层只放纲要：目标与工作区。待办与日程都不在规划页出现。
            .filter { it.kind == ItemKind.GOAL || it.kind == ItemKind.WORKSPACE }
            .sortedWith(nodeOrder)
            .map(::toNode)

        return PlanTree(roots = roots, totalCount = nodeCount)
    }

    private val nodeOrder: Comparator<Item> = PlanOrder.sibling
}
