package com.phonlynn.oreplan.core.todo

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.repository.TodoOrderChange

/**
 * 拖动判定用的**可见行几何**。
 *
 * 关键：这是**静态槽位**（与视觉位置无关）。拖动时行的视觉位置每帧在变，
 * 若拿视觉位置做判定，判定边界就会跟着动 —— 表现为卡片/行「抽搐」（0.1.9 的教训）。
 */
data class TodoDragSlot(
    /** 行的稳定标识：待办 = itemId，组 = groupId。 */
    val id: String,
    /** 行顶边在**列表容器坐标系**里的 y。 */
    val top: Float,
    val height: Float,
    val depth: Int,
    val isGroup: Boolean,
    /** 组是否折叠：折叠的组不接受「放进内部」（因为它里面没有可见行可以当锚点）。 */
    val collapsed: Boolean = false,
    /** 组：它自己 + 全部后代的 id（拒绝把组拖进自己/后代，会成环）。 */
    val subtreeIds: Set<String> = emptySet(),
)

/**
 * 落点。只保留三种形态 —— 它们都能被界面层的让位动画直接表达：
 *  · [Before] = 插到某一行**之前**（与它同层）；
 *  · [After] = 插到某一行**之后**（与它同层）；
 *  · [Into] = 放进某个组（成为它的第一个子项）—— 只用于**折叠的组或空组**：
 *    那时可见序列里找不到可作锚点的子行，「插到第 0 个子项之前」这句话无法用行来表达。
 *
 * 为什么不用「父层 + 下标」：折叠的组会让可见下标与真实下标不一致
 * （看不见的子行也算位置）→ 下标会算错。用「参考行 + 前后」就没有这个问题，
 * 具体的下标交给拿得到完整数据的 VM 去换算。
 */
sealed interface TodoDropTarget {
    data class Before(val refId: String) : TodoDropTarget
    data class After(val refId: String) : TodoDropTarget
    data class Into(val groupId: String) : TodoDropTarget
}

/** 解析结果。带上分区范围，供下一次调用做滞回。 */
data class TodoDropResolution(
    val target: TodoDropTarget,
    val zoneTop: Float,
    val zoneBottom: Float,
)

/**
 * 把一个指针 y 解析成落点。
 *
 * 分区规则（用户 2026-09-26 确认）：
 *  · 普通行：上半 → 插到它之前；下半 → 插到它之后；
 *  · **组行**：上 30% → 之前；**中 40% → 放进该组**；下 30% → 之后；
 *  · 列表最上方 → 插到第一行之前；最下方 → 插到最后一行之后。
 *
 * 滞回：上一次的分区外扩 [hysteresis] 后仍罩住指针 → **保持上一次的落点**。
 * 没有它，指针停在边界上时会来回翻转 → 让位动画反复改向 → 看起来就是抽搐。
 *
 * 返回 null 只在「没有任何可见行」时发生。
 */
