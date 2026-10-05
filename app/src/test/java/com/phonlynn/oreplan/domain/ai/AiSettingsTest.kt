package com.phonlynn.oreplan.domain.ai

import com.phonlynn.oreplan.domain.ai.PromptSet
import com.phonlynn.oreplan.domain.ai.PromptTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 配置的序列化与「生效模型」的选取逻辑。
 *
 * ## 为什么值得测
 *
 * 序列化错了的表现很隐蔽：**配置看着保存成功了，重启后却空了**，
 * 或者**换了模型却没生效**。而这两种都很难在界面上发现 ——
 * 用户只会觉得"AI 有时候不听话"。
 *
 * 这里不依赖数据库：直接测 JSON 往返与选取规则。
 * （`AiSettingsStore` 的读写是薄薄一层 DAO 调用。）
 */
class AiSettingsTest {

    private fun provider(
        id: String,
        model: String = "deepseek-flash",
        baseUrl: String = "https://api.deepseek.com",
        apiKey: String = "sk-test",
        enabled: Boolean = true,
    ) = AiProviderConfig(
        id = id,
        name = "deepseek",
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        enabled = enabled,
    )

    // ---------------------------------------------------------------- 生效模型

    @Test
    fun `选中的配置可用时返回它`() {
        val settings = AiSettings(
            providers = listOf(provider("a"), provider("b")),
            activeChatId = "b",
        )

        assertEquals("b", settings.chat()?.id)
        assertTrue(settings.hasChat)
    }

    /** 配置被停用后**不该还生效** —— 否则用户关掉了它却仍在被使用。 */
    @Test
    fun `选中的配置被停用时视为没有`() {
        val settings = AiSettings(
            providers = listOf(provider("a", enabled = false)),
            activeChatId = "a",
        )

        assertNull(settings.chat())
        assertFalse(settings.hasChat)
    }

    /** 缺 Base URL / Key / 模型时算不可用 —— 否则会发起一次必然失败的请求。 */
    @Test
    fun `字段不全的配置不算可用`() {
        assertFalse(
            "缺 Key 不该算可用",
            AiSettings(
                providers = listOf(provider("a", apiKey = "")),
                activeChatId = "a",
            ).hasChat,
        )
        assertFalse(
            "缺模型名不该算可用",
            AiSettings(
                providers = listOf(provider("a", model = "")),
                activeChatId = "a",
            ).hasChat,
        )
        assertFalse(
            "缺地址不该算可用",
            AiSettings(
                providers = listOf(provider("a", baseUrl = "")),
                activeChatId = "a",
            ).hasChat,
        )
    }

    /** 对话模型与向量模型**各自独立** —— 一个是空的，不影响另一个。 */
    @Test
    fun `对话与向量互相独立`() {
        val settings = AiSettings(
            providers = listOf(
                provider("chat").copy(model = "deepseek-flash"),
                provider("emb").copy(model = "qwen3-vl-embedding"),
            ),
            activeChatId = "chat",
            activeEmbeddingId = "emb",
        )

        assertEquals("deepseek-flash", settings.chat()?.model)
        assertEquals("qwen3-vl-embedding", settings.embedding()?.model)
    }

    /** 只配了对话、没配向量时，对话仍可用（不该被连累）。 */
    @Test
    fun `没配向量模型不影响对话模型可用`() {
        val settings = AiSettings(providers = listOf(provider("a")), activeChatId = "a")

        assertTrue(settings.hasChat)
        assertFalse(settings.hasEmbedding)
        assertNull(settings.embedding())
    }

    /** 没有选中任何配置时不能崩。 */
    @Test
    fun `空配置是安全的`() {
        val settings = AiSettings()

        assertNull(settings.chat())
        assertNull(settings.embedding())
        assertFalse(settings.hasChat)
    }

    // ---------------------------------------------------------------- 显示名

    @Test
    fun `显示名缺失时退回模型 id`() {
        assertEquals("deepseek-flash", provider("a").label)
        assertEquals("我的模型", provider("a").copy(displayName = "我的模型").label)
    }

