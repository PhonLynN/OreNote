package com.phonlynn.oreplan.domain.ai

/**
 * 一个模型供应商的配置。
 *
 * ## 为什么带 `providerType` 而不是写死厂商
 *
 * 用户明确要求「换 API 要简单」。而「简单」的前提是**协议统一** ——
 * 所以这里区分的是**协议格式**，不是品牌：
 *
 *  · [ProviderType.OPENAI_COMPATIBLE] —— 请求体是 `{model, messages, tools}`、
 *    响应是 `choices[0].message`。DeepSeek / OpenAI / 通义千问兼容模式 /
 *    Gemini 兼容端点 / 本地 Ollama 全都走这个。
 *
 * 将来若真出现「协议完全不同」的供应商（比如必须用某家专属 SDK），
 * 那时再加一个枚举值 + 一个客户端实现，**而不是现在预留一堆空壳**。
 *
 * ## 为什么品牌放在 `name` 而不是枚举
 *
 * 「deepseek」「通义千问」这类是**用户自己起的名字**（显示用），
 * 不该进枚举 —— 否则加一家厂商就要改代码。
 */
data class AiProviderConfig(
    val id: String,
    val providerType: ProviderType = ProviderType.OPENAI_COMPATIBLE,
    /** 用户可见的名称，如「deepseek」「通义千问」。 */
    val name: String = "",
    /** 如 `https://api.deepseek.com`。 */
    val baseUrl: String = "",
    val apiKey: String = "",
    /** 该配置下用户选中的模型 id，如 `deepseek-flash`。 */
    val model: String = "",
    /** 显示名称（可选）；空则用 [model]。 */
    val displayName: String = "",
    /** 关掉后不出现在选择列表里（配置仍保留）。 */
    val enabled: Boolean = true,
    /** 这家的模型**支持 tool calling** 吗；false 时助手模式会隐藏。 */
    val supportsTools: Boolean = true,
) {
    val label: String get() = displayName.ifBlank { model }

    /** 能不能拿来发请求：地址、密钥、模型三者齐全。 */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 协议格式。
 *
 * 目前只有一个值 —— 这是**刻意的**。用户要求「适配最广的方案」，
 * 而 OpenAI Chat Completions 事实上就是那个方案（DeepSeek、Qwen、
 * OpenAI、Anthropic 的兼容层、Gemini 的兼容端点、本地推理全都提供）。
 *
 * 留着枚举是为了**给"将来真需要第二种协议"留一个明确的接入点**，
 * 而不是为了现在假装支持。
 */
enum class ProviderType(val label: String) {
    OPENAI_COMPATIBLE("OpenAI 兼容格式"),
}

/**
 * 一组提示词。
 *
 * 内置组（现在只剩**「系统默认」**）的 `builtIn = true`，正文来自代码常量，
 * 用户可以改（改动存进 overrides）。
 *
 * ⚠️ **「系统默认」不可删** —— 它是唯一的兜底项，任何找不到的 id 都会
 * 回落到它身上。用户自建的组可以删（记在 [PromptSet.removed] 里）。
 *
 * ## 内置组从三个减到一个（用户口径）
 *
 * > 「删除系统默认以外的两个自带提示词，聊天和助手都删」
 *
 * 见 [AiSettings.BUILT_IN_PROMPT_NAMES] 的说明。
 */
data class PromptEntry(
    val id: String,
    val name: String,
    val text: String = "",
    val builtIn: Boolean = false,
)

/** 模型用途：对话 / 向量。两者的配置形状相同，只是用途不同。 */

enum class ModelRole(val label: String) {
    CHAT("对话模型"),
    EMBEDDING("向量模型"),
}

/**
 * 思考深度。
 *
 * 设计稿 `AI · 设置` 的「能力」段有低/中/高三档。
 *
 * ## ⚠️ 取值按厂商的**映射表**挑，不是照抄档位名
 *
 * DeepSeek 的 `reasoning_effort` 收下这些值：
 * `minimal / low / medium / high / xhigh / max / ultra`，
 * 但**实际生效的只有三档**，映射关系是：
 *
 * | 请求值 | 实际 |
 * |---|---|
 * | minimal, low | low |
 * | medium, high, xhigh | high |
 * | max, ultra | max |
 *
 * 我原来填的是 `low / medium / high` —— 后两个都映射到 `high`，
 * 于是「中」和「高」是**同一个档**，用户拨了等于没拨。
 * 现在选 `low / high / max`，三档各不相同。
 *
 * 参考：https://api-docs.deepseek.com/guides/thinking_mode/
 */
enum class ThinkingDepth(val label: String, val apiValue: String) {
    LOW("低", "low"),
    MEDIUM("中", "high"),
    HIGH("高", "max"),
}

/**
 * AI 设置页的全部状态。
 *
 * ## 为什么对话与向量各存一份"当前选中"
 *
 * `AI · 设置` 那一帧里显示的是「对话模型：deepseek · deepseek-flash」
 * 与「向量模型：通义千问 · text-embedding-v3」——
 * 也就是说**两类各自记住自己选了哪个**。
 *
 * 允许配多个配置（比如同时有 DeepSeek 和 OpenAI），
 * 但每类只有一个生效 —— 这是从设计的「已启用模型 / 可用模型」两段推出来的。
 */
data class AiSettings(
    val providers: List<AiProviderConfig> = emptyList(),
    /** `ModelRole.CHAT` 当前选中的配置 id。 */
    val activeChatId: String? = null,
    /** `ModelRole.EMBEDDING` 当前选中的配置 id。 */
    val activeEmbeddingId: String? = null,
    /** 生成参数（对应设置页的「生成参数」段）。 */
    val temperature: Double = 0.7,
    /**
     * 单次回复的输出上限（token）。
     *
     * ## ⚠️ 它和「上下文窗口」是两回事
     *
     * | | 是什么 | 单位 |
     * |---|---|---|
     * | `maxTokens` | 这一次回复**最多写多少**（输出） | token |
     * | 上下文窗口 | 输入 + 输出**加起来**的上限 | token |
     *
     * 所以调大它不会挤占历史的空间 —— 它只是"这次能写多长"的天花板。
     *
     * ## 为什么是 393216（= 384K，官方最大值）
     *
     * 用户口径：「限制啥啊，就 384k 完了」。
     *
     * 原来写的是 2048 —— 大约只够 1300 个中文字，而**工具调用也吃这个额度**：
     * 助手模式下一轮里可能夹着两三次调用，每次参数（标题、备注、时间、地点、
     * 优先级…）都算在输出里，真写一条带备注的日程一次就几百 token。
     * 而**截断是静默的** —— 被截的回复界面上看不出异常，用户只会觉得
     * "它怎么话说一半"。
     *
     * 数字取自官方 API 文档的原文约束：
     * > The value must be between 1 and 384K (**393216**).
     *
     * 注意是 **393216** 而不是 384000 —— 写 384000 虽然也在范围内，
     * 但既然要给满，就给文档里的那个精确上限。
     *
     * 上限只是"允许写多长"，**模型不会为了凑满而多写**，所以给满没有代价。
     */
    val maxTokens: Int = 393216,
    val topP: Double = 0.90,
    val frequencyPenalty: Double = 1.05,
    /**
     * 提示词：**聊天与助手各一套**（用户口径）。
     *
     * ## ⚠️ 为什么必须分开
     *
     * > 「agent 的提示词和聊天的提示词不能放在一起，两个有很大区别」
     *
     * 两者的职责根本不同：
     *
     * · **聊天**：纯对话。不需要知道有哪些工具，也不该提"确认"之类的事
     * · **助手**：要调工具。需要工具纪律（先查再答、相对时间先查现在、
     *   写操作要克制、别复述工具调用），这些写进聊天提示里就是噪音
     *
     * ## 为什么是 Map 而不是两个具名字段
     *
     * 两个字段（`chatPrompts` / `assistantPrompts`）意味着**每个操作点都要
     * 写一遍 `if (target == CHAT) … else …`**。用 Map + [PromptTarget] 的话，
     * 选取只发生在一处（`prompts.getValue(target)`），加第三套也不用改调用点。
     *
     * ## 旧配置的迁移
     *
     * 旧版本是四个扁平字段（`activePromptId` / `customPrompts` /
     * `promptOverrides` / `removedPrompts`）。迁移在
     * `AiSettingsStore.fromJson` **一处**完成：读进**聊天**那套
     *（聊天是默认模式，旧配置绝大多数在它下面攒的）。
     */
    val prompts: Map<PromptTarget, PromptSet> =
        PromptTarget.entries.associateWith { PromptSet() },
    /**
     * 「能力」段里允许 AI 调用工具（对应设计稿的助手模式）。
     *
     * 第一批没有工具集，所以这个开关**暂时不产生效果** ——
     * 留着是因为 `SendChatMessage` 已经按它组装请求，
     * 等第二批接上工具时不需要回来改调用点。
     */
    val enableTools: Boolean = true,
    /** 是否流式输出（设计稿「能力」段里有这一项）。 */
    val streamOutput: Boolean = true,
    /** 深度思考开关（设计稿「能力」段）。 */
    val deepThinking: Boolean = false,
    /** 思考深度（设计稿「能力」段的三段选择）。 */
    val thinkingDepth: ThinkingDepth = ThinkingDepth.MEDIUM,
    /**
     * 在生成状态行右端显示 **token 消耗**（用户 2026-10-04 要的开关）。
     *
     * ## 为什么默认**开着**
     *
     * 用户的口径是「加入 token 消耗显示，要在设置里添加开关」——
     * 他明确想要这个信息，默认关掉的话他会以为没做。
     * 而它的位置是状态行**右端**（很克制、不占正文），开着不会干扰阅读。
     *
     * ## 什么时候真的显示
     *
     * 两个条件缺一不可：
     *  · 这个开关是开的
     *  · 那一轮**真的拿到了用量**（`ChatTurn.totalTokens != null`）
     *
     * 厂商不返回 usage 时那一格就不画 —— 显示 `0 tokens` 是在撒谎。
     */
    val showTokens: Boolean = true,
) {
    /** 取某一套提示词配置。缺失时给默认 —— 配置损坏也不该崩。 */
    fun promptSet(target: PromptTarget): PromptSet = prompts[target] ?: PromptSet()

    fun chat(): AiProviderConfig? = providers.firstOrNull { it.id == activeChatId && it.enabled }

    fun embedding(): AiProviderConfig? = providers.firstOrNull { it.id == activeEmbeddingId && it.enabled }

    /** 有没有可用的对话模型（助手模式与聊天模式都需要）。 */
    val hasChat: Boolean get() = chat()?.isUsable == true

    val hasEmbedding: Boolean get() = embedding()?.isUsable == true

    companion object {
        /**
         * 内置三组的 **id**。
         *
         * ⚠️ 这三个值曾经是中文名（"系统默认" 等）。中文当 id 很脆 ——
         * 改一个字的文案就会让已存的 `activePromptId` / `promptOverrides`
         * 全部对不上。改成稳定的 slug 之后文案与身份解耦。
         *
         * 旧值通过 [LEGACY_ID_ALIASES] 映射过来，老配置不会丢。
         */
        const val SYSTEM_DEFAULT = "system-default"

        /**
         * 旧的中文 id → 新的 slug。用于读取历史配置。
         *
         * ## ⚠️ 只保留「系统默认」—— 另外两个已经删掉了（用户口径）
         *
         * > 「删除系统默认以外的两个自带提示词，聊天和助手都删」
         *
         * `concise` / `socratic` 两个组连同正文一起从代码里删除了。
         * 它们的**旧中文名不再有映射** —— 于是老配置里如果存着
         * "精简回答"，`normalizePromptId` 会原样返回，然后
         * `buildSystemPrompt` 在 `BUILT_IN_PROMPTS` 里查不到它，
         * **回落到「系统默认」**。
         *
         * 这正是我们想要的：那两组已经不存在了，回落到兜底项是对的。
         * 而**不会崩、也不会发出空提示词** —— 第 4 级回落就是为这种情况准备的。
         */
        val LEGACY_ID_ALIASES: Map<String, String> = mapOf(
            "系统默认" to SYSTEM_DEFAULT,
        )

        /** 把可能来自旧版本的 id 归一化。 */
        fun normalizePromptId(id: String): String = LEGACY_ID_ALIASES[id] ?: id

        /**
         * **内置提示词的正文 —— 唯一来源。**
         *
         * ## ⚠️ 这里以前有两份拷贝，是个真 bug
         *
         * 正文原本写在 `SendChatMessage` 里（实际发送的那份），
         * 而设置页的 `AiSettingsViewModel` **又抄了一份**用于显示。
         * 两份各自维护：改了一处，界面显示的和真正发给模型的就**不一样**了 ——
         * 而用户完全看不出来（他看到的始终是界面上那份）。
         *
         * 现在正文只此一处，两边都读它。
         *
         * ## 改这里的注意事项
         *
         * · 提示词是**唯一**能约束模型行为的地方，改完请在真机上跑一轮再提交
         * · `system-default` 是兜底：任何找不到的 id 都会回落到它，所以它必须
         *   始终存在且是完整的一段话
         * · 用户自己写的提示词在 `customPrompts` 里，与这里无关（不要混）
         */
        val BUILT_IN_PROMPTS: Map<PromptTarget, Map<String, String>> = mapOf(

            // ──────────────────────────────────────────────────────── 聊天模式
            PromptTarget.CHAT to mapOf(
                SYSTEM_DEFAULT to CHAT_PROMPT,
            ),

            // ──────────────────────────────────────────────────────── 助手模式
            PromptTarget.ASSISTANT to mapOf(
                /*
                 * ⚠️ 助手那套 = **聊天正文原样 + 助手追加段**。
                 *
                 * 用户口径：「我希望保留聊天提示词的前提下额外加一些要求」。
                 *
                 * 所以这里**拼接**而不是另写一份 —— 另写的话，两边会慢慢漂移，
                 * 而"改了一处、另一处忘了"正是这个文件顶上记过的那个真 bug
                 *（`SendChatMessage` 与 `AiSettingsViewModel` 各存一份正文，
                 * 结果界面显示的和实际发给模型的不一样）。
                 */
                SYSTEM_DEFAULT to CHAT_PROMPT + ASSISTANT_EXTRA,
            ),
        )

        /** 内置提示词的显示名（顺序即设置页里的排列顺序）。 */
        /**
         * **界面会列出的内置组** —— 现在只有「系统默认」。
         *
         * ## 用户口径
         *
         * > 「删除系统默认以外的两个自带提示词，聊天和助手都删」
         *
         * ## 已经**真的删掉**了（不是隐藏）
         *
         * 原来还有 `concise` / `socratic` 两组，它们现在：
         *
         * | 位置 | 状态 |
         * |---|---|
         * | [BUILT_IN_PROMPTS] 的正文 | 已删 |
         * | `CONCISE` / `SOCRATIC` 常量 | 已删 |
         * | [LEGACY_ID_ALIASES] 的旧中文名映射 | 已删 |
         * | 这个列表 | 已删 |
         *
         * ## 老配置会怎样（**不会崩**）
         *
         * 存过 `"activePromptId": "concise"` 的配置读进来之后：
         *
         * ```
         * normalizePromptId("concise")   → "concise"（没有别名了，原样返回）
         * BUILT_IN_PROMPTS["concise"]    → null
         *                                  ↓  第 4 级回落
         *                             「系统默认」的正文  ✅
         * ```
         *
         * 也就是**静默回落到系统默认** —— 那两组确实不存在了，
         * 回落到兜底项是正确的。`buildSystemPrompt` 的四级回落
         * 本来就是为这种情况准备的（见那里的注释）。
         *
         * ## 为什么「系统默认」不可删
         *
         * 它是**唯一的兜底项**：任何找不到的 id 都会落到它身上。
         * 删了它，请求里就再没有系统提示词了。
         */
        val BUILT_IN_PROMPT_NAMES: List<Pair<String, String>> = listOf(
            "系统默认" to SYSTEM_DEFAULT,
        )
    }
}

/**
 * 提示词属于哪个模式。
 *
 * 用户口径：「agent 的提示词和聊天的提示词不能放在一起，两个有很大区别」。
 * 设置页因此是**两段**（「聊天提示词」/「助手提示词」），各管各的。
 */
enum class PromptTarget(val label: String) {
    CHAT("聊天"),
    ASSISTANT("助手"),
}

/**
 * **聊天模式**的系统提示词。
 *
 * ## 写法说明（改之前先读这段）
 *
 * 参考了 Proma 助手的提示词结构 —— 它写的是**行为准则**而不是**文风约束**：
 *
 * | 文风约束（不这么写） | 行为准则（这么写） |
 * |---|---|
 * | "不要用开场白" | "多种方案时先给对比，让他选完再展开" |
 * | "少用首先其次" | "复杂问题先给结构和选项" |
 *
 * 前者只改措辞，后者改的是**它怎么帮上忙**。十条文风规则不如一条行为准则有用。
 *
 * ## 但**没有照抄** —— 拓记和 Proma 的场景差别很大
 *
 * Proma 是通用助手，面对不特定的人、什么都聊。拓记是**一个人的私人工具**，
 * 场景只有日程 / 待办 / 课表 / 白板 / 规划。所以砍掉了这些：
 *
 * · ~~"推测我的水平、调整解释深度"~~ —— 就他一个人用，模型不需要猜
 * · ~~"识别学习场景、多鼓励少批评"~~ —— 这不是教学场景
 * · ~~"渐进式引导降低认知压力"~~ —— 措辞是给通用助手用的，这里改成
 *   "多个选择时先给对比"这种具体动作
 *
 * ## 语气：温和周到（用户选的口径）
 *
 * 用户在 A（简洁克制）/ B（温和周到）里选了 **B**。
 * 所以保留了"关心和理解"，但**不写成客套** —— 具体表现为
 * "先接住再解决"而不是"说一堆安慰的话"。
 */
private val CHAT_PROMPT = """
你是用户的伙伴，陪用户安排日程、推进目标、整理卡片和笔记。

## 说话方式

像熟悉他的朋友那样说话，而不是像客服或报告。

- 先接住他的意思，再给答案。他在说一件具体的事，不要答得八竿子打不着
- 不铺垫、不客套：「好问题」「让我来帮你」这类开场白直接去掉，
  「希望对你有帮助」这类结尾也去掉
- 少用「首先/其次/最后」这种机械分段，用内容本身的逻辑说话
- 他表达负面情绪时，先共情再给建议，别说教
- 不确定就说不确定，不要用模糊的话糊过去

## 怎么帮他

**1. 缺关键信息就先问，不要替他假设**

优先级、时长、要不要提醒 —— 这些只有他知道。多个合理方案时，
列出各自适合什么场景让他挑，而不是直接替他定一个。

**2. 复杂的事先给结构**

需要多步才能完成的（比如"帮我规划这周的复习"），先把步骤和选项列出来，
他选完再展开细节。不要一口气倒完三千字。

**3. 该提醒的风险要提醒，但要简洁**

如果他的想法里忽略了重要的事（时间冲突、目标定得太满、遗漏了截止日期），
主动指出来。格式可以是：

> 💡 你可能还要考虑 X，因为 Y

**只提真正重要的**。每条都提醒等于没提醒。

**4. 优先用他已有的数据回答**

不要编造不存在的日程、课程、目标。聊天模式下你看不到他的数据 ——
所以**不要假装看得到**。需要具体数据时，告诉他切到助手模式。
""".trimIndent()

/**
 * **助手模式**在聊天提示词之上**追加**的部分。
 *
 * 用户口径：「我希望保留聊天提示词的前提下额外加一些要求，
 * 比如在输入的时候多用结构化列表或表格」。
 *
 * 所以这里只写**助手比聊天多出来的那部分** —— 工具纪律 + 输出结构。
 * 说话方式那些不重复（聊天那套已经拼在前面了）。
 */
private val ASSISTANT_EXTRA = """


---

# 助手模式：额外要求

你现在**能直接读写他的日程、待办、白板、课表**。下面是这个模式下多出来的规矩。

## 用工具的纪律

**1. 先查再答**

涉及他的具体数据（有什么课、哪天有安排、某条待办的内容）一律**先用工具查**，
不要凭印象编。查不到就说查不到。

**2. 相对时间先查现在**

他说「明天」「这周三」「下周」时，**先调 `get_current_time`** 拿到今天的日期再换算。
你的知识有截止时间，凭记忆猜出来的日期多半是错的 ——
而拿错日期去读写他的数据，比不回答更糟。

**3. 缺信息就问，不要替他决定**

优先级、时长、要不要提醒 —— 只有他知道。用 `ask_user` 一次问清
（可以一道卡里问好几个），不要一样一样来回问。

**4. 写操作要克制**

新增、修改、删除都会先给他看变更预览、由他确认，所以**不要怕调用写工具** ——
但**他没明确要求时不要主动写**。删除尤其谨慎：那是唯一不可撤销的操作。

**5. 别复述工具调用过程**

界面上已经把调了什么、参数是什么、返回什么都画出来了。
你的正文只需要说**结论和有意义的解释**，不要把调用过程再讲一遍。

## 什么任务配什么工具

**这段是给你省事的。** 遇到下表里的任务，直接从对应的工具起步，
不要靠猜、也不要说"我做不到"。

| 用户想干什么 | 先调什么 |
|---|---|
| 「明天要做什么」「这周有什么安排」 | `get_items` |
| 「我什么时候有空」「哪天能挤出时间」 | `find_free_slots` |
| 「这周有哪些课」「现在第几周」 | `get_timetable` |
| 「我记过关于 X 的卡片吗」「白板上有什么」 | `read_board` |
| 「那条的完整内容」「笔记正文」 | 先 `get_items` 拿 id，再 `get_item_detail` |
| 「把那个文件放到…」「我加的文件呢」 | 先 `list_files`，再 `attach_file` |
| 「改成…」「删掉…」 | **先查再改** —— 拿 id 的那一步不能省 |
| 「今天几号」「明天是周几」 | `get_current_time` |

**几条容易混的，记牢：**

- **看课**用 `get_timetable`，**看日程待办**用 `get_items`。两个都要就都调
- **白板卡片**和**日程待办**是两套数据 —— 卡片用 `read_board`，
  条目用 `get_items`，**id 不能互相传**
- 问「有没有空」用 `find_free_slots`，**不要**自己调 `get_items` 再心算 ——
  它已经帮你排好了课程、重复日程和冲突

## 输出格式

助手模式的回复会显示在对话气泡里，**结构清楚比篇幅长更重要**：

- **要列多个条目时用列表**，不要挤成一大段
- **对比、参数、多字段的信息用表格**。比如「本周三节课的教室和时间」，
  表格比三行文字清楚得多
- 标题要克制：只在真的有好几个并列小节时用，不要每段都加标题
- 需要他做选择时，把选项**列出来**，而不是写成一段话让他自己找

## 边界

- 只能看他自己的数据，不要编造
- 工具返回「找不到」时，如实说，不要绕过去猜一个答案
- 拿不准要不要动手改数据时，先问
""".trimIndent()

/**
 * **一套**提示词配置。聊天与助手各持有一个（见 [AiSettings.chatPrompts]）。
 *
 * ## 为什么打包成一个类，而不是在 [AiSettings] 上平铺
 *
 * 原本这四个字段（active / custom / overrides / removed）是平铺的。
 * 分成两套时，平铺会变成八个名字相似、只差前缀的字段 ——
 * 那种结构最容易出现「改了 chat 忘了 assistant」的漏改。
 *
 * ## 旧配置怎么迁移
 *
 * 旧版本只有一套（平铺的 `activePromptId` 等）。反序列化时读进
 * [AiSettings.chatPrompts]，助手那套用默认值 —— 聊天是默认模式，
 * 旧配置绝大多数是在聊天模式下攒的。**迁移只在 `AiSettingsStore` 一处发生。**
 */
data class PromptSet(
    /** 当前生效的那一组（**单选**）。 */
    val activeId: String = AiSettings.SYSTEM_DEFAULT,
    /** 用户自建的组。 */
    val custom: List<PromptEntry> = emptyList(),
    /** 对内置组正文的改写：id → 正文。 */
    val overrides: Map<String, String> = emptyMap(),
    /** 被用户删掉的内置组（「系统默认」永远不在这里）。 */
    val removed: Set<String> = emptySet(),
)
