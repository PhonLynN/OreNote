package com.phonlynn.oreplan.v2.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **回归守卫**：导入课表必须先清空旧课表。
 *
 * ## 为什么用「读源码」的方式测
 *
 * 导入是 ViewModel 里的流程（需要假仓储才能端到端测），而项目现有测试
 * 都是纯函数、没有 fake repository 基建。但这个行为**必须被钉住**：
 *
 * 2026-09-22 用户反馈「导入新课表时忘了删原课表」——
 * 结果是新旧叠加：同名课程各留一份、时段翻倍，课表格子里同格多课而显示错乱。
 *
 * 这类「少了一步」的回归在纯函数测试里测不到，所以直接断言源码里那一步还在。
 * 与 `ShadowRoomUsageTest` 同一思路。
 */
class CourseImportReplaceTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/phonlynn/oreplan/$relative"),
            File("app/src/main/java/com/phonlynn/oreplan/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到源文件：$relative（尝试过 $candidates）")
        return file.readText()
    }

    @Test
    fun `导入流程里保留了 clearAll 调用`() {
        val src = source("v2/screens/TtSettingsScreenV2.kt")
        assertTrue(
            "导入课表必须先清空旧课表（courseRepository.clearAll()），" +
                "否则新旧叠加：同名课程各留一份、时段翻倍。当前源码里找不到该调用。",
            src.contains("courseRepository.clearAll()"),
        )
    }

    @Test
    fun `clearAll 在写入循环之前`() {
        val src = source("v2/screens/TtSettingsScreenV2.kt")
        val clearAt = src.indexOf("courseRepository.clearAll()")
        val writeAt = src.indexOf("courseRepository.upsertCourse(")
        assertTrue("两个调用都应存在", clearAt >= 0 && writeAt >= 0)
        assertTrue(
            "clearAll 必须在 upsertCourse 之前 —— 顺序反了会把刚导入的课也清掉。",
            clearAt < writeAt,
        )
    }

    @Test
    fun `仓储接口暴露了 clearAll`() {
        val src = source("domain/repository/CourseRepository.kt")
        assertTrue(
            "CourseRepository 需要 clearAll()，导入前用它做整体替换。",
            src.contains("suspend fun clearAll()"),
        )
    }

    @Test
    fun `清空在事务里完成`() {
        val src = source("data/repository/RoomCourseRepository.kt")
        val i = src.indexOf("override suspend fun clearAll()")
        assertTrue("实现里必须有 clearAll", i >= 0)
        // 取该方法后续一小段，断言它在事务里
        val body = src.substring(i, (i + 320).coerceAtMost(src.length))
        assertTrue(
            "clearAll 应在 withTransaction 里执行（先删安排再删课程），" +
                "避免中途失败留下孤儿安排。",
            body.contains("withTransaction"),
        )
    }
}