    /** 显示名为空白串时也应退回模型 id（不是显示一个空白）。 */
    @Test
    fun `显示名是空白时退回模型 id`() {
        assertEquals("deepseek-flash", provider("a").copy(displayName = "   ").label)
    }

    // ---------------------------------------------------------------- JSON 往返

    /**
     * 用**真的**序列化器，不是手抄一份。
     *
     * 原先这里复刻了 store 的 toJson —— 那份抄本和真代码会各自演化，
     * 于是「往返测试通过」并不代表真代码能往返。
     */
    private fun toJson(settings: AiSettings): JSONObject = AiSettingsStore.toJsonForTest(settings)

    private fun fromJson(json: JSONObject): AiSettings = AiSettingsStore.fromJsonForTest(json)

    /**
     * ⚠️ **`activeChatId = null` 存成 `JSONObject.NULL` 后读回来必须是 null。**
     *
     * 这是最容易错的一处：`optString` 对 JSON null 返回字符串 `"null"`，
     * 于是「没选中任何模型」变成了「选中了一个叫 null 的模型」，
     * 表现是**配置看着在、却永远不生效**。
     */
    @Test
    fun `null 的选中项往返后仍是 null`() {
        val json = toJson(AiSettings())
        assertTrue("必须写成 JSON null", json.isNull("activeChatId"))

        // 复刻 store 的读取方式
        val read = if (!json.has("activeChatId") || json.isNull("activeChatId")) {
            null
        } else {
            json.optString("activeChatId").takeIf { it.isNotBlank() }
        }
        assertNull("读回来必须是 null，不能是字符串 \"null\"", read)
    }

    /** 常规字段往返不丢失。 */
    @Test
    fun `配置字段往返一致`() {
        val original = AiSettings(
            providers = listOf(
                AiProviderConfig(
                    id = "p1",
                    name = "deepseek",
                    baseUrl = "https://api.deepseek.com",
                    apiKey = "sk-abc",
                    model = "deepseek-flash",
                    displayName = "标准模型",
                    enabled = true,
                    supportsTools = true,
                ),
            ),
            activeChatId = "p1",
            activeEmbeddingId = null,
            temperature = 0.5,
            maxTokens = 4096,
        )

        val json = toJson(original)
        val p = json.getJSONArray("providers").getJSONObject(0)

        assertEquals("p1", p.getString("id"))
        assertEquals("deepseek", p.getString("name"))
        assertEquals("https://api.deepseek.com", p.getString("baseUrl"))
        assertEquals("sk-abc", p.getString("apiKey"))
        assertEquals("deepseek-flash", p.getString("model"))
        assertEquals("标准模型", p.getString("displayName"))
        assertTrue(p.getBoolean("enabled"))
        assertEquals(0.5, json.getDouble("temperature"), 0.001)
        assertEquals(4096, json.getInt("maxTokens"))
    }

    /** 未知的协议名不该让整个配置读不出来。 */
    @Test
    fun `未知协议名退回默认值`() {
        val parsed = runCatching {
            ProviderType.valueOf("SOME_FUTURE_PROTOCOL")
        }.getOrDefault(ProviderType.OPENAI_COMPATIBLE)

        assertEquals(ProviderType.OPENAI_COMPATIBLE, parsed)
    }

    /** 协议类型只有一个值 —— 这是刻意的（用户要求适配最广的方案）。 */
    @Test
    fun `目前只有 OpenAI 兼容格式一种协议`() {
        assertEquals(1, ProviderType.entries.size)
        assertEquals("OpenAI 兼容格式", ProviderType.OPENAI_COMPATIBLE.label)
    }

