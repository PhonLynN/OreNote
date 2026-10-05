package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.core.order.DropIndex
import com.phonlynn.oreplan.core.order.OrderKeys
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.repository.ItemRepository
import java.time.Instant
import javax.inject.Inject

/**
 * 条目清单的拖动排序：把 [movedId] 放到「可见清单的第 [visibleIndex] 位」。
 *
 * ## 一份全局清单，两个界面共用
 *
 * 今日页的待办卡、日历日视图的待办卡、以及日历月/周视图下方的「当天清单」，都是**同一份手动顺序**
 * 的不同切片。所以这里只维护**一个**扁平清单：全部非容器的条目（日程与待办），按
 * （orderIndex → createdAt → title）排。界面上看到的往往只是其中一部分（日历某一天只列当天的
 * 几条、待办卡只列没做完的待办），所以先按「插到可见的邻居旁边」在完整清单里定位，
 * 再整体重编号 —— 直接按可见下标重编号会把不在这一屏的条目顺序一起写乱。
 *
 * ## 为什么整体重编号，而不是「算中点插进去」
 *
 * 老数据里所有条目的 orderIndex 都是默认值 0，中点算出来还是 0，落库后顺序不变 ——
 * 表现就是「拖了没反应」。条目数量是几十条量级，不值得为省这些 UPDATE 去引入一套容易出错的
 * 特例判断。
 *
 * 容器（目标 / 工作区）不在这份清单里：它们有层级，顺序归 `MoveItemUseCase` 管。
 */
class ReorderItemsUseCase @Inject constructor(
    private val itemRepository: ItemRepository,
) {

    /**
     * [visibleIds]：界面上这一刻的清单顺序，**不含被拖动的项**。
     * [visibleIndex]：落点，`0..visibleIds.size`，等于 size 表示放到最后。
     */
    suspend operator fun invoke(movedId: String, visibleIds: List<String>, visibleIndex: Int) {
        val items = itemRepository.getAll()
            .filter { it.kind == ItemKind.EVENT || it.kind == ItemKind.TASK }
            .filter { it.status != ItemStatus.CANCELLED }
            .sortedWith(order)
        if (items.none { it.id == movedId }) return

        val newOrder = DropIndex.reorderedIds(
            globalIds = items.map { it.id },
            visibleIds = visibleIds,
            movedId = movedId,
            visibleIndex = visibleIndex,
        )
        val fresh = OrderKeys.rebalanced(newOrder.size)
        val byId = items.associateBy { it.id }
        val now = Instant.now()
        newOrder.forEachIndexed { position, id ->
            byId[id]?.let { item ->
                itemRepository.update(item.copy(orderIndex = fresh[position], updatedAt = now))
            }
        }
    }

    /** 排序键必须与 `DayListOrder` 里条目那一段完全一致，否则拖动落点会错位。 */
    private val order = compareBy<Item>({ it.orderIndex }, { it.createdAt }, { it.title })
}
