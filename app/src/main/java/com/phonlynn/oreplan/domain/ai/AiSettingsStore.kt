package com.phonlynn.oreplan.domain.ai

import com.phonlynn.oreplan.data.local.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 设置的持久化。
 *
 * ## 存在哪
 *
 * `app_meta`（KV 表）里的几个键 —— 与 `SyncSettings` / `SyncKeyVault` 同一策略：
 * **不加表、不动 schema**。v11 仍然冻结。
 *
 * ## 为什么整体存成一段 JSON 而不是拆成很多键
 *
 * 供应商配置是**一个列表**（可以有多个），键值表不适合表达列表。
 * 拆成 `ai.provider.0.baseUrl` 这种扁平键会让"增删改一条"变成一堆键的读写，
 * 且容易残留。整体序列化成一个 JSON 值，读写都是原子的。
 *
 * ## API Key 明文存
 *
 * 与 `SyncKeyVault` 同一判断：Android 上没有"锁屏时仍可用"的硬件级密钥，
 * 且用户要求「不做登录」。真正的保护是**应用私有目录 + 设备锁屏**。
 * 这一点在两个地方写了同样的注释，是为了避免后来者以为是漏了加密。
 */
@Singleton
class AiSettingsStore @Inject constructor(
    private val database: AppDatabase,
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(AiSettings())

    /** 当前设置（内存缓存；首次访问前先调 [refresh]）。 */
    val state: StateFlow<AiSettings> = _state.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        _state.value = read()
    }

    /** 读一次数据库。任何解析失败都退回默认值 —— 配置坏了不该让 App 起不来。 */
    private suspend fun read(): AiSettings = withContext(Dispatchers.IO) {
        val dao = database.appMetaDao()
        val raw = dao.find(KEY_SETTINGS)?.value ?: return@withContext AiSettings()
        runCatching { fromJson(JSONObject(raw)) }.getOrElse { AiSettings() }
    }

    /** 原子更新。 */
    suspend fun update(transform: (AiSettings) -> AiSettings) = mutex.withLock {
        val next = transform(_state.value)
        withContext(Dispatchers.IO) {
            database.appMetaDao().upsert(
                com.phonlynn.oreplan.data.local.entity.AppMetaEntity(
                    key = KEY_SETTINGS,
                    value = toJson(next).toString(),
                ),
            )
        }
        _state.value = next
    }

    // ---------------------------------------------------------------- 序列化

    private fun toJson(settings: AiSettings): JSONObject = AiSettingsCodec.toJson(settings)

    private fun fromJson(obj: JSONObject): AiSettings = AiSettingsCodec.fromJson(obj)

    companion object {
        const val KEY_SETTINGS = "ai.settings"

        /**
         * 给测试用的序列化入口。
         *
         * 这两个方法本来只被 [read] / [update] 用，而那两个都依赖数据库 ——
         * 于是测试里只好**自己抄一份序列化逻辑**，抄的那份和真代码会走偏，
         * 测了个寂寞。把它们开出来，测试测的就是真正在跑的那份。
         */
        @JvmStatic
        fun toJsonForTest(settings: AiSettings): JSONObject = AiSettingsCodec.toJson(settings)

        @JvmStatic
        fun fromJsonForTest(obj: JSONObject): AiSettings = AiSettingsCodec.fromJson(obj)
    }
}

/**
 * [AiSettings] 的 JSON 序列化。
 *
 * 单独抽出来是因为它**不依赖数据库** —— 抽出来测试就能直接测它，
 * 不用为了一个纯函数去搭 Room。store 里的 `read` / `update` 只负责存取。
 */
internal object AiSettingsCodec {