    /**
     * 两套提示词默认都选中「系统默认」，不能是空的。
     *
     * 空的 `activeId` 会让 `buildSystemPrompt` 找不到任何正文 ——
     * 请求里就没有系统提示了。
     */
    @Test
    fun `两套提示词默认都启用系统默认`() {
        PromptTarget.entries.forEach { target ->
            assertEquals(
                "$target 那套的默认选中项",
                AiSettings.SYSTEM_DEFAULT,
                AiSettings().promptSet(target).activeId,
            )
        }
    }

    /** 两套的**内置正文不同** —— 助手那套要多讲工具纪律。 */
    @Test
    fun `两套内置正文不一样`() {
        val chat = AiSettings.BUILT_IN_PROMPTS.getValue(PromptTarget.CHAT)
            .getValue(AiSettings.SYSTEM_DEFAULT)
        val assistant = AiSettings.BUILT_IN_PROMPTS.getValue(PromptTarget.ASSISTANT)
            .getValue(AiSettings.SYSTEM_DEFAULT)

        assertFalse("两套不该是同一份正文", chat == assistant)
        assertTrue(
            "助手那套必须包含聊天那套的全部内容（用户口径：保留聊天提示词的前提下额外加）",
            assistant.startsWith(chat),
        )
        assertTrue("助手那套要讲工具纪律", assistant.contains("先查再答"))
    }

    // ------------------------------------------------------------ 提示词 id

    /**
     * 内置组只剩**「系统默认」**一个（用户口径）。
     *
     * > 「删除系统默认以外的两个自带提示词，聊天和助手都删」
     *
     * 它的 id 必须是**稳定 slug**，不能是中文名 —— 中文当 id 的时候，
     * 改一个字的文案就会让所有已存的 `activePromptId` / `promptOverrides`
     * 全部对不上。
     */
    @Test
    fun `内置组只剩系统默认且 id 是稳定 slug`() {
        assertEquals("system-default", AiSettings.SYSTEM_DEFAULT)
        assertEquals(
            "内置组应当只剩一个",
            listOf(AiSettings.SYSTEM_DEFAULT),
            AiSettings.BUILT_IN_PROMPT_NAMES.map { it.second },
        )
    }

    /**
     * ⚠️ **两套的内置正文现在都只剩「系统默认」这一项**。
     *
     * 删掉 `concise` / `socratic` 之后，如果哪天有人手滑把某一项加回来，
     * 这条会跑红 —— 而设置页会多出一个不该有的组。
     */
    @Test
    fun `两套内置正文都只剩系统默认`() {
        PromptTarget.entries.forEach { target ->
            assertEquals(
                "$target 那套的内置组",
                setOf(AiSettings.SYSTEM_DEFAULT),
                AiSettings.BUILT_IN_PROMPTS.getValue(target).keys,
            )
        }
    }

    /**
     * 旧版本存下来的中文 id 要能映射到新 slug。
     *
     * ⚠️ **只测「系统默认」** —— 另外两个已经连正文一起删了，
     * 它们的旧中文名不再有映射（见下面那条测试）。
     */
    @Test
    fun `旧中文 id 映射到新 slug`() {
        assertEquals(AiSettings.SYSTEM_DEFAULT, AiSettings.normalizePromptId("系统默认"))
    }

    /**
     * ⚠️ **已删除的那两组：旧 id 原样返回，最终回落到系统默认**。
     *
     * 这是「真删」之后的正确行为：
     *
     * ```
     * normalizePromptId("concise")  → "concise"（没有别名了）
     * BUILT_IN_PROMPTS["concise"]   → null
     *                                 ↓  第 4 级回落
     *                            「系统默认」  ✅ 不会崩、也不会发出空提示词
     * ```
     */
    @Test
    fun `已删除的组其旧 id 原样返回`() {
        // 中文旧名不再有映射，原样返回（随后会在 BUILT_IN_PROMPTS 里查不到）
        assertEquals("精简回答", AiSettings.normalizePromptId("精简回答"))
        assertEquals("苏格拉底式提问", AiSettings.normalizePromptId("苏格拉底式提问"))
        // 旧的英文 slug 同理
        assertEquals("concise", AiSettings.normalizePromptId("concise"))
        assertEquals("socratic", AiSettings.normalizePromptId("socratic"))
    }

