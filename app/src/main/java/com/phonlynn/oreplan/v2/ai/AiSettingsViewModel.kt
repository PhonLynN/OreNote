package com.phonlynn.oreplan.v2.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.domain.ai.AiClient
import com.phonlynn.oreplan.domain.ai.AiProviderConfig
import com.phonlynn.oreplan.domain.ai.PromptSet
import com.phonlynn.oreplan.domain.ai.PromptTarget
import com.phonlynn.oreplan.domain.ai.AiSettingsStore
import com.phonlynn.oreplan.domain.ai.PromptEntry
import com.phonlynn.oreplan.domain.ai.AiSettings
import com.phonlynn.oreplan.domain.ai.ThinkingDepth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 可编辑的字段。 */
enum class Field(val label: String) {
    BaseUrl("Base URL"),
    ApiKey("API Key"),
    Model("模型"),
    Temperature("温度"),
    TopP("Top P"),
    FrequencyPenalty("重复惩罚"),
    MaxTokens("最大长度"),
}

/**
 * 内置的三组提示词。
 *
 * 正文默认值取自设计稿 `AI · 设置` 的 Prompt Editor 内容。
 *
 * ⚠️ 这三组里**只有「系统默认」不可删**。用户明确要求：
 * 「除了系统默认那一个，系统内置的剩下的两个都要能够删除」。
 * 所以 `builtIn` 只用来标记"这是出厂项"，不再等同于"不可删"——
 * 删除后靠内置正文仍然能取回默认文本（重置入口见设置页）。
 *
 * ## ⚠️ 正文**不在这里**，取 [AiSettings.BUILT_IN_PROMPTS]
 *
 * 这里原本自己抄了一份正文，而 `SendChatMessage` 里另有一份用于实际发送 ——
 * 两份各自维护，改一处就会让**界面显示的和真正发给模型的不一样**，
 * 而用户完全看不出来。现在正文只有一处，两边都读它。
 *
 * ## ⚠️ 聊天与助手**各有一套**（用户口径）
 *
 * 「agent 的提示词和聊天的提示词不能放在一起，两个有很大区别」。
 * 所以设置页显示两段，各管各的。
 */
private fun builtInPromptsFor(target: PromptTarget): List<PromptEntry> {
    val texts = AiSettings.BUILT_IN_PROMPTS.getValue(target)
    return AiSettings.BUILT_IN_PROMPT_NAMES.map { (name, id) ->
        PromptEntry(
            id = id,
            name = name,
            text = texts[id].orEmpty(),
            builtIn = true,
        )
    }
}

