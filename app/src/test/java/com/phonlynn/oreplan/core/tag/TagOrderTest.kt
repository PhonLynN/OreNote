package com.phonlynn.oreplan.core.tag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 新建标签的排序键规则：永远追加到同组末尾。
 * 算错的表现是「新标签插到中间/顺序随机」，直接破坏设计稿里的标签顺序，钉在测试里。
 */
class TagOrderTest {

    @Test
    fun `空组从零开始`() {
        assertEquals(0, nextTagSortIndex(emptyList()))
    }

    @Test
    fun `取最大值加一追加到末尾`() {
        assertEquals(6, nextTagSortIndex(listOf(0, 5, 2)))
    }

    @Test
    fun `有重复值时仍追加到末尾`() {
        assertEquals(3, nextTagSortIndex(listOf(2, 2, 2)))
    }

    @Test
    fun `负数与无序输入也能正确追加`() {
        assertEquals(-1, nextTagSortIndex(listOf(-4, -8, -2)))
    }
}
