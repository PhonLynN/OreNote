package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.ChecklistCategory
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChecklistSummaryTest {

    private val now: Instant = Instant.parse("2026-09-11T04:00:00Z")

    private fun entry(
        id: String,
        itemId: String,
        done: Boolean,
        category: ChecklistCategory = ChecklistCategory.MATERIAL,
    ) = ChecklistEntry(
        id = id,
        itemId = itemId,
        category = category,
        title = "条目 $id",
        done = done,
        orderIndex = 1024.0,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `按条目分组统计完成数`() {
        val counts = ChecklistSummary.counts(
            listOf(
                entry("a", "i1", done = true),
                entry("b", "i1", done = false),
                entry("c", "i1", done = false),
                entry("d", "i2", done = true),
            ),
        )

        assertEquals(3, counts.getValue("i1").total)
        assertEquals(1, counts.getValue("i1").done)
        assertEquals(1, counts.getValue("i2").total)
        assertEquals(1, counts.getValue("i2").done)
    }

    @Test
    fun `没有清单的条目不出现在结果里 由调用方决定显示什么`() {
        val counts = ChecklistSummary.counts(listOf(entry("a", "i1", done = false)))
        assertFalse(counts.containsKey("i2"))
    }

    @Test
    fun `空输入得到空映射`() {
        assertTrue(ChecklistSummary.counts(emptyList()).isEmpty())
    }

    @Test
    fun `全部勾选才算完成`() {
        val partial = ChecklistSummary.counts(
            listOf(entry("a", "i1", done = true), entry("b", "i1", done = false)),
        ).getValue("i1")
        assertFalse(partial.isComplete)

        val complete = ChecklistSummary.counts(
            listOf(entry("a", "i1", done = true), entry("b", "i1", done = true)),
        ).getValue("i1")
        assertTrue(complete.isComplete)
    }

    @Test
    fun `分类不影响计数`() {
        val counts = ChecklistSummary.counts(
            listOf(
                entry("a", "i1", done = true, category = ChecklistCategory.FORM),
                entry("b", "i1", done = true, category = ChecklistCategory.CHECKIN),
            ),
        ).getValue("i1")
        assertEquals(2, counts.total)
        assertEquals(2, counts.done)
    }
}