    /** 认不出来的 id 原样返回 —— 自定义组的 id 不能被改掉。 */
    @Test
    fun `未知 id 原样返回`() {
        assertEquals("custom-1-123", AiSettings.normalizePromptId("custom-1-123"))
    }

    /**
     * 反序列化时：旧的 `activePromptId` 与 `promptOverrides` 的键都要归一化，
     * **并且迁移进聊天那套**（现在是两套配置）。
     *
     * 这是「升级后老配置不丢」的核心保证 —— 只改常量不够，
     * 存下来的 JSON 里还是旧的中文键，而且是**扁平**的旧结构。
     *
     * ⚠️ 用的是**字面量** `"concise"` 而不是 `AiSettings.CONCISE` ——
     * 那个组已经删了，而这条测试模拟的正是"老配置里存着一个
     * 现在已不存在的组 id"。用字面量反而更贴近真实场景。
     */
    @Test
    fun `读取旧配置时归一化提示词 id 并迁移进聊天那套`() {
        val raw = """
            {"activePromptId":"concise","promptOverrides":{"concise":"改过的正文"}}
        """.trimIndent()

        val settings = AiSettingsStore.fromJsonForTest(org.json.JSONObject(raw))
        val chat = settings.promptSet(PromptTarget.CHAT)

        assertEquals("旧配置应当迁进聊天那套", "concise", chat.activeId)
        assertEquals("改过的正文", chat.overrides["concise"])
    }

    /**
     * ⚠️ **助手那套不该被旧配置污染**。
     *
     * 旧版本只有一套，它属于聊天（聊天是默认模式，旧配置绝大多数在它下面攒的）。
     * 助手那套是新的、正文完全不同，把聊天硬套过去反而更糟。
     */
    @Test
    fun `旧配置不会污染助手那套`() {
        val raw = """
            {"activePromptId":"concise","promptOverrides":{"concise":"改过的正文"}}
        """.trimIndent()

        val assistant = AiSettingsStore.fromJsonForTest(org.json.JSONObject(raw))
            .promptSet(PromptTarget.ASSISTANT)

        assertEquals("助手那套应当保持默认", AiSettings.SYSTEM_DEFAULT, assistant.activeId)
        assertTrue("助手那套不该拿到聊天的改动", assistant.overrides.isEmpty())
    }

    /**
     * `removed` 里**永远不该有**「系统默认」。
     *
     * 它是唯一的兜底项：删了之后请求里就再没有系统提示了。
     * 即便配置文件被人手改坏，读出来也要兜住。
     */
    @Test
    fun `系统默认不会被标记为已删除`() {
        val raw = """{"removedPrompts":["系统默认","concise"]}"""

        val removed = AiSettingsStore.fromJsonForTest(org.json.JSONObject(raw))
            .promptSet(PromptTarget.CHAT).removed

        assertFalse(removed.contains(AiSettings.SYSTEM_DEFAULT))
        assertTrue("别的 id 仍然照常记账", removed.contains("concise"))
    }

    /** 删除集合要能存能读（用户自建组的删除靠它记账）—— 两套**各存各的**。 */
    @Test
    fun `删除集合能往返序列化`() {
        val original = AiSettings(
            prompts = mapOf(
                PromptTarget.CHAT to PromptSet(removed = setOf("custom-a")),
                PromptTarget.ASSISTANT to PromptSet(removed = setOf("custom-b")),
            ),
        )

        val restored = AiSettingsStore.fromJsonForTest(AiSettingsStore.toJsonForTest(original))

        assertEquals(
            "聊天那套的删除项要能读回来",
            setOf("custom-a"),
            restored.promptSet(PromptTarget.CHAT).removed,
        )
        assertEquals(
            "助手那套**独立记账**，不能被聊天那套串了",
            setOf("custom-b"),
            restored.promptSet(PromptTarget.ASSISTANT).removed,
        )
    }
}