    fun toJson(settings: AiSettings): JSONObject = JSONObject().apply {
        put(
            "providers",
            JSONArray().apply {
                settings.providers.forEach { p ->
                    put(
                        JSONObject().apply {
                            put("id", p.id)
                            put("providerType", p.providerType.name)
                            put("name", p.name)
                            put("baseUrl", p.baseUrl)
                            put("apiKey", p.apiKey)
                            put("model", p.model)
                            put("displayName", p.displayName)
                            put("enabled", p.enabled)
                            put("supportsTools", p.supportsTools)
                        },
                    )
                }
            },
        )
        put("activeChatId", settings.activeChatId ?: JSONObject.NULL)
        put("activeEmbeddingId", settings.activeEmbeddingId ?: JSONObject.NULL)
        put("temperature", settings.temperature)
        put("maxTokens", settings.maxTokens)
        put("topP", settings.topP)
        put("frequencyPenalty", settings.frequencyPenalty)

        /*
         * 提示词：**聊天与助手各一套**，存成 promptSets 下的两个对象。
         *
         * 旧的扁平字段（activePromptId / customPrompts / …）**不再写** ——
         * 但读取时仍然认（见 fromJson 的迁移），这样用户降级回旧版本时
         * 至少还能读到聊天那套。
         */
        put("promptSets", JSONObject().apply {
            PromptTarget.entries.forEach { target ->
                put(target.name, promptSetToJson(settings.promptSet(target)))
            }
        })

        put("enableTools", settings.enableTools)
        put("streamOutput", settings.streamOutput)
        put("deepThinking", settings.deepThinking)
        put("thinkingDepth", settings.thinkingDepth.name)
        put("showTokens", settings.showTokens)
    }

