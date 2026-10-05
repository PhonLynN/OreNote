package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * **详情工具必须能读到"一切"**（用户口径）。
 *
 * > 「我注意到 ai 读取不了卡片现在的状态……日程的备注等更多字段我估计
 * > 他也读取不了，这不对，**ai 需要完全能够读取数据库里的任何细节**」
 *
 * ## 这组测试为什么必要
 *
 * "字段漏了"是一种**不会报错的 bug** —— 编译过、运行过、只是模型看不到。
 * 而用户在对话里感受到的是"AI 不知道这件事"，很难联想到是渲染函数少写了一行。
 *
 * 所以这里把**用户点名过的那些字段**逐个钉住：以后谁重构这段渲染，
 * 漏掉任何一个都会跑红。
 */
class ItemDetailFieldsTest {

    private fun item(
        note: String? = null,
        priority: Int = 0,
        allDay: Boolean = false,
        rrule: String? = null,
        pinned: Boolean = false,
        category: String? = null,
        location: String? = null,
        goalType: com.phonlynn.oreplan.domain.model.GoalType? = null,
        unit: String? = null,
        targetValue: Long? = null,
    ) = Item(
        id = "item-1",
        title = "期末考试复习",
        kind = ItemKind.TASK,
        note = note,
        status = ItemStatus.TODO,
        priority = priority,
        allDay = allDay,
        rrule = rrule,
        pinned = pinned,
        category = category,
        location = location,
        goalType = goalType,
        unit = unit,
        targetValue = targetValue,
        // 层级字段是必填的（树结构的内部表示），但不是这个测试关心的事
        treePath = "item-1",
        depth = 0,
        createdAt = Instant.parse("2026-10-01T08:00:00Z"),
        updatedAt = Instant.parse("2026-10-02T09:00:00Z"),
    )

    private fun render(
        item: Item,
        reminderAt: Instant? = null,
    ): String = renderItemDetail(
        item = item,
        blocks = emptyList(),
        checklist = emptyList(),
        attachments = emptyList<Attachment>(),
        reminderAt = reminderAt,
    )

    // ---------------------------------------------------------------- 用户点名的

    /** **备注**（用户明确点名："日程的备注等更多字段我估计他也读取不了"）。 */
    @Test
    fun `备注要能读到`() {
        val text = render(item(note = "第三章到第五章，重点是傅里叶变换"))

        assertTrue("备注内容要出现：\n$text", text.contains("第三章到第五章，重点是傅里叶变换"))
    }

    /** **优先级**要能读到，而且**没设时明确说「无」**。 */
    @Test
    fun `优先级要能读到且明确写无`() {
        assertTrue(render(item(priority = 3)).contains("优先级：高"))
        assertTrue(render(item(priority = 2)).contains("优先级：中"))
        assertTrue(render(item(priority = 1)).contains("优先级：低"))
        // 关键：没设也要给明确答案，而不是留空让模型只能答"没看到"
        assertTrue(render(item(priority = 0)).contains("优先级：无"))
    }

    /** **置顶**状态（用户点名："不知道现在这个卡片是不是置顶的"）。 */
    @Test
    fun `置顶状态要能读到`() {
        assertTrue(render(item(pinned = true)).contains("置顶：是"))
        assertTrue(render(item(pinned = false)).contains("置顶：否"))
    }

    // ---------------------------------------------------------------- 时间规则

    /** **全天**与**重复规则**要能读到 —— 否则模型分不清"每周三都有"和"只有这一次"。 */
    @Test
    fun `全天与重复规则要能读到`() {
        val text = render(item(allDay = true, rrule = "FREQ=WEEKLY;BYDAY=WE"))

        assertTrue("全天要写出来：\n$text", text.contains("全天：是"))
        assertTrue("重复规则要写出来：\n$text", text.contains("FREQ=WEEKLY;BYDAY=WE"))
    }

    // ---------------------------------------------------------------- 组织与外观

    /** 分类 / 色标 / 地点 —— 用户按它们区分条目。 */
    @Test
    fun `分类地点色标要能读到`() {
        val text = render(item(category = "数学", location = "三教 207"))

        assertTrue(text.contains("分类：数学"))
        assertTrue(text.contains("地点：三教 207"))
    }

    // ---------------------------------------------------------------- 目标类

    /** 目标类字段 —— 单位、目标值。 */
    @Test
    fun `目标字段要能读到`() {
        val text = render(
            item(
                goalType = com.phonlynn.oreplan.domain.model.GoalType.QUANTITY,
                unit = "页",
                targetValue = 200,
            ),
        )

        assertTrue("目标类型：\n$text", text.contains("目标类型"))
        assertTrue("单位：\n$text", text.contains("计量单位：页"))
        assertTrue("目标值：\n$text", text.contains("目标值：200"))
    }

    // ---------------------------------------------------------------- 时间戳

    /** 创建 / 修改时刻 —— 用户问"这条什么时候建的"时需要。 */
    @Test
    fun `创建与修改时刻要能读到`() {
        val text = render(item())

        assertTrue("创建时刻：\n$text", text.contains("创建："))
        assertTrue("修改时刻：\n$text", text.contains("修改："))
    }

    // ---------------------------------------------------------------- 提醒

    /** 提醒：有就写时刻，没有就**明确写「无」**（与列表刻意不同）。 */
    @Test
    fun `提醒无时也要明确写出来`() {
        assertTrue(render(item()).contains("提醒：无"))
        assertTrue(
            render(item(), reminderAt = Instant.parse("2026-10-03T01:00:00Z")).contains("提醒："),
        )
    }

    /** 空条目也要能渲染（不能抛异常）—— 兜底。 */
    @Test
    fun `最少字段的条目也能渲染`() {
        val text = render(item())

        assertTrue(text.contains("期末考试复习"))
        assertTrue(text.contains("（这条没有正文）"))
    }
}
