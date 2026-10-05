package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.core.order.OrderKeys
import com.phonlynn.oreplan.core.tree.TreePath
import com.phonlynn.oreplan.domain.expansion.PlanOrder
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.repository.ItemRepository
import javax.inject.Inject

/** 改挂的结果。失败原因要能区分，界面才能给出有用的提示，而不是笼统一句「操作失败」。 */
sealed interface MoveItemResult {
    data object Moved : MoveItemResult
    data object ItemNotFound : MoveItemResult
    data object ParentNotFound : MoveItemResult
    data object WouldCreateCycle : MoveItemResult
}

/**
 * 把条目（连同整棵子树）改挂到新的父节点下，并可选地插入到同级的指定位置。
 *
 * 校验放在用例层，路径重写交给仓储作为一个事务完成 —— 路径重写要同时更新
 * treePath、depth、parentId 三处，分散到多处维护迟早会不一致。
 *
 * [index] 的语义是**插入后的同级下标**（0 = 最前，null = 追加到最后）。
 * 把「算排序键」放在这里而不是界面层：界面只知道「放在第几行」，而排序键要读同级的现有键，
 * 属于数据规则。落位到同一个缝隙太多次时，这里还会触发一次均匀重排。
 */
class MoveItemUseCase @Inject constructor(
    private val itemRepository: ItemRepository,
) {

    suspend operator fun invoke(
        itemId: String,
        newParentId: String?,
        index: Int? = null,
    ): MoveItemResult {
        val item = itemRepository.getById(itemId) ?: return MoveItemResult.ItemNotFound

        val newParent = newParentId?.let { itemRepository.getById(it) }
        if (newParentId != null && newParent == null) return MoveItemResult.ParentNotFound

        // 唯一真正危险的操作：挂到自己或自己的后代下面，会让整棵子树从树上脱落。
        if (newParent != null && !TreePath.canReparent(item.treePath, newParent.treePath)) {
            return MoveItemResult.WouldCreateCycle
        }

        val orderIndex = index?.let { keyForIndex(itemId, newParentId, it) }

        // 父节点没变、又没要求改位置时才是真正的空操作
        if (item.parentId == newParentId && orderIndex == null) return MoveItemResult.Moved

        itemRepository.reparent(itemId, newParentId, orderIndex)
        return MoveItemResult.Moved
    }

    /**
     * 算出插到 [index] 位置时该用的排序键。
     *
     * 同级键已经挤到浮点精度以下时先做一次均匀重排 —— 否则中点会等于邻居，
     * 顺序变成「看数据库心情」。重排本身也是在仓储的同级事务里做的。
     */
    private suspend fun keyForIndex(itemId: String, parentId: String?, index: Int): Double {
        val keys = siblingsOf(itemId, parentId).map { it.orderIndex }
        val candidate = OrderKeys.keyForIndex(keys, index)

        // 关键判断：候选键必须**真的落在目标位置的邻居之间**，不能只看「有没有重复键」。
        // 老数据里同级键全是默认值 0，往最前面插算出的中点也是 0，落库后顺序不变 ——
        // 表现就是「拖了没反应」。这种时候先做一次均匀重排再算。
        val clamped = index.coerceIn(0, keys.size)
        val before = keys.getOrNull(clamped - 1)
        val after = keys.getOrNull(clamped)
        val collides = (before != null && candidate <= before) ||
            (after != null && candidate >= after)

        if (!collides && !OrderKeys.needsRebalance(keys)) return candidate

        val renormalizedKeys = itemRepository.renormalizeSiblings(parentId)
            .filter { it.id != itemId }
            .map { it.orderIndex }
        return OrderKeys.keyForIndex(renormalizedKeys, index)
    }

    /** 同级节点（不含自己），按现有显示顺序。 */
    private suspend fun siblingsOf(itemId: String, parentId: String?): List<Item> =
        itemRepository.getChildren(parentId)
            .filter { it.id != itemId }
            .sortedWith(PlanOrder.sibling)
}
