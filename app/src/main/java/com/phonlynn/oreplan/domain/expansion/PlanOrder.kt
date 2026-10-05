package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind

/**
 * 同级条目的**唯一**排序规则。纯函数，便于把顺序钉在单测里。
 *
 * 为什么必须只有一份：同一层条目的顺序会被三处独立读到 ——
 *
 *  1. 领域层建树（[PlanTreeBuilder]）；
 *  2. 仓储读某一层的子节点（`RoomItemRepository.getChildren`），拖动落位要拿它算新排序键；
 *  3. 界面投影（[PlanOutline]），它决定了「看得见的第几行」。
 *
 * 三者一旦不一致，「可见第几行 → 库内第几项」的换算就会整体错位，症状是拖动落点错乱
 * 或者工作区顺序怎么拖都不对 —— 而这类错位在界面上极难定位。
 *
 * ## 工作区排在最前
 *
 * 规划页把工作区渲染成**分区卡片**、其余根条目渲染成「未分组」分区里的行，也就是
 * 「先工作区、后未分组」。规则里就必须带上这一条，否则库里的顺序（工作区与根目标按
 * 排序键交错）与界面顺序（工作区在前）不是同一个序列，拖动下标换算得出来的位置就是错的。
 */
object PlanOrder {

    /** 同级排序：工作区优先，其余按手动排序键、创建时间、标题。 */
    val sibling: Comparator<Item> = compareBy<Item>(
        { if (it.kind == ItemKind.WORKSPACE) 0 else 1 },
        { it.orderIndex },
        { it.createdAt },
        { it.title },
    )
}
