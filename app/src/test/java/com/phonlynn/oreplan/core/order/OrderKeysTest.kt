package com.phonlynn.oreplan.core.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排序键的边界。
 *
 * 这些看起来是「一行的数学」，但它决定拖动排序落位准不准 ——
 * 算错一次的表现是「拖完又弹回原位」，靠肉眼在手机上几乎查不出原因，
 * 所以规则必须在纯 JVM 测试里钉死。
 */
class OrderKeysTest {

    private val epsilon = 1e-9

    @Test
    fun `空列表追加得到默认间隔`() {
        assertEquals(OrderKeys.GAP, OrderKeys.forAppend(null), epsilon)
    }

    @Test
    fun `追加总是排在最后`() {
        val first = OrderKeys.forAppend(null)
        val second = OrderKeys.forAppend(first)
        assertTrue(second > first)
        assertEquals(first + OrderKeys.GAP, second, epsilon)
    }

    @Test
    fun `插到最前面取后半段的一半`() {
        val key = OrderKeys.between(before = null, after = 1024.0)
        assertEquals(512.0, key, epsilon)
    }

    @Test
    fun `插到最后面往后加一个间隔`() {
        val key = OrderKeys.between(before = 1024.0, after = null)
        assertEquals(1024.0 + OrderKeys.GAP, key, epsilon)
    }

    @Test
    fun `插到中间取中点`() {
        val key = OrderKeys.between(before = 1024.0, after = 2048.0)
        assertEquals(1536.0, key, epsilon)
    }

    @Test
    fun `没有任何邻居时取默认值`() {
        assertEquals(OrderKeys.GAP, OrderKeys.between(null, null), epsilon)
    }

    @Test
    fun `按下标取键 头 中 尾 都对`() {
        val keys = listOf(1024.0, 2048.0, 3072.0)

        // 头部：插在 0 号之前
        assertEquals(512.0, OrderKeys.keyForIndex(keys, 0), epsilon)
        // 中间：插在 0 号与 1 号之间
        assertEquals(1536.0, OrderKeys.keyForIndex(keys, 1), epsilon)
        // 尾部：插在 2 号之后
        assertEquals(3072.0 + OrderKeys.GAP, OrderKeys.keyForIndex(keys, 3), epsilon)
    }

    @Test
    fun `下标越界被夹到合法范围而不是抛异常`() {
        val keys = listOf(1024.0, 2048.0)
        assertEquals(512.0, OrderKeys.keyForIndex(keys, -5), epsilon)
        assertEquals(2048.0 + OrderKeys.GAP, OrderKeys.keyForIndex(keys, 99), epsilon)
    }

    @Test
    fun `空列表取键得到默认值`() {
        assertEquals(OrderKeys.GAP, OrderKeys.keyForIndex(emptyList(), 0), epsilon)
    }

    @Test
    fun `间隔过小时要求重排`() {
        val tight = listOf(1024.0, 1024.0 + OrderKeys.MIN_GAP / 2)
        assertTrue(OrderKeys.needsRebalance(tight))
    }

    @Test
    fun `正常间隔不需要重排`() {
        assertFalse(OrderKeys.needsRebalance(listOf(1024.0, 2048.0, 3072.0)))
    }

    @Test
    fun `少于两项永远不需要重排`() {
        assertFalse(OrderKeys.needsRebalance(emptyList()))
        assertFalse(OrderKeys.needsRebalance(listOf(1024.0)))
    }

    @Test
    fun `无序输入也能判定重排`() {
        assertTrue(OrderKeys.needsRebalance(listOf(3072.0, 1024.0, 1024.0 + 1e-9)))
    }

    @Test
    fun `重排结果严格递增且留足间隔`() {
        val keys = OrderKeys.rebalanced(4)
        assertEquals(listOf(1024.0, 2048.0, 3072.0, 4096.0), keys)
        assertFalse(OrderKeys.needsRebalance(keys))
    }
}
