package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块间距的**层级关系**（用户 2026-10-04 指出）。
 *
 * ## 用户原话
 *
 * > 「ds在由大标题分割的块之间给了**非常大**的间隙，
 * >   而 orenote 不同大标题分割的空隙高度甚至**低于大标题到下方第一行**的高度」
 *
 * ## 实测（同宽 1011px 的双端截图）
 *
 * | 位置 | DS | 我们（改前） |
 * |---|---|---|
 * | 标题**上方** | **160 px** | 177 px |
 * | 标题**下方** | **54 px** | 158 px |
 * | 上/下 比 | **2.94** | **1.12** |
 *
 * 旧实现把「标题之前」定成 **1.3 倍**（最小档之一），
 * 于是标题上方和下方**几乎一样大** —— 正是用户说的"甚至低于"。
 *
 * ## 这个测试钉的是**层级关系**，不是具体 dp
 *
 * "标题之前必须是最大的一档""标题之后必须小于标题之前" ——
 * 这类性质才是排版意图本身。具体倍数会随字号/机型调，
 * 但**关系错了就是错**（而且这次错的正是关系）。
 */
class BlockSpacingOrderTest {

    private fun gap(prev: Block, next: Block, unit: Dp = 16.dp): Dp = gapBefore(prev, next, unit)

    private val heading = Block.Heading(2, "标题")
    private val para = Block.Paragraph("正文")
    private val bullet = Block.Bullet("条目")
    private val math = Block.Math("x")

    /**
     * ⚠️ **核心**：标题之前的留白必须**大于**标题之后。
     *
     * 这是用户报的那一条，也是分隔感的来源。
     */
    @Test
    fun `标题之前的留白大于标题之后`() {
        val beforeHeading = gap(para, heading)   // 上一块 → 标题
        val afterHeading = gap(heading, para)    // 标题 → 下一块

        assertTrue(
            "标题上方（$beforeHeading）必须大于下方（$afterHeading）——" +
                "DS 实测是近 3 倍（160px vs 54px），我们改前只有 1.12 倍（几乎一样大）",
            beforeHeading > afterHeading,
        )
    }

    /** 标题之前应当是**所有档里最大的**。 */
    @Test
    fun `标题之前是最大档`() {
        val beforeHeading = gap(para, heading)
        val paraGap = gap(para, para)
        val listGap = gap(bullet, bullet)

        assertTrue("标题之前（$beforeHeading）应当大于段落之间（$paraGap）", beforeHeading > paraGap)
        assertTrue("标题之前（$beforeHeading）应当大于列表项之间（$listGap）", beforeHeading > listGap)
    }

    /** 列表项之间仍然最紧凑（一份清单不能散成一节一节）。 */
    @Test
    fun `列表项之间最紧凑`() {
        val listGap = gap(bullet, bullet)
        val paraGap = gap(para, para)
        assertTrue("列表项之间（$listGap）应当小于段落之间（$paraGap）", listGap < paraGap)
    }

    /** 标题之后的留白要收得比较紧（让标题与它管的内容贴在一起）。 */
    @Test
    fun `标题之后小于段落之间`() {
        assertTrue(
            "标题之后（${gap(heading, para)}）应当小于段落之间（${gap(para, para)}）",
            gap(heading, para) < gap(para, para),
        )
    }

    /** 公式前后用的也是普通块间距（不再有单独的"公式留白"）。 */
    @Test
    fun `公式按普通块间距处理`() {
        assertTrue(
            "公式与段落之间应当按普通块间距",
            gap(para, math).value > 0f && gap(math, para).value > 0f,
        )
    }
}
