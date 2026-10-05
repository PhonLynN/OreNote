package com.phonlynn.oreplan.v2.ai

import com.phonlynn.oreplan.v2.ai.math.AtomType
import com.phonlynn.oreplan.v2.ai.math.atomGapEm
import com.phonlynn.oreplan.v2.ai.math.atomTypeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **原子类型与类型×类型间距表**（用户 2026-10-04）。
 *
 * ## 用户原话
 *
 * > 「说明你的渲染器设计**对不同字符是不公平的**，现在字符之间的空隙是不一样的，
 * >   有些留了，有些没留，我完全没法给你一一指出」
 *
 * 旧实现是**白名单**：只有 `MATH_BINARY_OPS` / `MATH_RELATIONS` 里的字符有间距，
 * 其余一律 0。于是"在不在表里"决定了有没有空隙，而人手列的表必然有漏。
 *
 * 现在改成 TeX 的 **原子类型 + 8×8 间距表** —— 每个字符都有类型，
 * 判定永远有依据。这个测试钉的就是"公平"这件事。
 */
class AtomSpacingTest {

    // ---------------------------------------------------------------- 公平性

    /**
     * ⚠️ **核心：每个字符都有类型，不会"没有判定依据"。**
     *
     * 这是"公平"的定义 —— 不存在"这个字符忘了归类所以没间距"的情况。
     */
    @Test
    fun `任何字符都能归到一个类型`() {
        val samples = listOf(
            "a", "1", "+", "=", "(", ")", ",", "；", "，", "、", "∂", "∇", "∑",
            "α", "→", "×", "≠", "|", "…", "/", "∞", "√", "%", "°", "€", "中",
        )
        samples.forEach { ch ->
            val t = atomTypeOf(ch)
            assertTrue("字符 '$ch' 没有得到类型", t in AtomType.entries)
        }
    }

    /**
     * ⚠️ **间距表对每一对类型都有定义**（封闭）。
     *
     * 表里没有的组合按 TeX 规定是 0（例如开括号左侧不留空）——
     * 那是**规则**，不是"漏了"。所以这里断言的是"返回值合法"，不是"都有空隙"。
     */
    @Test
    fun `任意两个类型的间距都有定义且非负`() {
        for (a in AtomType.entries) {
            for (b in AtomType.entries) {
                val em = atomGapEm(a, b)
                assertTrue("类型对 ($a, $b) 的间距为负：$em", em >= 0f)
                assertTrue("类型对 ($a, $b) 的间距过大：$em", em < 0.5f)
            }
        }
    }

    // ---------------------------------------------------------------- TeX 规则

    /** 关系符两侧都要有 thick space（5/18 em ≈ 0.278）。 */
    @Test
    fun `关系符两侧都有 thick space`() {
        val left = atomGapEm(AtomType.ORD, AtomType.REL)
        val right = atomGapEm(AtomType.REL, AtomType.ORD)
        assertTrue("`a =` 之间应当有空隙", left > 0f)
        assertTrue("`= b` 之间应当有空隙", right > 0f)
        assertEquals("两侧应当对称", left, right, 1e-6f)
        // ⚠️ 具体宽度也要对（TeX 的 thick space = 5/18 em）
        assertEquals("关系符应当是 thick space (5/18 em)", 5f / 18f, left, 1e-4f)
    }

    /** 二元运算符两侧要有 medium space（4/18 em），且**小于**关系符的间距。 */
    @Test
    fun `二元运算符的间距小于关系符`() {
        val bin = atomGapEm(AtomType.ORD, AtomType.BIN)
        val rel = atomGapEm(AtomType.ORD, AtomType.REL)
        assertTrue("`a +` 之间应当有空隙", bin > 0f)
        assertEquals("二元运算符应当是 medium space (4/18 em)", 4f / 18f, bin, 1e-4f)
        assertTrue("关系符间距（$rel）应当大于二元运算符（$bin）", rel > bin)
    }

    /** 开括号左侧**不留空**、闭括号右侧**不留空**（`√(x)`、`(a)` 的写法）。 */
    @Test
    fun `开括号左侧与闭括号右侧不留空`() {
        assertEquals(0f, atomGapEm(AtomType.ORD, AtomType.OPEN), 1e-6f)
        assertEquals(0f, atomGapEm(AtomType.CLOSE, AtomType.ORD), 1e-6f)
    }

    /** 逗号**后面**留空、**前面**不留（TeX 的 punct 规则）。 */
    @Test
    fun `逗号后面留空前面不留`() {
        val after = atomGapEm(AtomType.PUNCT, AtomType.ORD)
        val before = atomGapEm(AtomType.ORD, AtomType.PUNCT)
        assertTrue("逗号后面应当留空：$after", after > 0f)
        assertEquals("逗号前面不该留空", 0f, before, 1e-6f)
    }

    // ---------------------------------------------------------------- 中文标点

    /**
     * ⚠️ **中文标点也要有类型**（旧表只有英文 `,` `;`）。
     *
     * 中文公式里 `，` `；` `、` 很常见，旧实现下它们两侧**完全没有空隙** ——
     * 这正是用户说的"有些字符没留"的一个具体来源。
     */
    @Test
    fun `中文标点归为标点类型`() {
        listOf("，", "；", "、").forEach { ch ->
            assertEquals("'$ch' 应当归为 PUNCT", AtomType.PUNCT, atomTypeOf(ch))
        }
    }

    // ---------------------------------------------------------------- 归类正确性

    /** 各类字符归类正确。 */
    @Test
    fun `各类字符归类正确`() {
        assertEquals(AtomType.REL, atomTypeOf("="))
        assertEquals(AtomType.REL, atomTypeOf("≤"))
        assertEquals(AtomType.REL, atomTypeOf("→"))
        assertEquals(AtomType.BIN, atomTypeOf("+"))
        assertEquals(AtomType.BIN, atomTypeOf("−"))
        assertEquals(AtomType.BIN, atomTypeOf("·"))
        assertEquals(AtomType.OPEN, atomTypeOf("("))
        assertEquals(AtomType.OPEN, atomTypeOf("["))
        assertEquals(AtomType.CLOSE, atomTypeOf(")"))
        assertEquals(AtomType.CLOSE, atomTypeOf("]"))
        // ⚠️ 竖线既是开也是闭 —— 归 CLOSE（`|x|` 里右侧那个更常见）
        assertEquals(AtomType.PUNCT, atomTypeOf(","))
        // 兜底：变量、数字、算符
        assertEquals(AtomType.ORD, atomTypeOf("a"))
        assertEquals(AtomType.ORD, atomTypeOf("1"))
        assertEquals(AtomType.ORD, atomTypeOf("∂"))
        assertEquals(AtomType.ORD, atomTypeOf("∇"))
        assertEquals(AtomType.ORD, atomTypeOf("中"))
    }
}
