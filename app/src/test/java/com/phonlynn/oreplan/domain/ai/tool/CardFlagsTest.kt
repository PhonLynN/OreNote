package com.phonlynn.oreplan.domain.ai.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片开关参数的解析。
 *
 * ## 为什么值得单独测
 *
 * 用户报的是「没法让 AI 创建一张**半宽的置顶保密卡片**」—— 模型能看到参数了，
 * 但**参数解析错一位，写进去的卡就是错的**：宽度没生效、或者"不显示日期"
 * 反而变成了显示。
 *
 * 最容易错的是**三态**：`没传` / `传了 true` / `传了 false`。
 * 用 `optBoolean(key, 默认值)` 会把前两者混成一个，而它们意思相反。
 */
class CardFlagsTest {

    private fun args(vararg pairs: Pair<String, Any>): JSONObject =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    // ---------------------------------------------------------------- 默认值

    /** 什么都不传时：自动宽度、不置顶、不保密、**显示日期**。 */
    @Test
    fun `不传参数时的默认`() {
        val f = cardFlagsOf(JSONObject())

        assertNull("宽度默认跟随自动", f.widthMode)
        assertFalse(f.pinned)
        assertFalse(f.secret)
        assertNull(f.secretHint)
        assertTrue("日期默认显示", f.showDate)
        assertNull(f.imageLayout)
    }

    // ---------------------------------------------------------------- 宽度

    /** 用户原话里的「半宽」。 */
    @Test
    fun `半宽`() {
        assertEquals("half", cardFlagsOf(args("width" to "half")).widthMode)
        assertEquals("half", cardFlagsOf(args("width" to "半宽")).widthMode)
        assertEquals("half", cardFlagsOf(args("width" to "半")).widthMode)
    }

    @Test
    fun `整宽`() {
        assertEquals("full", cardFlagsOf(args("width" to "full")).widthMode)
        assertEquals("full", cardFlagsOf(args("width" to "整宽")).widthMode)
        assertEquals("full", cardFlagsOf(args("width" to "占满")).widthMode)
    }

    /**
     * ⚠️ `auto` 要映射成 **null**，不是字符串 `"auto"`。
     *
     * 存储口径是 `null` = 跟随默认。存成字面量会让它在界面上
     * 被当成一个未知的宽度值。
     */
    @Test
    fun `自动宽度存成 null`() {
        assertNull(cardFlagsOf(args("width" to "auto")).widthMode)
        assertNull(cardFlagsOf(args("width" to "自动")).widthMode)
        assertNull(cardFlagsOf(args("width" to "说不清的东西")).widthMode)
    }

    // ---------------------------------------------------------------- 三态布尔

    /**
     * ⚠️ **`show_date` 的三态**：没传 → true；传 false → false。
     *
     * 这是最容易写错的一处：用 `optBoolean("show_date", true)` 的话
     * "传了 false"和"没传"分不出来，而两者意思相反。
     */
    @Test
    fun `显示日期是三态`() {
        assertTrue("没传 → 默认显示", cardFlagsOf(JSONObject()).showDate)
        assertTrue(cardFlagsOf(args("show_date" to true)).showDate)
        assertFalse("明确传 false 才关掉", cardFlagsOf(args("show_date" to false)).showDate)
    }

    @Test
    fun `置顶与保密默认关`() {
        assertFalse(cardFlagsOf(JSONObject()).pinned)
        assertFalse(cardFlagsOf(JSONObject()).secret)

        assertTrue(cardFlagsOf(args("pinned" to true)).pinned)
        assertTrue(cardFlagsOf(args("secret" to true)).secret)
    }

    // ---------------------------------------------------------------- 暗号

    /** 用户原话里的「保密」还可能带一句暗号。 */
    @Test
    fun `保密暗号`() {
        val f = cardFlagsOf(args("secret" to true, "secret_hint" to "生日礼物"))

        assertTrue(f.secret)
        assertEquals("生日礼物", f.secretHint)
    }

    /** 暗号是空白时当作没填 —— 否则主页会显示一个空白模糊块。 */
    @Test
    fun `暗号空白当作没填`() {
        assertNull(cardFlagsOf(args("secret_hint" to "   ")).secretHint)
        assertNull(cardFlagsOf(args("secret_hint" to "")).secretHint)
    }

    // ---------------------------------------------------------------- 图片排布

    @Test
    fun `图片排布`() {
        assertEquals("fill", cardFlagsOf(args("image_layout" to "fill")).imageLayout)
        assertEquals("grid", cardFlagsOf(args("image_layout" to "网格")).imageLayout)
        assertNull(cardFlagsOf(args("image_layout" to "看不懂")).imageLayout)
    }

    // ---------------------------------------------------------------- 组合

    /** **用户原话那条**：半宽 + 置顶 + 保密，一次都要生效。 */
    @Test
    fun `半宽置顶保密卡片`() {
        val f = cardFlagsOf(
            args(
                "width" to "half",
                "pinned" to true,
                "secret" to true,
                "secret_hint" to "别点开",
            ),
        )

        assertEquals("half", f.widthMode)
        assertTrue(f.pinned)
        assertTrue(f.secret)
        assertEquals("别点开", f.secretHint)
        assertTrue("没传 show_date 时仍然显示日期", f.showDate)
    }

    /** 回给模型的描述要把这些都说清楚 —— 用户才知道卡建成了什么样。 */
    @Test
    fun `描述包含所有生效的开关`() {
        val text = cardFlagsOf(
            args("width" to "half", "pinned" to true, "secret" to true),
        ).describe()

        assertTrue("要提到半宽：$text", text.contains("半宽"))
        assertTrue("要提到置顶：$text", text.contains("置顶"))
        assertTrue("要提到保密：$text", text.contains("保密"))
    }

    /** 什么都没设时描述是空的（不要输出一个空括号）。 */
    @Test
    fun `没有开关时描述为空`() {
        assertEquals("", cardFlagsOf(JSONObject()).describe())
    }

    /** 预览卡上的中文标签。 */
    @Test
    fun `宽度标签`() {
        assertEquals("半宽", widthLabelOf("half"))
        assertEquals("整宽", widthLabelOf("full"))
        assertNull("null = 自动，不单独列一行", widthLabelOf(null))
    }
}
