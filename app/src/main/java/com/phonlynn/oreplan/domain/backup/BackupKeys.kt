package com.phonlynn.oreplan.domain.backup

/**
 * `app_meta` 里哪些键进备份、哪些出设备前要脱敏。
 *
 * ## 为什么是**白名单**
 *
 * 用户口径：「一切用户能够自定义的东西都应该能进备份，除了 api 密钥这种东西」。
 *
 * 用白名单而不是黑名单，是因为**两类错误的代价不对等**：
 *
 * | | 漏了会怎样 |
 * |---|---|
 * | 白名单漏一个 | 少备份一项设置（用户会来问，容易发现） |
 * | 黑名单漏一个 | **密钥泄露进备份文件**（悄无声息） |
 *
 * 而且以后新加一个密钥类的键时，白名单让它**默认安全**。
 *
 * ## 为什么不用"前缀匹配"一把梭
 *
 * 看起来 `ai.*` / `sync.*` 前缀就够了，但同一个前缀下**既有能备份的也有不能的**：
 *
 * ```
 * ai.settings          → API Key 在里面，要备份但必须剥掉 Key
 * ai.conversations     → 全部对话，原样备份
 * sync.enabled         → 同步开关，可以备份
 * sync.master_key      → 主密钥（绑本机 Keystore），绝不能出设备
 * ```
 *
 * 所以既要有键级白名单，也要有"这个键里哪部分要剥掉"的逻辑。
 */
internal object BackupKeys {

    /** 参与备份的键（原样带走）。 */
    val INCLUDED: Set<String> = setOf(
        // 全部用户设置：外观、提醒、白板新卡位置、默认时长……
        APP_SETTINGS,
        // 全部对话 —— 这是换机时最舍不得丢的东西
        AI_CONVERSATIONS,
        // AI 配置（**Key 会被剥掉**，见 scrubAiSettings）
        AI_SETTINGS,
        // 云同步的**偏好**（开关、仅 Wi-Fi、自动同步）—— 凭据会被剥掉
        SYNC_PREFERENCES,
    )

    /**
     * 永远不进备份的键。
     *
     * 显式列出来**只为了在代码里留下"为什么"** —— 白名单模式下它们本来就不会被带走。
     * 哪天有人想改成黑名单，这份清单就是"漏一个就泄密"的名单。
     */
    val NEVER: Set<String> = setOf(
        /** 云同步主密钥。被 Keystore 包裹、**绑定本机** —— 带走也没用，而且不该带。 */
        "sync.master_key",
        /** R2 的 Access Key / Secret（明文存在 SyncSettings 的 JSON 里）。 */
        "sync.r2_access_key",
        "sync.r2_secret",
    )

    // 实际存在的键字面量。
    //
    // ⚠️ 这里**不 import 各 Store 里的常量**：那几个是 private companion 里的
    // 实现细节，为了引用它们而把它们改成 public，等于把"备份知道 AI 内部键名"
    // 这件事做实 —— 而那正是耦合开始的地方。
    //
    // 代价是字面量要跟两边保持一致。所以加了一条测试（`BackupKeyCoverageTest`）
    // 直接扫描源码里的 app_meta 写入点，**键名对不上会跑红**。
    const val APP_SETTINGS = "app_settings_v2"
    const val AI_CONVERSATIONS = "ai.conversations"
    const val AI_SETTINGS = "ai.settings"

    /**
     * 这个键**允许从备份写回库里**吗。
     *
     * ## 为什么恢复时也要过滤
     *
     * 备份文件是用户手里可以随意编辑的 JSON。万一有人（或某个出错的版本）
     * 在里面塞了一个 `sync.master_key`，而我们照单全收 ——
     * 那就等于**让外部文件往本机的密钥槽里写东西**。
     *
     * 导出侧已经过滤过一遍了，这一层是**同一个白名单的第二次使用** ——
     * 出入口用同一份名单，就不会出现"只堵了一头"。
     */
    fun isRestorable(key: String): Boolean =
        key in INCLUDED || key in SYNC_PREFERENCE_KEYS

    /**
     * 云同步偏好所在的键。
     *
     * ⚠️ 同步设置是**一个键一个值**（`sync.enabled` 本身就是一个键），
     * 所以这里不是"一个 JSON"，而是若干独立键 —— 见 [SYNC_PREFERENCE_KEYS]。
     */
    const val SYNC_PREFERENCES = "sync.enabled"

    /**
     * 可以带走的同步**偏好**键。
     *
     * 只列用户自己拨的开关与地址；凭据一律不在其中。
     */
    val SYNC_PREFERENCE_KEYS: Set<String> = setOf(
        "sync.enabled",
        "sync.wifi_only",
        "sync.auto_sync",
        "sync.r2_account_id",
        "sync.r2_bucket",
        "sync.key_prefix",
    )

    /**
     * ⚠️ **带走的设置里，哪些要脱敏**。
     *
     * 目前只有一处：`ai.settings` 里的 **API Key 明文**。
     *
     * 用户口径是「除了 api 密钥这种东西」—— 所以配置本身（模型、温度、
     * 提示词、参数）**要备份**，只有 Key 剥掉。恢复后用户重填一次即可，
     * 而重填 Key 只要十几秒，丢掉的对话却再也回不来。
     *
     * @return 脱敏后的值；无法解析时返回 null（**宁可整条不备份，也不要原样带出去**）
     */
    fun scrub(key: String, value: String): String? = when (key) {
        AI_SETTINGS -> scrubAiSettings(value)
        else -> value
    }

    /**
     * 把 `ai.settings` 里的 API Key 清空。
     *
     * 结构是 `{"providers":[{"apiKey":"sk-…", …}], …}` —— 逐个 provider 清空
     * `apiKey`，其余（模型、地址、温度、提示词…）原样保留。
     *
     * ⚠️ 解析不了就返回 null（**不备份这一项**）。绝不能"解析失败就原样返回" ——
     * 那正好会把 Key 带出去，而这个函数的全部意义就是不带它出去。
     */
    private fun scrubAiSettings(raw: String): String? = runCatching {
        val root = org.json.JSONObject(raw)
        val providers = root.optJSONArray("providers") ?: return@runCatching root.toString()
        for (i in 0 until providers.length()) {
            providers.optJSONObject(i)?.put("apiKey", "")
        }
        root.toString()
    }.getOrNull()
}
