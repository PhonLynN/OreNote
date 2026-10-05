package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.PlanNode
import com.phonlynn.oreplan.domain.model.PlanTree
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 甘特图的布局计算。纯函数，与绘制完全分离 —— 这样「父条自动包络子条」这类规则
 * 可以用普通单元测试钉住，而不是靠肉眼看截图。
 *
 * 关键规则：
 *
 * 1. **父条自动包络子条。** 父目标自己没填计划跨度时，直接取子节点的并集；
 *    填了就和子节点取并集。否则会出现「子任务拖到 3 月，父目标的条却停在 1 月」这种
 *    自相矛盾的画面，而且用户没有任何办法手动修好它。
 *
 * 2. **只填了一端就当作单日。** 起点有终点无 → 起点当天；反之同理。
 *    总比不画条更好 —— 不画条等于用户填了却看不见。
 *
 * 3. **完全没填跨度的节点不画条**，只显示标签。空条会让人以为「跨度是 0」。
 *
 * 4. **时间轴前后各留数年**（[RANGE_MARGIN_DAYS]）。界线不再贴着数据的首尾：
 *    拉宽之后可以随时左右拖去看更远的时间，而代价为零 —— 轨道层是虚拟化绘制。
 */
object GanttLayout {

    /**
     * 时间轴前后各留多少天。
     *
     * 留得宽是为了让「想看远一点」随时可行（开学时就看看期末考试周、学期末回头看看开学那段），
     * 而代价为零：轨道层是虚拟化绘制，可见范围之外的刻度与横条根本不进绘制。
     * 不做到「无限」是因为需要一个有限区间来定义可拖动的边界 ——
     * 无限滑动的画布会让人彻底失去「现在在哪」的参照。
     */
    private const val RANGE_MARGIN_DAYS = 1_825L

    /** 一条跨度都没有时的视窗宽度（居中的今天）。 */
    private const val FALLBACK_DAYS = 56

    data class Bar(
        val item: Item,
        val depth: Int,
        val progress: Float,
        /** 已包络子节点后的实际跨度。 */
        val start: LocalDate?,
        val end: LocalDate?,
        val hasChildren: Boolean,
        val isExpanded: Boolean,
    ) {
        val hasSpan: Boolean get() = start != null && end != null
    }

    data class Model(
        val bars: List<Bar>,
        val rangeStart: LocalDate,
        val rangeEnd: LocalDate,
    ) {
        val totalDays: Long
            get() = ChronoUnit.DAYS.between(rangeStart, rangeEnd).coerceAtLeast(1)
    }

    fun build(
        tree: PlanTree,
        expanded: Set<String>,
        today: LocalDate,
    ): Model {
        // 递归算每个节点的有效跨度（含子节点包络）
        fun spanOf(node: PlanNode): Pair<LocalDate, LocalDate>? {
            val own = ownSpanOf(node.item)
            val childSpans = node.children.mapNotNull(::spanOf)
            val all = buildList {
                if (own != null) add(own)
                addAll(childSpans)
            }
            if (all.isEmpty()) return null
            return all.minOf { it.first } to all.maxOf { it.second }
        }

        val spans = mutableMapOf<String, Pair<LocalDate, LocalDate>>()
        fun collect(node: PlanNode) {
            spanOf(node)?.let { spans[node.item.id] = it }
            node.children.forEach(::collect)
        }
        tree.roots.forEach(::collect)

        // 按展示顺序展开（尊重折叠状态）
        val bars = mutableListOf<Bar>()
        fun walk(node: PlanNode, depth: Int) {
            val span = spans[node.item.id]
            bars += Bar(
                item = node.item,
                depth = depth,
                progress = node.progress,
                start = span?.first,
                end = span?.second,
                hasChildren = node.hasChildren,
                isExpanded = node.item.id in expanded,
            )
            if (node.item.id in expanded) {
                node.children.forEach { child -> walk(child, depth + 1) }
            }
        }
        tree.roots.forEach { walk(it, 0) }

        val allSpans = spans.values
        val rangeStart: LocalDate
        val rangeEnd: LocalDate
        if (allSpans.isEmpty()) {
            rangeStart = today.minusDays((FALLBACK_DAYS / 2).toLong() + RANGE_MARGIN_DAYS)
            rangeEnd = today.plusDays((FALLBACK_DAYS / 2).toLong() + RANGE_MARGIN_DAYS)
        } else {
            val earliest = minOf(allSpans.minOf { it.first }, today)
            val latest = maxOf(allSpans.maxOf { it.second }, today)
            rangeStart = earliest.minusDays(RANGE_MARGIN_DAYS)
            rangeEnd = latest.plusDays(RANGE_MARGIN_DAYS)
        }

        return Model(bars = bars, rangeStart = rangeStart, rangeEnd = rangeEnd)
    }

    /** 节点自己的跨度，不含子节点。只填一端时当作单日。 */
    private fun ownSpanOf(item: Item): Pair<LocalDate, LocalDate>? {
        val start = item.planStartDay ?: return item.planEndDay?.let { it to it }
        val end = item.planEndDay ?: return start to start
        return if (end < start) end to start else start to end
    }

    /** 某个日期在视窗里的横向占比（0f..1f）。 */
    fun fractionOf(date: LocalDate, model: Model): Float {
        val offset = ChronoUnit.DAYS.between(model.rangeStart, date).toFloat()
        return (offset / model.totalDays).coerceIn(0f, 1f)
    }
}
