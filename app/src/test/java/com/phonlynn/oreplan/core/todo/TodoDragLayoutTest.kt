package com.phonlynn.oreplan.core.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动让位布局的纯函数测试。
 *
 * 用等高（100）的四行 a/b/c/d 做基准，行高不同时也验一遍累加口径。
 */
class TodoDragLayoutTest {

    private val ids = listOf("a", "b", "c", "d")
    private val rows = ids.map { TodoDragRowInfo(it, 100f, 0) }

    @Test
    fun `没有落点时返回空表，调用方回落到静态槽位`() {
        assertTrue(planTodoDragTargets(rows, "c", null, 0f).isEmpty())
    }

    @Test
    fun `把最后一行拖到最前：中间三行整体下移一个行高`() {
        val out = planTodoDragTargets(rows, "d", TodoDropTarget.Before("a"), draggedLiveTop = 999f)
        assertEquals(100f, out["a"]!!, 0.01f)
        assertEquals(200f, out["b"]!!, 0.01f)
        assertEquals(300f, out["c"]!!, 0.01f)
        assertEquals(999f, out["d"]!!, 0.01f) // 被拖行跟着指针
    }

    @Test
    fun `把第一行拖到第三行之后：跨越的两行整体上移一个行高`() {
        // 草稿顺序 b, c, a, d：a 去 200 的位置（跟着指针画），b/c 上移，d 本来就在 300 不动
        val out = planTodoDragTargets(rows, "a", TodoDropTarget.After("c"), draggedLiveTop = 12f)
        assertEquals(0f, out["b"]!!, 0.01f)
        assertEquals(100f, out["c"]!!, 0.01f)
        assertEquals(300f, out["d"]!!, 0.01f)
        assertEquals(12f, out["a"]!!, 0.01f)
    }

    @Test
    fun `把最后一行放进最前面的组：被跨越的两行整体下移一个行高`() {
        // 草稿顺序 a, d, b, c：d 去 100 的位置（跟着指针画），b/c 让开
        val out = planTodoDragTargets(rows, "d", TodoDropTarget.Into("a"), draggedLiveTop = 5f)
        assertEquals(0f, out["a"]!!, 0.01f)
        assertEquals(200f, out["b"]!!, 0.01f)
        assertEquals(300f, out["c"]!!, 0.01f)
        assertEquals(5f, out["d"]!!, 0.01f)
    }

    @Test
    fun `落点就是自己前后：等于没换位，返回空表让调用方回落静态槽位`() {
        assertTrue(planTodoDragTargets(rows, "b", TodoDropTarget.Before("b"), 100f).isEmpty())
        assertTrue(planTodoDragTargets(rows, "b", TodoDropTarget.After("b"), 100f).isEmpty())
    }

    @Test
    fun `行高不同：按各自高度累加，不是按序号乘行高`() {
        val mixed = listOf(
            TodoDragRowInfo("a", 50f, 0),
            TodoDragRowInfo("b", 80f, 0),
            TodoDragRowInfo("c", 30f, 0),
        )
        val out = planTodoDragTargets(mixed, "c", TodoDropTarget.Before("a"), draggedLiveTop = 0f)
        assertEquals(30f, out["a"]!!, 0.01f) // 被拖行(30)占位后才是 a
        assertEquals(80f, out["b"]!!, 0.01f)
        assertEquals(0f, out["c"]!!, 0.01f)
    }

    @Test
    fun `放在组行之后：跳过整棵子树，落到整个组之后（与落库口径一致）`() {
        // 列表：g(组) / g1(组内) / g2(组内) / x(顶层)
        val groupRows = listOf(
            TodoDragRowInfo("g", 100f, 0),
            TodoDragRowInfo("g1", 100f, 1),
            TodoDragRowInfo("g2", 100f, 1),
            TodoDragRowInfo("x", 100f, 0),
        )
        val out = planTodoDragTargets(groupRows, "x", TodoDropTarget.After("g"), draggedLiveTop = 0f)
        // x 应该落在 g 的整棵子树之后 = 300，而不是紧跟组行 = 100
        assertEquals(0f, out["g"]!!, 0.01f)
        assertEquals(100f, out["g1"]!!, 0.01f)
        assertEquals(200f, out["g2"]!!, 0.01f)
        assertEquals(0f, out["x"]!!, 0.01f)
    }

    @Test
    fun `落点引用的行已不可见：返回空表，不产生错位`() {
        val out = planTodoDragTargets(rows, "b", TodoDropTarget.After("zzz"), draggedLiveTop = 3f)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `被拖行不在可见列表里：返回空表`() {
        val out = planTodoDragTargets(rows, "zzz", TodoDropTarget.Before("a"), draggedLiveTop = 3f)
        assertTrue(out.isEmpty())
    }
}