    fun fromJson(obj: JSONObject): AiSettings {
        val providers = buildList {
            val array = obj.optJSONArray("providers") ?: JSONArray()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { add(providerFromJson(it)) }
            }
        }
        return AiSettings(
            providers = providers,
            activeChatId = obj.optStringOrNull("activeChatId"),
            activeEmbeddingId = obj.optStringOrNull("activeEmbeddingId"),
            temperature = obj.optDouble("temperature", 0.7),
            maxTokens = obj.optInt("maxTokens", 2048),
            topP = obj.optDouble("topP", 0.90),
            frequencyPenalty = obj.optDouble("frequencyPenalty", 1.05),
            enableTools = obj.optBoolean("enableTools", true),
            streamOutput = obj.optBoolean("streamOutput", true),
            prompts = readPromptSets(obj),
            deepThinking = obj.optBoolean("deepThinking", false),
            thinkingDepth = runCatching { ThinkingDepth.valueOf(obj.optString("thinkingDepth")) }
                .getOrDefault(ThinkingDepth.MEDIUM),
            // 旧配置里没有这个键 → 用默认值 true（用户明确要过这个显示）
            showTokens = obj.optBoolean("showTokens", true),
        )
    }

    /**
     * 读提示词两套配置，**并把旧版本的扁平字段迁移进来**。
     *
     * ## 迁移规则
     *
     * 旧版本只有一套（`activePromptId` / `customPrompts` / `promptOverrides` /
     * `removedPrompts` 平铺在顶层）。它读进 **聊天** 那套，助手那套用默认值 ——
     * 理由：
     *
     * · 聊天是**默认模式**，旧配置绝大多数是在聊天模式下攒的
     * · 助手那套是新的（内置正文也完全不同），把聊天那套硬套过去反而更糟
     *
     * ⚠️ **迁移只在读取时发生、不回写**。也就是说旧字段一直留在 JSON 里 ——
     * 这样用户降级回旧版本时仍能读到聊天那套，而不是看到一个空配置。
     */
    private fun readPromptSets(obj: JSONObject): Map<PromptTarget, PromptSet> {
        val sets = PromptTarget.entries.associateWith { PromptSet() }.toMutableMap()

        // ① 新格式
        obj.optJSONObject("promptSets")?.let { o ->
            PromptTarget.entries.forEach { target ->
                o.optJSONObject(target.name)?.let { sets[target] = promptSetFromJson(it) }
            }
        }

        // ② 旧格式 → 聊天那套（仅当新格式里聊天是空的，避免覆盖）
        if (sets.getValue(PromptTarget.CHAT) == PromptSet()) {
            val legacy = legacyPromptSet(obj)
            if (legacy != PromptSet()) sets[PromptTarget.CHAT] = legacy
        }
        return sets
    }

    /** 把旧版本的四个扁平字段拼成一套。 */
    private fun legacyPromptSet(obj: JSONObject): PromptSet = PromptSet(
        // 旧配置里存的是中文 id，读的时候归一化成 slug
        activeId = (obj.optStringOrNull("activePromptId") ?: AiSettings.SYSTEM_DEFAULT)
            .let(AiSettings::normalizePromptId),
        custom = buildList {
            val arr = obj.optJSONArray("customPrompts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o ->
                    add(PromptEntry(o.optString("id"), o.optString("name"), o.optString("text"), builtIn = false))
                }
            }
        },
        overrides = buildMap {
            obj.optJSONObject("promptOverrides")?.let { o ->
                o.keys().forEach { k ->
                    o.optString(k).takeIf { s -> s.isNotEmpty() }
                        ?.let { s -> put(AiSettings.normalizePromptId(k), s) }
                }
            }
        },
        removed = buildSet {
            val arr = obj.optJSONArray("removedPrompts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }
                    ?.let { add(AiSettings.normalizePromptId(it)) }
            }
        }.minus(AiSettings.SYSTEM_DEFAULT), // 「系统默认」永远不在删除集合里，兜一层
    )

    private fun promptSetToJson(set: PromptSet): JSONObject = JSONObject().apply {
        put("activeId", set.activeId)
        put("custom", JSONArray().apply {
            set.custom.forEach { e ->
                put(JSONObject().apply { put("id", e.id); put("name", e.name); put("text", e.text) })
            }
        })
        put("overrides", JSONObject().apply { set.overrides.forEach { (k, v) -> put(k, v) } })
        put("removed", JSONArray().apply { set.removed.forEach { put(it) } })
    }

    private fun promptSetFromJson(o: JSONObject): PromptSet = PromptSet(
        activeId = (o.optStringOrNull("activeId") ?: AiSettings.SYSTEM_DEFAULT)
            .let(AiSettings::normalizePromptId),
        custom = buildList {
            val arr = o.optJSONArray("custom") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { e ->
                    add(PromptEntry(e.optString("id"), e.optString("name"), e.optString("text"), builtIn = false))
                }
            }
        },
        overrides = buildMap {
            o.optJSONObject("overrides")?.let { m ->
                m.keys().forEach { k ->
                    m.optString(k).takeIf { s -> s.isNotEmpty() }
                        ?.let { s -> put(AiSettings.normalizePromptId(k), s) }
                }
            }
        },
        removed = buildSet {
            val arr = o.optJSONArray("removed") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }
                    ?.let { add(AiSettings.normalizePromptId(it)) }
            }
        }.minus(AiSettings.SYSTEM_DEFAULT),
    )

    private fun providerFromJson(obj: JSONObject): AiProviderConfig = AiProviderConfig(
        id = obj.optString("id"),
        // 未知的协议名退回默认值，而不是崩 —— 将来若删掉某个枚举值，
        // 旧配置仍能读出来（虽然那个配置可能不可用）
        providerType = runCatching {
            ProviderType.valueOf(obj.optString("providerType"))
        }.getOrDefault(ProviderType.OPENAI_COMPATIBLE),
        name = obj.optString("name"),
        baseUrl = obj.optString("baseUrl"),
        apiKey = obj.optString("apiKey"),
        model = obj.optString("model"),
        displayName = obj.optString("displayName"),
        enabled = obj.optBoolean("enabled", true),
        supportsTools = obj.optBoolean("supportsTools", true),
    )

    /**
     * 读一个「本该是字符串」的字段。三种异常输入都归一成 `null`：
     *
     * | 输入 | `optString` 给什么 | 这里给什么 |
     * |---|---|---|
     * | 键不存在 | `""` | `null` |
     * | 值是 JSON `null` | 字符串 `"null"` | `null` |
     * | **值是对象/数组/数字** | **它的 JSON 文本** | `null` |
     *
     * ## ⚠️ 第三行是真 bug（用户报的「覆盖安装后提示词被覆盖」）
     *
     * `optString` 在**类型不对时不报错**，而是把那个值序列化成文本返回：
     *
     * ```
     * {"activePromptId": {"坏": 1}}   →  得到  "{\"坏\":1}"
     * ```
     *
     * 于是外层 `runCatching` **抓不到任何异常**，一个垃圾值就这样一路传进
     * 业务逻辑。用户看到的是"我的设置没了/选中项没了"，而库里那份数据
     * 其实**完好无损** —— 只是读的时候静默读错。
     *
     * 静默的类型错误是最难查的一类，所以这里显式判类型。
     */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        // ⚠️ 必须判类型 —— optString 对非字符串会返回 JSON 文本，不报错
        if (get(key) !is String) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
