package com.phonlynn.oreplan.v2.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表格子分段（`buildDayCells`）的纯函数测试。
 *
 * ## 为什么测
 *
 * 旧的分段实现用「单向前进 cursor」，遇到同一天重叠的课会**截断或跳过**，
 * 被跳过的课**不占高度** → 该列比其他列短 → 课表错位。
 *
 * 新实现的核心不变量是：
 * **所有段的总长度恒等于 totalPeriods，与内容无关。**
 * 这一条保证了「五列永远等高对齐」，所以必须由测试钉住。
 */
class TimetableCellSegmentationTest {

    private fun block(
        sp: Int,
        ep: Int,
        name: String,
        active: Boolean = true,
        /** 用于区分「同一门课的多条 session」——默认与位置绑定即可。 */
        idSuffix: String = "",
    ) = GridBlock(
        sessionId = "$name-$sp-$ep$idSuffix",
        courseId = name,
        name = name,
        location = null,
        teacher = null,
        weeks = "1-16 周",
        startPeriod = sp,
        endPeriod = ep,
        colorHex = null,
        active = active,
    )

    // ---------------------------------------------------------------- 总长度不变量

    @Test
    fun `总长度恒等于总节数（空）`() {
        val cells = buildDayCells(emptyList(), 14)
        assertEquals(14, totalCellSpan(cells))
        assertEquals(1, cells.size)
        assertTrue(cells.single().isEmpty)
    }

    @Test
    fun `总长度恒等于总节数（单课）`() {
        val cells = buildDayCells(listOf(block(3, 5, "工图")), 14)
        assertEquals(14, totalCellSpan(cells))
    }

    /**
     * 这一条正是旧实现会漏掉的：两门课落同一节次时，
     * 旧实现会跳过其中一门、丢掉它的高度。
     */
    @Test
    fun `总长度恒等于总节数（重叠课也不例外）`() {
        val cells = buildDayCells(
            listOf(block(6, 9, "AI导论"), block(6, 7, "军事理论")),
            14,
        )
        assertEquals("重叠不能丢高度", 14, totalCellSpan(cells))
    }

    @Test
    fun `总长度恒等于总节数（完全相同的区间）`() {
        val cells = buildDayCells(
            listOf(block(3, 5, "甲"), block(3, 5, "乙")),
            14,
        )
        assertEquals(14, totalCellSpan(cells))
    }

    @Test
    fun `总长度恒等于总节数（多组重叠杂乱）`() {
        val cells = buildDayCells(
            listOf(
                block(1, 2, "甲"), block(1, 2, "乙"),
                block(3, 5, "丙"),
                block(6, 9, "丁"), block(6, 7, "戊"), block(8, 9, "己"),
                block(11, 13, "庚"),
            ),
            14,
        )
        assertEquals(14, totalCellSpan(cells))
    }

    /** 不同内容的一天，总长度必须相同 —— 这就是「列间对齐」的数学保证。 */
    @Test
    fun `不同的天总长度相同`() {
        val d1 = buildDayCells(listOf(block(1, 2, "甲"), block(3, 4, "乙")), 14)
        val d2 = buildDayCells(listOf(block(6, 9, "丙"), block(6, 7, "丁")), 14)
        val d3 = buildDayCells(emptyList(), 14)
        assertEquals(totalCellSpan(d1), totalCellSpan(d2))
        assertEquals(totalCellSpan(d2), totalCellSpan(d3))
    }

    // ---------------------------------------------------------------- 段的正确性

    @Test
    fun `单课连排合并为一段`() {
        val cells = buildDayCells(listOf(block(3, 5, "工图")), 14)
        val course = cells.first { !it.isEmpty }
        assertEquals(3, course.startPeriod)
        assertEquals(5, course.endPeriod)
        assertEquals(3, course.span)
    }

    @Test
    fun `相邻空白合并为一段`() {
        val cells = buildDayCells(listOf(block(3, 5, "工图")), 5)
        // 1-2 空白、3-5 课
        assertEquals(2, cells.size)
        assertEquals(1, cells[0].startPeriod)
        assertEquals(2, cells[0].endPeriod)
        assertTrue(cells[0].isEmpty)
        assertEquals(3, cells[1].startPeriod)
        assertEquals(5, cells[1].endPeriod)
        assertEquals(1, cells[1].blocks.size)
    }

