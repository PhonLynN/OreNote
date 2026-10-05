package com.phonlynn.oreplan.core.tag

/**
 * 标签排序：新建标签时取同组现有 sortIndex 的最大值 + 1，追加到同组末尾。
 * 纯函数，把这个「新标签永远在最后」的规则钉在单测里。
 */
fun nextTagSortIndex(siblingSortIndexes: List<Int>): Int =
    (siblingSortIndexes.maxOrNull() ?: -1) + 1
