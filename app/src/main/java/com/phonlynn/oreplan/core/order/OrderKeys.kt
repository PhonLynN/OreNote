package com.phonlynn.oreplan.core.order

/**
 * 同级排序键的计算。纯函数，便于把「插到两项之间」这类规则钉在单测里。
 *
 * 为什么用 Double 而不是整数下标：拖动排序时，若存的是「第几项」，插一次就要重写它后面
 * 所有行 —— 列表越长越慢，而且并发写容易错位。用 Double 只需改被移动的那一行：
 * 取相邻两项的中点即可。代价是反复往同一缝隙里插会耗尽浮点精度，所以配一个
 * [needsRebalance]，精度不够时对这一层做一次均匀重排。
 */
object OrderKeys {

    /** 均匀重排时相邻两项的间隔。取 1024 是为了给中间插入留足够多的次数。 */
    const val GAP = 1024.0

    /** 小于这个间隔就认为「插不进去了」，需要重排。 */
    const val MIN_GAP = 1e-6

    /** 追加到末尾。 */
    fun forAppend(last: Double?): Double = (last ?: 0.0) + GAP

    /**
     * 插到 [before] 与 [after] 之间。
     *
     * - 两边都没有 → 取一个默认间隔；
     * - 只有后面 → 取它的一半（插到最前面）；
     * - 只有前面 → 往后加一个间隔（插到最后面）；
     * - 两边都有 → 取中点。
     *
     * 结果出现 0 或与邻居相等时由调用方通过 [needsRebalance] 发现并重排。
     */
    fun between(before: Double?, after: Double?): Double = when {
        before == null && after == null -> GAP
        before == null -> after!! / 2.0
        after == null -> before + GAP
        else -> (before + after) / 2.0
    }

    /**
     * 按目标下标取一个新键。
     *
     * [keys] 是**当前**同级项的排序键（升序），[index] 是插入后的下标
     * （0 = 插到最前，keys.size = 追加到最后）。
     */
    fun keyForIndex(keys: List<Double>, index: Int): Double {
        val clamped = index.coerceIn(0, keys.size)
        return between(keys.getOrNull(clamped - 1), keys.getOrNull(clamped))
    }

    /** 相邻间隔是否已经小到插不进去。 */
    fun needsRebalance(keys: List<Double>): Boolean {
        if (keys.size < 2) return false
        val sorted = keys.sorted()
        if (sorted.any { !it.isFinite() }) return true
        return (1 until sorted.size).any { sorted[it] - sorted[it - 1] < MIN_GAP }
    }

    /** 均匀重排后的键，升序。 */
    fun rebalanced(count: Int): List<Double> = (1..count).map { it * GAP }
}