data class AiSettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: String = "0.7",
    val maxTokens: String = "2048",
    val editing: Field? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val topP: String = "0.90",
    val frequencyPenalty: String = "1.05",
    val deepThinking: Boolean = false,
    val thinkingDepth: ThinkingDepth = ThinkingDepth.MEDIUM,
    val streamOutput: Boolean = true,
    /** 在生成状态行右端显示 token 消耗（用户 2026-10-04）。 */
    val showTokens: Boolean = true,
    /**
     * 提示词：**聊天与助手各一套**（用户口径）。
     *
     * 「agent 的提示词和聊天的提示词不能放在一起，两个有很大区别」。
     *
     * ## 为什么用 Map 而不是再平铺五个字段
     *
     * 原本是 `activePromptId` / `customPrompts` / `promptOverrides` /
     * `removedPrompts` 四个扁平字段。分成两套的话，平铺就变成八个名字相似、
     * 只差前缀的字段 —— 那种结构最容易出现"改了 chat 忘了 assistant"的漏改。
     *
     * 打包之后，**要操作哪一套由 `PromptTarget` 一处决定**：
     * 界面点哪一段就传哪个 target，其余代码不需要知道有几套。
     */
    val prompts: Map<PromptTarget, PromptSet> = PromptTarget.entries.associateWith { PromptSet() },
    /** 「启用此配置」开关（对应设计稿配置页里的那一行）。 */
    val enabled: Boolean = true,
) {
    /** 取某一套的配置。 */
    fun promptSet(target: PromptTarget): PromptSet = prompts[target] ?: PromptSet()

    /**
     * 某一套在界面上要显示的提示词组：内置（减去删掉的）+ 自定义。
     *
     * 正文取值顺序：**用户的改动** → 内置默认文本。
     * 改过的优先显示，没改过的仍显示内置文本 ——
     * 比"改过一次之后内置文本就没了"更符合预期。
     */
    fun promptGroupsFor(target: PromptTarget): List<PromptGroup> {
        val set = promptSet(target)
        return (builtInPromptsFor(target) + set.custom)
            .filterNot { it.id in set.removed }
            .map { entry ->
                PromptGroup(
                    id = entry.id,
                    name = entry.name,
                    text = set.overrides[entry.id] ?: entry.text,
                    builtIn = entry.builtIn,
                )
            }
    }

    /**
     * 兼容旧调用点：聊天那套的 [promptGroups]。
     *
     * ⚠️ 新代码请用 [promptGroupsFor] —— 这个写死了 CHAT，
     * 用在助手那一段上会显示错误的内容。
     */
    @Deprecated("用 promptGroupsFor(target)", ReplaceWith("promptGroupsFor(PromptTarget.CHAT)"))
    val promptGroups: List<PromptGroup> get() = promptGroupsFor(PromptTarget.CHAT)
    /**
     * Key 的展示形式：**只露尾部**。
     *
     * 不是安全考虑（用户说过不在意），而是**避免误读**：
     * 设置页常常截图分享，完整 key 露出来容易被别人拿去用。
     */
    val maskedKey: String
        get() = when {
            apiKey.isBlank() -> "未填写"
            apiKey.length <= 4 -> "••••"
            else -> "••••" + apiKey.takeLast(4)
        }

    val canTest: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 供应商名（从 baseUrl 推出，不额外存字段 —— 存两份必然不一致）。 */
    val providerName: String get() = providerNameOf(baseUrl)

    /**
     * 「对话模型」那一行的值，如 `deepseek · deepseek-flash`。
     *
     * 格式取自设计稿（供应商名 · 模型名）。
     */
    val modelLine: String
        get() = if (model.isBlank()) "未配置" else "$providerName · $model"

    /** 「向量模型」那一行。第一批没实现向量模型，如实说明而不是假装有。 */
    val embeddingLine: String get() = "未配置"

    private fun providerNameOf(url: String): String = when {
        url.contains("deepseek") -> "deepseek"
        url.contains("dashscope") || url.contains("aliyun") -> "通义千问"
        url.contains("openai") -> "openai"
        url.isBlank() -> "未填写"
        else -> url.substringAfter("://").substringBefore("/").substringBefore(".")
    }
}