fun resolveTodoDrop(
    slots: List<TodoDragSlot>,
    pointerY: Float,
    draggedId: String,
    draggedIsGroup: Boolean,
    draggedSubtreeIds: Set<String>,
    previous: TodoDropResolution? = null,
    hysteresis: Float = 14f,
): TodoDropResolution? {
    if (slots.isEmpty()) return null

    // 滞回优先：上一分区仍然罩得住，就不动。
    if (previous != null &&
        pointerY >= previous.zoneTop - hysteresis &&
        pointerY <= previous.zoneBottom + hysteresis
    ) {
        return previous
    }

    val first = slots.first()
    val last = slots.last()

    // 上/下越出：贴到首行之前 / 末行之后。
    if (pointerY < first.top) {
        return TodoDropResolution(TodoDropTarget.Before(first.id), Float.NEGATIVE_INFINITY, first.top).accepted(
            draggedId = draggedId,
            draggedIsGroup = draggedIsGroup,
            draggedSubtreeIds = draggedSubtreeIds,
        ) ?: previous
    }
    if (pointerY > last.top + last.height) {
        return TodoDropResolution(
            TodoDropTarget.After(last.id),
            last.top + last.height,
            Float.POSITIVE_INFINITY,
        ).accepted(draggedId, draggedIsGroup, draggedSubtreeIds) ?: previous
    }

    val row = slots.firstOrNull { pointerY >= it.top && pointerY <= it.top + it.height } ?: return previous
    val mid = row.top + row.height / 2f
    val upperEdge = row.top + row.height * 0.3f
    val lowerEdge = row.top + row.height * 0.7f

    val raw: TodoDropResolution = when {
        // 组的中间：放进它（折叠的组也接受 —— 放进去之后它的统计会 +1）
        row.isGroup && pointerY in upperEdge..lowerEdge ->
            TodoDropResolution(TodoDropTarget.Into(row.id), upperEdge, lowerEdge)

        pointerY < mid ->
            TodoDropResolution(TodoDropTarget.Before(row.id), row.top - row.height, mid)

        else ->
            TodoDropResolution(TodoDropTarget.After(row.id), mid, row.top + row.height * 2f)
    }

    return raw.accepted(draggedId, draggedIsGroup, draggedSubtreeIds) ?: previous
}

/**
 * 拒绝不合法的落点：
 *  · 组不能插到自己或自己的后代旁边（那一侧等价于「和我的后代同层」，会成环）；
 *  · 不能放到自己身上（Before/After 自己 = 原地不动，交给调用方忽略）。
 *
 * 返回 null 表示这个落点不成立，调用方保留上一个合法落点。
 */
private fun TodoDropResolution.accepted(
    draggedId: String,
    draggedIsGroup: Boolean,
    draggedSubtreeIds: Set<String>,
): TodoDropResolution? {
    val ref = when (val t = target) {
        is TodoDropTarget.Before -> t.refId
        is TodoDropTarget.After -> t.refId
        is TodoDropTarget.Into -> t.groupId
    }
    if (ref == draggedId) return null
    if (draggedIsGroup && ref in draggedSubtreeIds) return null
    return this
}

/**
 * 把落点翻译成**要写的改动**（纯函数，可单测）。
 *
 * 规则与界面层一致：每一层里「组在前、待办在后」，各自按 orderIndex 升序
 * （与 [buildTodoRows] 的呈现顺序同一套口径）。所以插入位置在**同类型**的序列里算，
 * 跨类型不会互相挤位（拖到组上方也不会把待办插到组前面去）。
 *
 * 序号按 [gap] 的整数倍重编号：一次写一整层，避免反复插入把两个键的中点压到浮点精度以下。
 */
