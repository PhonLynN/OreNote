package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemStatus

/**
 * 目标进度汇总。纯函数，便于把规则钉死在单测里。
 *
 * 规则：
 *  - **有子节点**：进度 = 子节点进度均值，**不允许手填**。否则会出现
 *    「父目标显示 80%，但下面子任务全没做完」这种自相矛盾的状态。
 *  - **叶子节点**：有手填进度就用它，否则「已完成 = 1 / 其余 = 0」。
 *  - **已取消的子节点不计入分母** —— 取消一个子任务不应该拖低父目标。
 */
object GoalProgress {

    fun rollup(items: List<Item>): Map<String, Float> {
        if (items.isEmpty()) return emptyMap()

        val byId = items.associateBy { it.id }
        val childrenOf = items.groupBy { it.parentId }
        val memo = HashMap<String, Float>(items.size)
        val visiting = HashSet<String>()

        fun compute(id: String): Float {
            memo[id]?.let { return it }
            // 防御：数据结构被外部写坏时可能形成环，这里兜住，不让它变成栈溢出。
            if (!visiting.add(id)) return 0f

            val children = childrenOf[id]
                .orEmpty()
                .filter { it.id != id && it.status != ItemStatus.CANCELLED }

            val value = if (children.isEmpty()) {
                val item = byId[id]
                item?.progress ?: if (item?.status == ItemStatus.DONE) 1f else 0f
            } else {
                children.sumOf { compute(it.id).toDouble() }.toFloat() / children.size
            }

            visiting.remove(id)
            memo[id] = value.coerceIn(0f, 1f)
            return memo.getValue(id)
        }

        items.forEach { compute(it.id) }
        return memo.toMap()
    }
}
