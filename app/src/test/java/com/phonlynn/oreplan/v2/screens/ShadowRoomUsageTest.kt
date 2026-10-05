package com.phonlynn.oreplan.v2.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **回归守卫**：带阴影的圆形按钮必须包在 `vShadowRoom` 里。
 *
 * ## 为什么用「读源码」的方式测
 *
 * 项目没有 Compose UI 测试环境，而这一类 bug 的性质很特殊：
 * **代码丢了 `vShadowRoom` 调用，但注释还在提它**。
 * 表现是「阴影被裁成方形」——只有真机肉眼能看出，单测测不到。
 *
 * 2026-09-21 就发生过一次：`BoardScreenV2` 的多选按钮（删除 / 归档 / 移动标签）
 * 丢了 `vShadowRoom`，只剩 import 与注释，于是早已修好的「方形截断」又回来了，
 * 而且是在用户眼皮底下反复出现、极难定位。
 *
 * 这个测试直接盯住「源码里那几处调用还在不在」——
 * 丑陋，但能挡住这类「注释与实现脱节」的回归。
 *
 * ## 原理
 *
 * `graphicsLayer { alpha }` 会按**节点尺寸**建立离屏缓冲。
 * 56dp 节点上若直接画 12dp elevation 的阴影，阴影扩散超出的部分会被裁掉，
 * 看上去就是一块方形边界。`vShadowRoom` 把内容按 92dp 测量，缓冲随之变大，
 * 阴影才完整。
 */
class ShadowRoomUsageTest {

    private fun source(relative: String): String {
        // 测试运行时工作目录是 app/，源码在其下。
        val candidates = listOf(
            File("src/main/java/com/phonlynn/oreplan/$relative"),
            File("app/src/main/java/com/phonlynn/oreplan/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到源文件：$relative（尝试过 $candidates）")
        return file.readText()
    }

    @Test
    fun `多选按钮：源码里保留了 vShadowRoom 调用`() {
        val src = source("v2/screens/BoardScreenV2.kt")
        val calls = Regex("""Modifier\.vShadowRoom\(""").findAll(src).count()
        assertTrue(
            "BoardScreenV2 里带阴影的圆按钮（删除 / 归档 / 移动标签）必须包在 " +
                "vShadowRoom 里，否则阴影会被离屏缓冲裁成方形。当前只有 $calls 处调用。",
            calls >= 2,
        )
    }

    @Test
    fun `FAB：源码里保留了 vShadowRoom 调用`() {
        val src = source("v2/components/VScaffold.kt")
        assertTrue(
            "VFAB 必须包在 vShadowRoom 里（阴影不被裁的既有修法）。",
            src.contains("Modifier.vShadowRoom(") || src.contains(".vShadowRoom("),
        )
    }

    @Test
    fun `vShadowRoom：对外报 contentSize、内容按加大尺寸测量`() {
        val src = source("v2/components/VShadowRoom.kt")
        // 契约：测量 bigPx、对外 layout contentPx。
        assertTrue("必须按加大尺寸测量", src.contains("measure(Constraints.fixed(bigPx, bigPx))"))
        assertTrue("必须只对外报 contentSize", src.contains("layout(contentPx, contentPx)"))
    }
}
