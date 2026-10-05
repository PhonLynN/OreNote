package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable

/** 规划树里的一个节点。进度已按 [com.phonlynn.oreplan.domain.expansion.GoalProgress] 汇总好。 */
@Immutable
data class PlanNode(
    val item: Item,
    val progress: Float,
    val children: List<PlanNode>,
) {
    val hasChildren: Boolean get() = children.isNotEmpty()
}

/** 规划树。 */
@Immutable
data class PlanTree(
    val roots: List<PlanNode>,
    /** 树里实际有的节点数（即会被渲染的行数），不是条目表的总行数。 */
    val totalCount: Int,
) {
    companion object {
        val Empty = PlanTree(emptyList(), 0)
    }
}