fun planTodoDrop(
    groups: List<Item>,
    tasks: List<AgendaEntry>,
    draggedId: String,
    target: TodoDropTarget,
    gap: Double = 1.0,
): List<TodoOrderChange> {
    val draggedItem = groups.firstOrNull { it.id == draggedId }
    val draggedTask = tasks.firstOrNull { it.itemId == draggedId }
    if (draggedItem == null && draggedTask == null) return emptyList()
    val draggedIsGroup = draggedItem != null

    // 目标层（父组 id）与「同类型兄弟里的插入下标」
    val groupIds = groups.mapTo(HashSet()) { it.id }
    fun parentOfGroup(group: Item): String? = group.parentId?.takeIf { it in groupIds && it != group.id }

    val targetParent: String?
    val insertIndex: Int
    when (target) {
        is TodoDropTarget.Into -> {
            targetParent = target.groupId
            insertIndex = 0
        }

        is TodoDropTarget.Before, is TodoDropTarget.After -> {
            val refId = if (target is TodoDropTarget.Before) target.refId else (target as TodoDropTarget.After).refId
            val refGroup = groups.firstOrNull { it.id == refId }
            val refTask = tasks.firstOrNull { it.itemId == refId }
            if (refGroup == null && refTask == null) return emptyList()
            targetParent = if (refGroup != null) parentOfGroup(refGroup) else refTask!!.groupId?.takeIf { it in groupIds }
            val siblings: List<String> = if (draggedIsGroup) {
                groups.filter { parentOfGroup(it) == targetParent }
                    .sortedWith(compareBy({ it.orderIndex }, { it.createdAt }, { it.title }))
                    .map { it.id }
            } else {
                tasks.filter { it.groupId?.takeIf { g -> g in groupIds } == targetParent }
                    .sortedWith(taskSiblingOrder)
                    .map { it.itemId.orEmpty() }
            }
            val refIndex = siblings.indexOf(refId)
            if (refIndex < 0) return emptyList()
            // ⚠️ siblings 里**还包含被拖行自己**。下面会先把它摘掉再插回，所以若它原本
            // 排在参照行之前，参照行在「摘掉后」的序列里要往前挪一位 —— 漏了这一步，
            // 往下拖就会差一位（实测表现：松手后落错位置，甚至看着像回到原位）。
            val draggedAt = siblings.indexOf(draggedId)
            val adjustedRef = if (draggedAt >= 0 && draggedAt < refIndex) refIndex - 1 else refIndex
            insertIndex = if (target is TodoDropTarget.Before) adjustedRef else adjustedRef + 1
        }
    }

    val siblingsWithDragged: List<Pair<String, Boolean>> = if (draggedIsGroup) {
        groups.filter { parentOfGroup(it) == targetParent }
            .sortedWith(compareBy({ it.orderIndex }, { it.createdAt }, { it.title }))
            .map { it.id to true }
    } else {
        tasks.filter { it.groupId?.takeIf { g -> g in groupIds } == targetParent }
            .sortedWith(taskSiblingOrder)
            .map { it.itemId.orEmpty() to false }
    }

    val without = siblingsWithDragged.filterNot { it.first == draggedId }
    val at = insertIndex.coerceIn(0, without.size)
    val ordered = without.toMutableList().apply { add(at, draggedId to draggedIsGroup) }

    val movedAcrossLayer = if (draggedIsGroup) {
        parentOfGroup(draggedItem!!) != targetParent
    } else {
        draggedTask!!.groupId?.takeIf { it in groupIds } != targetParent
    }

    return ordered.mapIndexed { index, (id, isGroup) ->
        val isDragged = id == draggedId
        TodoOrderChange(
            itemId = id,
            orderIndex = (index + 1) * gap,
            groupId = if (isDragged && !isGroup && movedAcrossLayer) targetParent else null,
            setGroup = isDragged && !isGroup && movedAcrossLayer,
            parentId = if (isDragged && isGroup && movedAcrossLayer) targetParent else null,
            setParent = isDragged && isGroup && movedAcrossLayer,
        )
    }
}


/**
 * 待办在同一层里的顺序：**优先级高→低，同档按手动序号**。
 *
 * ⚠️ 直接用 [todoTaskOrder]，**不要在这里另写一份**：
 * 本函数算插入下标时要先排出一份"同层兄弟序列"，再用 `indexOf(refId)` 数位置。
 * 这份序列的口径**必须**与界面呈现（`buildTodoRows`）完全一致，
 * 否则下标是在**另一个顺序**里数出来的 ⇒ 落点系统性偏移（不是偶发，每次必错）。
 * 2026-09-30 之前两处各写一份（都是 orderIndex 口径，正好一致才没暴露）；
 * 引入优先级分档后两份必然分叉，所以合并成这一份。
 */
private val taskSiblingOrder: Comparator<AgendaEntry> = todoTaskOrder

/** 组在界面上的图标/统计用得到：是否是「可容纳子项」的组。 */
fun isTodoGroup(item: Item): Boolean = item.kind == ItemKind.TODO_GROUP