    @Test
    fun `部分重叠切成两段，重叠部分含两门课`() {
        val cells = buildDayCells(
            listOf(block(6, 9, "AI导论"), block(6, 7, "军事理论")),
            9,
        )
        // 1-5 空白一段；6-7 两门课重叠一段；8-9 只剩 AI导论一段。
        assertEquals(3, cells.size)
        assertTrue(cells[0].isEmpty)
        assertEquals(1, cells[0].startPeriod)
        assertEquals(5, cells[0].endPeriod)
        assertEquals(6, cells[1].startPeriod)
        assertEquals(7, cells[1].endPeriod)
        assertEquals(2, cells[1].blocks.size)
        assertEquals(8, cells[2].startPeriod)
        assertEquals(9, cells[2].endPeriod)
        assertEquals(1, cells[2].blocks.size)
    }

    @Test
    fun `课程按节次升序排列`() {
        val cells = buildDayCells(
            listOf(block(8, 9, "后"), block(1, 2, "前")),
            10,
        )
        val names = cells.filter { !it.isEmpty }.flatMap { it.blocks }.map { it.name }
        // 段本身按位置排列，所以「前」应在「后」之前
        assertEquals(listOf("前", "后"), names)
    }

    // ---------------------------------------------------------------- 越界

    @Test
    fun `越界的课被裁到范围内但不改变总长度`() {
        val cells = buildDayCells(listOf(block(10, 20, "超长")), 14)
        assertEquals(14, totalCellSpan(cells))
        val seg = cells.first { !it.isEmpty }
        assertEquals(14, seg.endPeriod)
    }

    @Test
    fun `完全越界的课被忽略且不改变总长度`() {
        val cells = buildDayCells(listOf(block(20, 25, "越界")), 14)
        assertEquals(14, totalCellSpan(cells))
        assertTrue(cells.all { it.isEmpty })
    }

    @Test
    fun `总节数为零时返回空`() {
        assertTrue(buildDayCells(listOf(block(1, 2, "甲")), 0).isEmpty())
        assertTrue(buildDayCells(emptyList(), 0).isEmpty())
    }
    // ---------------------------------------------------------------- 同一门课的多段（真实场景）

    /**
     * **回归**（用户 2026-09-22：「部分三节连堂课被从中间截断」）。
     *
     * ## 真实成因
     *
     * 同一门课可以有多条 session（不同周次）。例如「概预算正课」：
     *  · 3-13 周 → 占 3-5 节
     *  · 14-15 周 → 占 3-4 节
     *
     * 界面上**只显示当前周有效的那一段**。但分段若发生在过滤之前，
     * 两条 session 会同时参与分段 → 3-4 与 5 的覆盖不同 → 切成两段 →
     * 本周本该是完整的 3-5 节，却从中间断开。
     *
     * ## 现在的保证
     *
     * **先过滤、再分段**（见 `DayColumn`）。过滤后同一门课当周只剩一条 session，
     * 分段自然还原为完整的 3-5 节。本组测试钉住这个前提。
     */
    @Test
    fun `同一门课只有一条有效 session 时连排不被截断`() {
        // 过滤后剩下的：只有 3-5 这一段
        val cells = buildDayCells(listOf(block(3, 5, "概预算")), 14)
        val course = cells.first { !it.isEmpty }
        assertEquals("应从 3 节连到 5 节", 3, course.startPeriod)
        assertEquals(5, course.endPeriod)
        assertEquals(3, course.span)
    }

    /**
     * 若两条 session **同时有效**（真冲突），分段会切开——
     * 这是**正确**行为：那确实是两段不同的安排，不该合并成一块。
     * 关键在于「过滤先于分段」保证了这种情况只会在真冲突时出现。
     */
    @Test
    fun `两条 session 同时有效时保持切开（真冲突）`() {
        val cells = buildDayCells(
            listOf(
                block(3, 5, "概预算"),
                block(3, 4, "概预算", idSuffix = "-b"),
            ),
            14,
        )
        // 3-4 覆盖两条、5 只覆盖一条 → 正确切成两段
        val courses = cells.filter { !it.isEmpty }
        assertEquals(2, courses.size)
        assertEquals(2, courses[0].blocks.size)
        assertEquals(1, courses[1].blocks.size)
        // 但总长度仍然不变（列间对齐不受影响）
        assertEquals(14, totalCellSpan(cells))
    }

    @Test
    fun `三节连排总长度仍然不变`() {
        val cells = buildDayCells(listOf(block(3, 5, "概预算")), 14)
        assertEquals(14, totalCellSpan(cells))
    }
}
