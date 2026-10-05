package com.phonlynn.oreplan.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展抽屉的值类型（[ExtMap]）。
 *
 * 这些测试**刻意针对「抽屉本身」而不是某个具体功能** —— 抽屉是给未来功能用的，
 * 不应该绑死在某一个用例上（否则抽屉就退化成「又一个专用字段」）。
 */
class ExtMapTest {

    @Test
    fun `空抽屉没有内容`() {
        assertTrue(ExtMap.EMPTY.isEmpty)
        assertEquals(0, ExtMap.EMPTY.size)
        assertNull(ExtMap.EMPTY.get("anything"))
    }

    @Test
    fun `三种基本类型都能存取`() {
        val map = ExtMap.EMPTY
            .putText("weather.sky", "晴")
            .putNum("weather.temp", 26.0)
            .putFlag("weather.rain", false)

        assertEquals("晴", map.text("weather.sky"))
        assertEquals(26.0, map.num("weather.temp")!!, 0.0001)
        assertEquals(false, map.flag("weather.rain"))
        assertEquals(3, map.size)
    }

    @Test
    fun `类型不匹配时返回 null 而不是抛异常`() {
        val map = ExtMap.EMPTY.putText("mood.note", "还不错")
        // 用数字去读一个文本键：读不到就是 null，不该崩
        assertNull(map.num("mood.note"))
        assertNull(map.flag("mood.note"))
        assertEquals("还不错", map.text("mood.note"))
    }

    @Test
    fun `同键重复写入是覆盖`() {
        val map = ExtMap.EMPTY.putText("k", "a").putText("k", "b")
        assertEquals(1, map.size)
        assertEquals("b", map.text("k"))
    }

    @Test
    fun `删除键`() {
        val map = ExtMap.EMPTY.putText("a", "1").putText("b", "2").remove("a")
        assertNull(map.text("a"))
        assertEquals("2", map.text("b"))
    }

    @Test
    fun `空键被拒绝`() {
        val failure = runCatching { ExtMap.EMPTY.putText("   ", "x") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    /** 下划线前缀是系统保留位（给以后的内务用途），业务键不许占用。 */
    @Test
    fun `下划线开头的键被拒绝`() {
        val failure = runCatching { ExtMap.EMPTY.putText("_internal", "x") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    /** 输出顺序必须稳定：否则「同样内容」会产生不同的字符串，测试与比对都会抖。 */
    @Test
    fun `排序后的条目顺序稳定`() {
        val map = ExtMap.EMPTY
            .putText("z.last", "1")
            .putText("a.first", "2")
            .putText("m.mid", "3")
        assertEquals(
            listOf("a.first", "m.mid", "z.last"),
            map.sortedEntries().map { it.first },
        )
    }

    /** 抽屉是值对象：改它必须产生新对象，不能就地改（否则 Compose 收不到变更）。 */
    @Test
    fun `修改返回新对象且不改动原对象`() {
        val original = ExtMap.EMPTY.putText("a", "1")
        val modified = original.putText("b", "2")
        assertEquals(1, original.size)
        assertEquals(2, modified.size)
    }
}
