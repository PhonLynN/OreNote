package com.phonlynn.oreplan.core.order

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拖动落位的下标换算。这类换算出错时症状很隐蔽（拖了没反应、旁边的项跳位），
 * 所以把边界一条条钉死。
 */
class DropIndexTest {

    // ------------------------------------------------------------ siblingIndexFor

    @Test
    fun `可见列表与同级列表一致时，下标原样返回`() {
        val ids = listOf("a", "b", "c")
        assertEquals(0, DropIndex.siblingIndexFor(ids, ids, 0))
        assertEquals(1, DropIndex.siblingIndexFor(ids, ids, 1))
        assertEquals(3, DropIndex.siblingIndexFor(ids, ids, 3))
    }

    @Test
    fun `可见列表是同级列表的子序列时按锚点定位`() {
        // 同级共 5 项，只有第 2、4 项看得见（另外三项在树里被过滤掉了）
        val sibling = listOf("a", "b", "c", "d", "e")
        val visible = listOf("b", "d")

        // 插到 b 之前 → 同级下标 1
        assertEquals(1, DropIndex.siblingIndexFor(visible, sibling, 0))
        // 插到 d 之前 → 同级下标 3
        assertEquals(3, DropIndex.siblingIndexFor(visible, sibling, 1))
        // 追加到 d 之后 → 同级下标 4，**不能**取末尾 5（那会越过看不见的 e）
        assertEquals(4, DropIndex.siblingIndexFor(visible, sibling, 2))
    }

    @Test
    fun `可见列表为空时落到最后`() {
        assertEquals(3, DropIndex.siblingIndexFor(emptyList(), listOf("a", "b", "c"), 0))
    }

    @Test
    fun `越界的下标按边界收敛`() {
        val sibling = listOf("a", "b", "c")
        val visible = listOf("a", "c")
        assertEquals(0, DropIndex.siblingIndexFor(visible, sibling, -5))
        assertEquals(3, DropIndex.siblingIndexFor(visible, sibling, 99))
    }

    @Test
    fun `锚点不在同级列表里时退化为追加`() {
        assertEquals(3, DropIndex.siblingIndexFor(listOf("x"), listOf("a", "b", "c"), 0))
    }

    // ------------------------------------------------------------ reorderedIds

    @Test
    fun `可见即完整清单时，等价于常规的移动一位`() {
        val ids = listOf("a", "b", "c")
        // 把 a 拖到 b 之后
        assertEquals(listOf("b", "a", "c"), DropIndex.reorderedIds(ids, ids, "a", 1))
        // 把 c 拖到最前
        assertEquals(listOf("c", "a", "b"), DropIndex.reorderedIds(ids, ids, "c", 0))
    }

    @Test
    fun `可见只是子集时，不在这一屏的项顺序不变`() {
        // 完整清单 a b c d e；日历这一天只看得见 b 与 d
        val global = listOf("a", "b", "c", "d", "e")
        val visible = listOf("b", "d")

        // 把 d 拖到 b 之前：插到 b 之前，a 与 c 的相对位置不动
        assertEquals(
            listOf("a", "d", "b", "c", "e"),
            DropIndex.reorderedIds(global, visible, "d", 0),
        )

        // 把 b 拖到 d 之后：插到 d 之后
        assertEquals(
            listOf("a", "c", "d", "b", "e"),
            DropIndex.reorderedIds(global, visible, "b", 2),
        )
    }

    @Test
    fun `被拖动的项本来就在可见列表里也不会算错`() {
        val global = listOf("a", "b", "c", "d")
        val visible = listOf("b", "c")
        // 把 b 放到 c 之后
        assertEquals(
            listOf("a", "c", "b", "d"),
            DropIndex.reorderedIds(global, visible, "b", 1),
        )
    }

    @Test
    fun `结果长度与完整清单一致，不丢项不重复`() {
        val global = listOf("a", "b", "c", "d", "e", "f")
        val visible = listOf("c", "e")
        val reordered = DropIndex.reorderedIds(global, visible, "e", 0)
        assertEquals(global.toSet(), reordered.toSet())
        assertEquals(global.size, reordered.size)
    }
}
