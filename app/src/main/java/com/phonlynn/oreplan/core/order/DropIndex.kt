package com.phonlynn.oreplan.core.order

/**
 * 拖动落位时的下标换算。纯函数，便于把「看得见的第几行」与「库里的第几项」
 * 这两套下标钉在单测里。
 *
 * 为什么必须换算：界面上一行不一定都在同一个下标空间里。
 *
 *  - 规划树里**待办不进树**（`PlanTreeBuilder` 过滤掉了），所以「某个目标下看得见的子项」
 *    只是它全部同级项的一个**子序列**；
 *  - 今日页待办是**跨父节点的扁平清单**，而日历某一天的清单又只列当天到期的那几条。
 *
 * 拖动只知道自己落在「可见的第几行」，落库要的却是目标容器里的第几项。两者不换算就会写错
 * 条目 —— 症状是「拖了没反应」或者「旁边那项莫名其妙跳了位置」。
 */
object DropIndex {

    /**
     * 把「可见列表里的插入位置」换算成「同级完整列表里的插入位置」。
     *
     * [visibleIds] 必须是 [siblingIds] 的子序列（相对顺序一致），且**都已剔除被拖动的项**；
     * 返回值同样是剔除被拖动项之后的下标空间，正好可以喂给
     * `MoveItemUseCase(itemId, parentId, index)`。
     *
     * [visibleIndex] 的合法范围是 `0..visibleIds.size`，等于 size 表示「追加到最后」。
     * 「最后」按**最后一行可见项之后**算，而不是同级列表的末尾 —— 可见项后面可能还排着
     * 不显示的同级项（目标下面挂着的待办），直接取末尾会让被拖的项越过它们。
     */
    fun siblingIndexFor(
        visibleIds: List<String>,
        siblingIds: List<String>,
        visibleIndex: Int,
    ): Int {
        if (visibleIds.isEmpty()) return siblingIds.size
        val clamped = visibleIndex.coerceIn(0, visibleIds.size)
        return if (clamped < visibleIds.size) {
            siblingIds.indexOf(visibleIds[clamped]).let { if (it < 0) siblingIds.size else it }
        } else {
            siblingIds.indexOf(visibleIds.last()).let { if (it < 0) siblingIds.size else it + 1 }
        }
    }

    /**
     * 扁平清单的重排：把 [movedId] 放到「可见列表第 [visibleIndex] 位」，返回重排后的
     * **完整清单**。
     *
     * 与 [siblingIndexFor] 的分工：那个是「同一个父节点内部」的落位（交给 `MoveItemUseCase`
     * 算排序键），这个是「跨父节点的扁平清单」。（待办就是后者。）
     *
     * 为什么不直接按可见下标整体重编号：可见的往往只是完整清单的一部分 —— 在日历的某一天里
     * 拖动一条待办，如果按当天的可见顺序整体重编号，**不在这一屏**的待办顺序会一起乱掉。
     * 所以这里只在完整清单里做一次「插到目标邻居旁边」，其余项的相对顺序原样保留。
     */
    fun reorderedIds(
        globalIds: List<String>,
        visibleIds: List<String>,
        movedId: String,
        visibleIndex: Int,
    ): List<String> {
        val base = globalIds.filterNot { it == movedId }
        val visibleWithout = visibleIds.filterNot { it == movedId }
        val position = siblingIndexFor(visibleWithout, base, visibleIndex)
        return base.toMutableList().apply { add(position.coerceIn(0, size), movedId) }
    }
}