/**
 * AI 设置页的 ViewModel。
 *
 * ## 用一个默认配置承载三个字段
 *
 * 第一批只支持**一个**对话模型配置（设计稿的「配置列表」是后续版本）。
 * 这样设置页只需要三个输入框，用户配起来最快 ——
 * 而用户的目标正是「做完之后我自己去手机上配 api」。
 */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val store: AiSettingsStore,
    private val client: AiClient,
) : ViewModel() {

    private val _ui = MutableStateFlow(AiSettingsUiState())
    val ui: StateFlow<AiSettingsUiState> = _ui.asStateFlow()

    /** 提示词正文的延迟落库（见 [editPromptText]）。 */
    private var promptPersistJob: Job? = null

    init {
        viewModelScope.launch {
            store.refresh()
            val settings = store.state.value
            val chat = settings.chat() ?: settings.providers.firstOrNull()
            _ui.value = AiSettingsUiState(
                baseUrl = chat?.baseUrl ?: DEFAULT_BASE_URL,
                apiKey = chat?.apiKey.orEmpty(),
                model = chat?.model ?: DEFAULT_MODEL,
                temperature = (settings.temperature).toString(),
                maxTokens = settings.maxTokens.toString(),
                topP = settings.topP.toString(),
                frequencyPenalty = settings.frequencyPenalty.toString(),
                deepThinking = settings.deepThinking,
                thinkingDepth = settings.thinkingDepth,
                streamOutput = settings.streamOutput,
                showTokens = settings.showTokens,
                prompts = settings.prompts,
            )
        }
    }

    /**
     * 深度思考**默认值**（设置页）。
     *
     * 与输入框那颗 chip 是不同层级：这里管"默认开不开"，
     * chip 管"这一次开不开"。chip 的改动不回写设置 —— 否则
     * 用户临时关一次，下次进对话发现默认也被关了。
     */
    fun setDeepThinking(value: Boolean) {
        _ui.value = _ui.value.copy(deepThinking = value)
        viewModelScope.launch { store.update { it.copy(deepThinking = value) } }
    }

    fun setThinkingDepth(depth: ThinkingDepth) {
        _ui.value = _ui.value.copy(thinkingDepth = depth)
        viewModelScope.launch { store.update { it.copy(thinkingDepth = depth) } }
    }

    fun setStreamOutput(value: Boolean) {
        _ui.value = _ui.value.copy(streamOutput = value)
        viewModelScope.launch { store.update { it.copy(streamOutput = value) } }
    }

    /**
     * 显示 token 消耗（用户 2026-10-04）。
     *
     * 与「深度思考」那种"默认值"不同，它是**纯粹的显示开关** ——
     * 不影响请求，只影响状态行右端画不画那一格。
     */
    fun setShowTokens(value: Boolean) {
        _ui.value = _ui.value.copy(showTokens = value)
        viewModelScope.launch { store.update { it.copy(showTokens = value) } }
    }

    // ---------------------------------------------------------------- 提示词

    /**
     * 选中一组提示词（**单选**）。
     *
     * 用户口径：提示词就是单选 —— 切换即替换当前生效的那一组，不是叠加。
     *（我第一版做成了多选开关，那是错的。）
     *
     * @param target 改的是**聊天**那套还是**助手**那套。两套各自记着自己选了哪组。
     */
    fun selectPrompt(target: PromptTarget, id: String) {
        updatePrompts(target) { it.copy(activeId = id) }
    }

    /**
     * 改一组提示词的正文（就地编辑框）。
     *
     * ## ⚠️ 落库必须**合并**，否则编辑器卡到没法用
     *
     * 用户报「编辑框还卡的要死」。根因是这里原来每敲一个字就
     * `store.update { ... }` 一次 —— 而 `AiSettingsStore.update` 会把
     * **整个设置对象序列化成一段 JSON 再写一次 SQLite**（见该类注释：
     * 供应商列表不适合拆键，所以整体序列化）。
     * 于是一个字 = 一次全量序列化 + 一次磁盘写，连续打字时输入法直接被拖住。
     *
     * 现在分成两半：
     *  · **界面状态立刻更新** —— 输入必须跟手，这一半不能省；
     *  · **落库等手停下来**（[PROMPT_PERSIST_DEBOUNCE_MS]）—— 中途的按键直接丢弃。
     *
     * 用 ViewModel 的 job 而不是在 composable 里 debounce：
     * 编辑器被回收时 composable 的作用域就没了，最后一次输入会丢；
     * ViewModel 活得比它久。
     */
    fun editPromptText(target: PromptTarget, id: String, text: String) {
        val next = _ui.value.promptSet(target).overrides + (id to text)
        updatePrompts(target, persist = false) { it.copy(overrides = next) }

        promptPersistJob?.cancel()
        promptPersistJob = viewModelScope.launch {
            delay(PROMPT_PERSIST_DEBOUNCE_MS)
            store.update { s ->
                s.copy(prompts = s.prompts + (target to s.promptSet(target).copy(overrides = next)))
            }
        }
    }

    /**
     * 删掉一组提示词。
     *
     * **只有「系统默认」不可删**（用户口径）—— 它是唯一的兜底项，
     * 删了之后请求里就再没有系统提示了。
     * 内置的另外两组、以及用户自己加的组，都可以删。
     *
     * 内置组是代码常量，删不掉，所以在 [PromptSet.removed] 里记账。
     */
    fun deletePrompt(target: PromptTarget, id: String) {
        if (id == AiSettings.SYSTEM_DEFAULT) return

        val set = _ui.value.promptSet(target)
        val isBuiltIn = builtInPromptsFor(target).any { it.id == id }

        updatePrompts(target) { current ->
            current.copy(
                custom = if (isBuiltIn) current.custom else current.custom.filterNot { it.id == id },
                removed = if (isBuiltIn) current.removed + id else current.removed,
                overrides = current.overrides - id,
                // 删掉的正好是选中的那组 → 回到「系统默认」，避免指向已不存在的组
                activeId = if (set.activeId == id) AiSettings.SYSTEM_DEFAULT else current.activeId,
            )
        }
    }

    /**
     * 加一组自定义提示词，并直接选中它 —— 用户可以立刻开始写。
     *
     * 名字从「自定义 N」起，N 取当前**没被占用**的最小值：
     * 删掉「自定义 1」再加，不该变成「自定义 3」。
     */
    fun addPrompt(target: PromptTarget) {
        val groups = _ui.value.promptGroupsFor(target)
        var n = 1
        while (groups.any { it.name == "自定义 $n" }) n++

        val entry = PromptEntry(
            id = "custom-$n-${System.currentTimeMillis()}",
            name = "自定义 $n",
            text = "",
            builtIn = false,
        )
        updatePrompts(target) { it.copy(custom = it.custom + entry, activeId = entry.id) }
    }

    /**
     * 改某一套提示词配置的**唯一入口**。
     *
     * 界面状态立刻更新、落库可以延后 —— 把所有"改一套配置"的操作都收敛到
     * 这一处，就不会出现"某个方法忘了同步另一半"（两套并存时最容易出的错）。
     *
     * @param persist 是否立刻落库。编辑正文那条路传 false（它自己 debounce）。
     */
    private fun updatePrompts(
        target: PromptTarget,
        persist: Boolean = true,
        transform: (PromptSet) -> PromptSet,
    ) {
        val next = transform(_ui.value.promptSet(target))
        _ui.value = _ui.value.copy(prompts = _ui.value.prompts + (target to next))

        if (persist) {
            viewModelScope.launch {
                store.update { s -> s.copy(prompts = s.prompts + (target to next)) }
            }
        }
    }

    /** 配置页的开关：关掉后该配置不出现在选择列表里。 */
    fun setEnabled(value: Boolean) {
        _ui.value = _ui.value.copy(enabled = value)
        viewModelScope.launch {
            store.update { settings ->
                settings.copy(providers = settings.providers.map { it.copy(enabled = value) })
            }
        }
    }

    fun edit(field: Field) {
        _ui.value = _ui.value.copy(editing = field)
    }

    fun cancelEdit() {
        _ui.value = _ui.value.copy(editing = null)
    }

    fun currentValueOf(field: Field): String = when (field) {
        Field.BaseUrl -> _ui.value.baseUrl
        Field.ApiKey -> _ui.value.apiKey
        Field.Model -> _ui.value.model
        Field.Temperature -> _ui.value.temperature
        Field.MaxTokens -> _ui.value.maxTokens
        Field.TopP -> _ui.value.topP
        Field.FrequencyPenalty -> _ui.value.frequencyPenalty
    }

    /** 提交一个字段的编辑。 */
    fun commit(field: Field, value: String) {
        val text = value.trim()
        _ui.value = when (field) {
            Field.BaseUrl -> _ui.value.copy(baseUrl = text, editing = null, testResult = null)
            Field.ApiKey -> _ui.value.copy(apiKey = text, editing = null, testResult = null)
            Field.Model -> _ui.value.copy(model = text, editing = null, testResult = null)
            Field.Temperature -> _ui.value.copy(temperature = text, editing = null)
            Field.MaxTokens -> _ui.value.copy(maxTokens = text, editing = null)
            Field.TopP -> _ui.value.copy(topP = text, editing = null)
            Field.FrequencyPenalty -> _ui.value.copy(frequencyPenalty = text, editing = null)
        }
        persist()
    }

    private fun persist() {
        val state = _ui.value
        viewModelScope.launch {
            store.update { settings ->
                val existing = settings.providers.firstOrNull()
                val provider = (existing ?: AiProviderConfig(id = DEFAULT_ID)).copy(
                    baseUrl = state.baseUrl,
                    apiKey = state.apiKey,
                    model = state.model,
                    name = existing?.name ?: "默认",
                    enabled = true,
                )
                settings.copy(
                    providers = if (existing == null) listOf(provider) else settings.providers.map {
                        if (it.id == provider.id) provider else it
                    },
                    // 没选中时自动选上 —— 否则用户填完了却"没配置"
                    activeChatId = settings.activeChatId ?: provider.id,
                    temperature = state.temperature.toDoubleOrNull() ?: settings.temperature,
                    maxTokens = state.maxTokens.toIntOrNull() ?: settings.maxTokens,
                )
            }
        }
    }

    /**
     * 测试连接。
     *
     * 用**一次真实的最小请求**（`max_tokens = 1`），而不是只探域名 ——
     * 那样只能证明"域名通了"，证明不了 Key 有效、模型名对。
     * 而配错这两样恰恰是最常见的失败原因。
     */
    fun testConnection() {
        val state = _ui.value
        if (!state.canTest || state.testing) return
        _ui.value = state.copy(testing = true, testResult = null)

        viewModelScope.launch {
            val config = AiProviderConfig(
                id = DEFAULT_ID,
                baseUrl = state.baseUrl,
                apiKey = state.apiKey,
                model = state.model,
            )
            val result = client.testConnection(config)
            _ui.value = _ui.value.copy(
                testing = false,
                testResult = result.fold(
                    onSuccess = { "连接成功" },
                    onFailure = { e -> "失败：${e.message ?: "未知原因"}" },
                ),
            )
        }
    }

    private companion object {
        const val DEFAULT_ID = "default-chat"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"

        /** 提示词正文的落库延迟：够让手停下来，又不至于切页时丢字。 */
        const val PROMPT_PERSIST_DEBOUNCE_MS = 400L
    }
}
