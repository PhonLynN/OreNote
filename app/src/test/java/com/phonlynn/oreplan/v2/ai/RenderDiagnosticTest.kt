package com.phonlynn.oreplan.v2.ai

import org.junit.Test

/**
 * **诊断用**：把你截图里那段 NS 原文喂给当前渲染器，把解析结果打印出来。
 *
 * ## 为什么要有这个
 *
 * 之前几轮我一直在用"测试全绿"证明渲染器变好了，但那是**我写的测试**。
 * 用户的原话是「新旧在 bug 上没多大差异」—— 要判断这句对不对，
 * 唯一的办法是**拿同一段真实输入，把两版的解析结果并排打出来**，
 * 而不是各自跑各自的用例。
 *
 * 关掉断言，只输出事实。
 */
class RenderDiagnosticTest {

    private val raw: String = listOf(
        "NS方程，全称**纳维-斯托克斯方程**（Navier-Stokes equations），是描述流体运动的核心方程。我按层次给你讲。",
        "",
        "##它想回答什么问题给定一堆流体——水、空气、任何能流动的东西——在某时刻的状态，它接下来会怎么流？NS方程就是给这个\"怎么流\"提供答案的运动方程。",
        "",
        "##核心思想它本质上是**牛顿第二定律（F = ma）用在流体微团上**：",
        "",
        "-左边：流体微团受到的加速度（惯性项）",
        "-右边：所有作用在它上面的力写成常见形式：",
        "",
        "\$\$\\rho\\left(\\frac{\\partial \\mathbf{u}}{\\partial t} + \\mathbf{u}\\cdot\\nabla\\mathbf{u}\\right) = -\\nabla p + \\mu\\nabla^2\\mathbf{u} + \\mathbf{f}\$\$",
        "",
        "其中：",
        "- \$\\mathbf{u}\$：速度场（流体在多快、往哪流）",
        "- \$\\rho\$：密度- \$p\$：压强- \$\\mu\$：动力粘度- \$\\mathbf{f}\$：外力（如重力）",
        "",
        "##各项的物理含义|项 |含义 |",
        "|---|---|",
        "| \$\\rho\\,\\partial\\mathbf{u}/\\partial t\$ |非定常项，速度随时间变化 |",
        "| \$\\rho\\,\\mathbf{u}\\cdot\\nabla\\mathbf{u}\$ |对流项，流体被自身流动带走而改变速度 |",
        "| \$-\\nabla p\$ |压强梯度力，流体从高压流向低压 |",
        "| \$\\mu\\nabla^2\\mathbf{u}\$ |粘性力，流体内部摩擦 |",
        "| \$\\mathbf{f}\$ |外力 |",
        "",
        "通常还要配上**连续性方程**（质量守恒），两者一起构成完整方程组。",
        "",
        "##一个关键分界：粘性-粘性项 \$\\mu\\nabla^2\\mathbf{u}\$存在时叫**NS方程**",
        "-若 \$\\mu =0\$（理想无粘流体），退化为**欧拉方程**",
        "",
        "粘性正是让 NS方程比欧拉方程难得多、也真实得多的原因。",
        "",
        "##为什么它这么出名NS方程本身不难写，难在**它有没有光滑解**。三维情况下，\"给定初值，解是否始终存在且光滑\"至今没被证明——这正是克雷数学研究所**七个千禧年大奖难题之一**，悬赏100万美元。",
        "",
        "同时它也是工程支柱：",
        "-天气预报、气候模拟-飞机、汽车的气动设计（CFD，计算流体力学）",
        "-血液流动、管道、石油输送几乎所有\"流动\"的问题，归根到底都在解它。",
        "",
        "##顺带一提它在\"混沌\"里也很有分量——湍流就是 NS方程在特定条件下的解，而湍流至今仍是经典物理里最难啃的问题之一。",
    ).joinToString("\n")

    /**
     * 对比：**收流层修好前后**，同一段原文解析出的块数。
     *
     * 这段 `raw` 代表**修复后**收到的样子（换行完整）；
     * 把换行全去掉，代表**修复前**收到的样子（换行被空白分片吞掉）。
     *
     * ⚠️ 这是判断"根因是不是收流层"最直接的证据：
     * 块数差异越大，说明"猜粘连"的负担越重 —— 而那些猜测正是
     * 之前几轮所有 bug 的来源。
     */
    @Test
    fun `修复前后的块数对比`() {
        val broken = raw.replace("\n", "")
        val good = parseBlocks(raw)
        val bad = parseBlocks(broken)

        println("==================================================================")
        println("修复后（原文换行完整）：${good.size} 个块")
        println("修复前（换行被吞掉）：  ${bad.size} 个块")
        println("差异：${good.size - bad.size} 个块 —— 这些就是渲染器被迫去猜的部分")
        println("==================================================================")
        println("块类型分布（修复后）：" + good.groupingBy { it::class.simpleName }.eachCount())
        println("块类型分布（修复前）：" + bad.groupingBy { it::class.simpleName }.eachCount())
    }

    @Test
    fun `打印解析结果`() {
        val blocks = parseBlocks(raw)
        println("================ 当前渲染器解析结果：${blocks.size} 个块 ================")
        blocks.forEachIndexed { i, b ->
            val kind = b::class.simpleName
            val text = when (b) {
                is Block.Heading -> "L${b.level}  ${b.text}"
                is Block.Bullet -> "[i${b.indent}] ${b.text}"
                is Block.Ordered -> "[${b.marker}] ${b.text}"
                is Block.Task -> "[${if (b.checked) "x" else " "}] ${b.text}"
                is Block.Code -> "«code» ${b.text.take(60)}"
                is Block.Quote -> "«quote» ${b.text}"
                is Block.Paragraph -> b.text
                is Block.Table -> "header=${b.header} rows=${b.rows.size}"
                is Block.Math -> "«math» ${b.latex}"
                Block.Rule -> "---"
            }
            println("%2d. %-10s %s".format(i, kind, text))
        }
        println("================ 结束 ================")
    }
}
