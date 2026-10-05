package com.phonlynn.oreplan.core.todo

/**
 * 拖动布局需要知道的单行信息（当前可见的行）。
 *
 * [depth] 用来处理「放在组行之后」：那应当是**整个组之后**（与落库口径一致），
 * 而不是「组行紧后面」（那等于放进组里，两个口径不一致时松手会看到一次跳动）。
 */
data class TodoDragRowInfo(val id: String, val height: Float, val depth: Int)

/**
 * 拖动中的「让位」布局：算出每一行应该停在哪一个 y（容器坐标）。
 *
 * 为什么不用白板那套「交叠推挤」：
 * 白板是瀑布流，卡片之间有 rowGap，被拖卡片压过去时才能算出交叠量。待办列表是
 * **零间距**的——相邻两行的矩形一开始只是**相切**，交叠量恒为 0，所以那套公式
 * 在这条列表上永远推不出任何位移（用户 2026-09-26 实测：所有事项都停在原位）。
 * 列表该有的口径是「按落点重排草稿顺序，其余行按新顺序落位」：
 *
 *  1. 把被拖行从原位置摘掉，按落点插到新位置，得到**草稿顺序**；
 *  2. 其余行按草稿顺序累加各自行高，得到自己的目标 y —— 处在原位置与落点之间的行
 *     自然整体平移一个行高，也就是肉眼看到的「被挤开」；
 *  3. 被拖行本身不参与落位（它跟着指针走），但**仍占位**，这样其它行的位移量才对。
 *
 * 纯函数，无副作用，可直接单测。
 *
 * @param rows 当前可见行（渲染顺序，组内待办紧跟组行）
 * @param draggedId 被拖行的 id
 * @param target 当前落点；为 null（还没有落点）时返回空表，调用方回落到静态槽位
 * @param draggedLiveTop 被拖行的实时顶边 y（跟着指针走）
 * @return 每个可见行的目标 y；被拖行给出 [draggedLiveTop]。落点引用失效时返回空表。
 */
fun planTodoDragTargets(
    rows: List<TodoDragRowInfo>,
    draggedId: String,
    target: TodoDropTarget?,
    draggedLiveTop: Float,
): Map<String, Float> {
    if (target == null) return emptyMap()
    val dragged = rows.firstOrNull { it.id == draggedId } ?: return emptyMap()
    val rest = rows.filter { it.id != draggedId }
    var insertAt = when (target) {
        // 插到参照行之前
        is TodoDropTarget.Before -> rest.indexOfFirst { it.id == target.refId }
        // 插到参照行之后：若参照行是「组」（后面还跟着更深的后代），要跳过整棵子树，
        // 否则显示的位置（紧跟组行）与落库位置（整组之后）不一致，松手会跳一下。
        is TodoDropTarget.After -> {
            val refAt = rest.indexOfFirst { it.id == target.refId }
            if (refAt < 0) -1
            else {
                val refDepth = rest[refAt].depth
                var i = refAt + 1
                while (i < rest.size && rest[i].depth > refDepth) i++
                i
            }
        }
        // 放进组里 = 插到该组行之后（也就是它第一个子项的位置）
        is TodoDropTarget.Into -> rest.indexOfFirst { it.id == target.groupId }.let { if (it < 0) -1 else it + 1 }
    }
    // 落点引用的行已经不在可见列表里（刚被折叠/删除）→ 不动，等下一帧重新解析
    if (insertAt < 0) return emptyMap()

    val out = HashMap<String, Float>(rows.size)
    var y = 0f
    var placed = false
    rest.forEachIndexed { i, r ->
        if (!placed && i == insertAt) {
            y += dragged.height // 被拖行占位，但不写进结果
            placed = true
        }
        out[r.id] = y
        y += r.height
    }
    if (!placed) y += dragged.height

    out[draggedId] = draggedLiveTop
    return out
}
