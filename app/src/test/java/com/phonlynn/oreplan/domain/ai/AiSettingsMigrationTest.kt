package com.phonlynn.oreplan.domain.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **覆盖安装后旧配置不能丢**（用户口径）。
 *
 * > 「我不希望重新安装软件后我的提示词设计会被覆盖」
 *
 * 覆盖安装不会清数据，所以只可能是"读的时候没读回来"。
 * 这个文件把各种**历史格式**都喂进来，确认解析结果里用户的东西还在。
 *
 * ## 为什么这类测试特别重要
 *
 * `AiSettingsStore.read()` 是 `runCatching { … }.getOrElse { AiSettings() }` ——
 * **解析失败会静默退回默认值**。用户在界面上看到的就是"我的设置被重置了"，
 * 而旧数据其实还躺在数据库里。静默失败最难查，所以只能靠测试先钉住。
 */
class AiSettingsMigrationTest {

    private fun parse(raw: String) =
        AiSettingsCodec.fromJson(JSONObject(raw))

    // ---------------------------------------------------------------- 旧格式

    /**
     * ⚠️ **第一版格式**：提示词四个字段平铺在顶层。
     *
     * 这是我这两轮改动**之前**的样子。用户覆盖安装后遇到的就是这一种。
     */
    @Test
    fun `第一版扁平格式的提示词要能读回来`() {
        val raw = """
            {
              "activePromptId": "concise",
              "customPrompts": [
                {"id": "custom-1", "name": "自定义 1", "text": "我的提示词正文"}
              ],
              "promptOverrides": {"concise": "我改过的精简版"},
              "removedPrompts": ["socratic"]
            }
        """.trimIndent()

        val chat = parse(raw).promptSet(PromptTarget.CHAT)

        assertEquals("选中的那组要读回来", "concise", chat.activeId)
        assertEquals("自定义组不能丢", "我的提示词正文", chat.custom.single().text)
        assertEquals("改过的正文不能丢", "我改过的精简版", chat.overrides["concise"])
        assertEquals("删除记账不能丢", setOf("socratic"), chat.removed)
    }

    /**
     * 中文 id 也要归一化成 slug（更早的版本存的是中文）。
     *
     * ⚠️ **只用「系统默认」来测** —— 它现在是**唯一**还有中文别名的组。
     *
     * 原来这条用的是「精简回答」→ `concise`，但那个组已经连正文一起删了
     *（用户口径：「删除系统默认以外的两个自带提示词」），旧中文名不再有映射。
     *
     * 测的意图没变：**存进去的中文 id，读出来要能对上**。
     */
    @Test
    fun `更早的中文 id 也要认`() {
        val raw = """
            {
              "activePromptId": "系统默认",
              "promptOverrides": {"系统默认": "中文键改过的正文"},
              "removedPrompts": ["某个自定义组"]
            }
        """.trimIndent()

        val chat = parse(raw).promptSet(PromptTarget.CHAT)

        assertEquals(AiSettings.SYSTEM_DEFAULT, chat.activeId)
        assertEquals(
            "中文键要归一化后能取到",
            "中文键改过的正文",
            chat.overrides[AiSettings.SYSTEM_DEFAULT],
        )
        // 认不出来的 id 原样保留（自定义组的 id 不能被改掉）
        assertTrue("认不出的 id 原样保留", chat.removed.contains("某个自定义组"))
    }

    // ---------------------------------------------------------------- 新格式

    /** 新版格式（两套）要能往返。 */
    @Test
    fun `新版两套格式往返`() {
        val original = AiSettings(
            prompts = mapOf(
                PromptTarget.CHAT to PromptSet(
                    activeId = "concise",
                    custom = listOf(PromptEntry("c1", "我的", "聊天用", builtIn = false)),
                    overrides = mapOf("concise" to "聊天改过"),
                    removed = setOf("socratic"),
                ),
                PromptTarget.ASSISTANT to PromptSet(
                    activeId = "socratic",
                    overrides = mapOf("socratic" to "助手改过"),
                ),
            ),
        )

        val restored = AiSettingsCodec.fromJson(AiSettingsCodec.toJson(original))

        assertEquals(original.promptSet(PromptTarget.CHAT), restored.promptSet(PromptTarget.CHAT))
        assertEquals(original.promptSet(PromptTarget.ASSISTANT), restored.promptSet(PromptTarget.ASSISTANT))
    }

    // ---------------------------------------------------------------- 混合/损坏

    /**
     * ⚠️ **新旧格式同时存在**时，以新格式为准 —— 但不能因此丢掉旧的。
     *
     * 场景：用户先升到新版（写入了新格式），后来因为某些原因旧字段还在库里。
     * 这时若新格式里聊天那套是空的，应当把旧的兜进来。
     */
    @Test
    fun `新格式存在时以它为准`() {
        val raw = """
            {
              "activePromptId": "concise",
              "promptSets": {
                "CHAT": {"activeId": "socratic"}
              }
            }
        """.trimIndent()

        val chat = parse(raw).promptSet(PromptTarget.CHAT)

        assertEquals("新格式优先", "socratic", chat.activeId)
    }

    /** 新格式里聊天那套是空的 → 旧的兜进来（别让用户白丢一次）。 */
    @Test
    fun `新格式为空时旧字段兜底`() {
        val raw = """
            {
              "activePromptId": "concise",
              "promptSets": {}
            }
        """.trimIndent()

        assertEquals("concise", parse(raw).promptSet(PromptTarget.CHAT).activeId)
    }

    /**
     * ⚠️ **完全损坏的 JSON 不能抛异常** —— 那会让 App 起不来。
     *
     * 退回默认值可以接受（总比闪退好），但这条测试在提醒：
     * 退回是**静默**的，用户看到的是"我的设置没了"。
     */
    @Test
    fun `字段类型完全不对时退回默认而不崩`() {
        val raw = """{"activePromptId": {"这不是字符串": 1}, "customPrompts": "这不是数组"}"""

        val settings = parse(raw)

        assertEquals(
            "退回默认值，而不是抛出去让 App 崩",
            AiSettings.SYSTEM_DEFAULT,
            settings.promptSet(PromptTarget.CHAT).activeId,
        )
    }

    /** 完全空的 JSON 也要能给出可用的默认值。 */
    @Test
    fun `空 JSON 给出默认`() {
        val settings = parse("{}")

        PromptTarget.entries.forEach { target ->
            assertEquals(AiSettings.SYSTEM_DEFAULT, settings.promptSet(target).activeId)
        }
    }
}
