package com.phonlynn.oreplan.data.mapper

import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展抽屉的 JSON 编解码。
 *
 * 这里也是**针对抽屉本身**的通用测试，不绑定任何具体功能：
 * 抽屉的价值恰恰在于「以后加什么字段都不用改代码」。
 */
class ExtCodecTest {

    @Test
    fun `空抽屉编码成 null 而不是空对象`() {
        // 不留空壳：这样「表里有没有这一行」就等价于「有没有抽屉」
        assertNull(ExtCodec.encode(ExtMap.EMPTY))
    }

    @Test
    fun `三种基本类型往返一致`() {
        val original = ExtMap.EMPTY
            .putText("weather.sky", "多云")
            .putNum("mood.score", 4.0)
            .putFlag("mood.private", true)

        val back = ExtCodec.decode(ExtCodec.encode(original))

        assertEquals(original, back)
    }

    /** 整数要写成整数：`3` 比 `3.0` 更自然（人会看到，AI 也会读）。 */
    @Test
    fun `整数不写成小数形式`() {
        val json = ExtCodec.encode(ExtMap.EMPTY.putNum("mood.score", 3.0))!!
        assertTrue("实际输出：$json", json.contains("\"mood.score\":3"))
        assertTrue("实际输出：$json", !json.contains("3.0"))
    }

    @Test
    fun `小数保持小数`() {
        val json = ExtCodec.encode(ExtMap.EMPTY.putNum("weather.temp", 26.5))!!
        assertTrue("实际输出：$json", json.contains("26.5"))
    }

    /** 抽屉里是附属信息：脏数据不该让读取崩，认不出就当空抽屉。 */
    @Test
    fun `非法 JSON 降级为空抽屉`() {
        assertTrue(ExtCodec.decode("这不是 json").isEmpty)
        assertTrue(ExtCodec.decode("").isEmpty)
        assertTrue(ExtCodec.decode(null).isEmpty)
        assertTrue(ExtCodec.decode("   ").isEmpty)
    }

    /** JSON 里合法但不是对象的（数组/标量）同样降级，而不是抛异常。 */
    @Test
    fun `非对象 JSON 降级为空抽屉`() {
        assertTrue(ExtCodec.decode("[1,2,3]").isEmpty)
        assertTrue(ExtCodec.decode("42").isEmpty)
    }

    /**
     * 嵌套对象不在支持范围内，但**不能丢掉** ——
     * 安静地丢数据正是这一轮要根治的毛病（见 `data-structure-contract.md` 第一节）。
     */
    @Test
    fun `嵌套对象转成文本而不是丢弃`() {
        val back = ExtCodec.decode("""{"a":{"deep":1}}""")
        assertEquals(1, back.size)
        val value = back.get("a")
        assertTrue(value is ExtValue.Text)
        assertTrue((value as ExtValue.Text).value.contains("deep"))
    }

    @Test
    fun `JSON null 值被跳过`() {
        val back = ExtCodec.decode("""{"a":null,"b":"ok"}""")
        assertNull(back.get("a"))
        assertEquals("ok", back.text("b"))
    }

    /**
     * 解码必须**放宽**：库里可能存着系统保留键（下划线开头），
     * 读取时不能因为「业务不许写」就把它丢掉。
     * 严格的校验只发生在写入侧（`ExtMap.put`）。
     */
    @Test
    fun `解码保留系统保留键`() {
        val back = ExtCodec.decode("""{"_sys.v":7,"mood.score":3}""")
        assertEquals(2, back.size)
        assertEquals(7.0, back.num("_sys.v")!!, 0.0001)
    }

    /** 编码顺序必须稳定（按键排序），否则字节级比对会随机失败。 */
    @Test
    fun `编码输出顺序稳定`() {
        val a = ExtMap.EMPTY.putText("z", "1").putText("a", "2")
        val b = ExtMap.EMPTY.putText("a", "2").putText("z", "1")
        assertEquals(ExtCodec.encode(a), ExtCodec.encode(b))
    }

    /** 任意键名都能安全往返 —— 这是「加新功能零迁移」的直接体现。 */
    @Test
    fun `任意键名都能往返`() {
        val keys = listOf(
            "weather.sky",
            "mood.score",
            "whiteboard.linkedCardId",
            "focus.lastSessionId",
            "a.very.long.namespaced.key.name",
            "中文键.也可以",
        )
        var map = ExtMap.EMPTY
        keys.forEachIndexed { index, key -> map = map.putText(key, "v$index") }

        val back = ExtCodec.decode(ExtCodec.encode(map))

        assertEquals(keys.size, back.size)
        keys.forEachIndexed { index, key -> assertEquals("v$index", back.text(key)) }
    }
}
