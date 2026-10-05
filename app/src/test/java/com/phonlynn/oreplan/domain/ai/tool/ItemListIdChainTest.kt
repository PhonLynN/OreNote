package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * **「列表粗筛 → 详情精读」这条链路必须通**（用户口径）。
 *
 * > 「ai 能做到粗略定位到一个对象之后为了查询更精准的信息用 get_item_detail 吗」
 *
 * 答案是**原来做不到** —— `get_items` 的输出里没有 id，
 * 而 `get_item_detail` 的说明却写着「id 从那些工具的返回值里拿」。
 * 那条指令是**做不到的**，模型照着做只会失败，然后很可能编一个答案。
 *
 * ## 为什么值得单独测
 *
 * 这是**跨工具的一致性**：单个工具各自看都没问题（`get_items` 少个字段、
 * `get_item_detail` 要个参数），但连起来用就断了。
 * 而这种断裂**编译期抓不到** —— 两个工具各自都是对的。
 */
class ItemListIdChainTest {

    private fun entry(
        itemId: String? = "item-abc",
        courseId: String? = null,
        title: String = "高数",
    ) = AgendaEntry(
        key = "k1",
        source = AgendaSource.ITEM,
        date = LocalDate.of(2026, 10, 3),
        title = title,
        itemId = itemId,
        courseId = courseId,
        itemKind = ItemKind.TASK,
        itemStatus = ItemStatus.TODO,
        startMinute = 13 * 60,
        endMinute = 14 * 60,
        allDay = false,
        createdAtMillis = 0L,
        orderIndex = 0.0,
        priority = 0,
    )

    /**
     * ⚠️ **普通条目必须带 id** —— 否则详情工具够不着。
     *
     * 这是这次修的核心：原来那行注释写着「ID 不显示 —— 需要时模型会另外要」，
     * 但**没有任何工具能让模型"另外要"**。
     */
    @Test
    fun `条目要输出 id`() {
        val line = describeAgendaEntry(entry(itemId = "item-abc"))

        assertTrue("必须带上 id，否则 get_item_detail 调不了：\n$line", line.contains("id=item-abc"))
    }

    /**
     * ⚠️ **课程条目不能给出一个像 id 的东西**。
     *
     * 课表里的课不是一条 `Item`（没有详情可查）。
     *
     * 我第一版写的是 `课程id=course-xyz` —— 而那个字符串**含有子串**
     * `id=course-xyz`，模型（和写测试的我）都会把它读成一个条目 id，
     * 然后拿它去调详情工具 → 找不到 → 可能编答案。
     *
     * 所以课程那种明确写"无详情"，并**完全不含 `id=`**。
     */
    @Test
    fun `课程条目不能给出像 id 的东西`() {
        val line = describeAgendaEntry(entry(itemId = null, courseId = "course-xyz", title = "线性代数"))

        assertFalse("课程不该带任何 `id=`（模型会拿去调详情工具）：\n$line", line.contains("id="))
        assertTrue("但要说清楚它没有详情：\n$line", line.contains("无详情"))
    }

    /** 既没有 itemId 也没有 courseId（理论上不该有）→ 不写空标记。 */
    @Test
    fun `没有任何 id 时不写空标记`() {
        val line = describeAgendaEntry(entry(itemId = null, courseId = null, title = "幽灵条目"))

        assertFalse("不该出现光秃秃的 \"id=\"：\n$line", line.contains("id="))
    }

    /** 其余内容不受影响（防止我为了加 id 把别的挤掉）。 */
    @Test
    fun `加了 id 之后标题与时刻仍在`() {
        val line = describeAgendaEntry(entry(title = "概率论"))

        assertTrue(line.contains("概率论"))
        assertTrue(line.contains("13:00"))
    }
}
