package com.phonlynn.oreplan.domain.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 变更记录的**操作对象名**。
 *
 * 用户报的：「这几十个任务卡片没有半点索引，我都不知道自己改了啥，
 * 至少显示个标题，没有标题显示个前十个字也行啊，总之要写明操作对象」。
 *
 * ## 为什么值得测
 *
 * 这个字符串直接决定**用户能不能在一屏十几条记录里找到想取消的那一条**。
 * 取名规则错一点（比如换行没处理、截断没加省略号），列表就会参差不齐、
 * 或者显示成一段莫名其妙的东西。
 */
class ChangeSubjectTest {

    // ---------------------------------------------------------------- 取名优先级

    /** 有标题就用标题。 */
    @Test
    fun `有标题优先用标题`() {
        assertEquals("期末复习", ChangeSubject.of("期末复习", "这里是正文"))
    }

    /** 没标题才退回正文 —— **空串和 null 一样，都算没标题**。 */
    @Test
    fun `没标题用正文`() {
        assertEquals("随手记的一句话", ChangeSubject.of(null, "随手记的一句话"))
        assertEquals("空标题也退回正文", "短正文", ChangeSubject.of("", "短正文"))
    }

    /** 标题是空白（只有空格）也算没标题。 */
    @Test
    fun `空白标题当作没有`() {
        assertEquals("正文兜底", ChangeSubject.of("   ", "正文兜底"))
    }

    /** 两样都没有 → 空串（界面会退回显示 id，而不是显示一个空白）。 */
    @Test
    fun `都没有时返回空串`() {
        assertEquals("", ChangeSubject.of(null, null))
        assertEquals("", ChangeSubject.of("", ""))
        assertEquals("", ChangeSubject.of("  ", "  "))
    }

    // ---------------------------------------------------------------- 截断

    /** 超长的截到一行放得下，并加省略号。 */
    @Test
    fun `超长的会截断并加省略号`() {
        val long = "这是一个非常非常非常非常长的标题需要被截断"
        val got = ChangeSubject.of(long)

        assertEquals(13, got.length)   // 12 个字 + 省略号
        assertEquals("…", got.last().toString())
    }

    /** 刚好 12 字**不加**省略号（不要多此一举）。 */
    @Test
    fun `刚好放得下就不截`() {
        val exactly = "一二三四五六七八九十十一"   // 12 字
        assertEquals(12, exactly.length)
        assertEquals(exactly, ChangeSubject.of(exactly))
    }

    /**
     * ⚠️ **多行正文要先压成一行**。
     *
     * 直接截会带出换行符 —— 界面上一行标题变成两行，整个列表参差不齐。
     * 这是最容易漏的一条。
     */
    @Test
    fun `多行正文压成一行`() {
        val multi = "第一行\n第二行\n第三行"

        val got = ChangeSubject.of(null, multi)

        assertEquals("不该有换行符：$got", false, got.contains('\n'))
    }

    /** 连续空格/制表符也压成一个空格（表格里粘出来的文本常有）。 */
    @Test
    fun `连续空白压成一个空格`() {
        assertEquals("甲 乙", ChangeSubject.of(null, "甲    乙"))
        assertEquals("甲 乙", ChangeSubject.of(null, "甲\t\n乙"))
    }

    /** 首尾空白要去掉。 */
    @Test
    fun `去掉首尾空白`() {
        assertEquals("标题", ChangeSubject.of("  标题  "))
    }
}
