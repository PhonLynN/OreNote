package com.phonlynn.oreplan.domain.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份里的**设置白名单**与**密钥脱敏**。
 *
 * 用户口径：「一切用户能够自定义的东西都应该能进备份，除了 api 密钥这种东西」。
 *
 * ## 为什么这组测试的口气比较重
 *
 * 因为漏一个的后果不对称：
 *
 * · 白名单漏一项 → 用户发现某个设置没恢复（**会被发现**）
 * · 密钥漏一个 → **sk-… 明文进了备份文件**，可能被同步到网盘、
 *   发给别人排错、或者上传到聊天工具（**悄无声息**）
 *
 * 所以这里对着"不该出去的东西"反复断言，而不是只测"该出去的出去了"。
 */
class BackupKeysTest {

    // ---------------------------------------------------------------- 白名单

    /** 用户能自定义的都在。 */
    @Test
    fun `该备份的键都在白名单里`() {
        listOf(
            BackupKeys.APP_SETTINGS,       // 外观、提醒、白板…… 全部设置
            BackupKeys.AI_CONVERSATIONS,   // 全部对话
            BackupKeys.AI_SETTINGS,        // AI 配置（Key 会剥掉）
        ).forEach { key ->
            assertTrue("`$key` 应当能进备份", BackupKeys.isRestorable(key))
        }
    }

    /** 同步的**偏好**（不是凭据）也在。 */
    @Test
    fun `同步偏好能进备份`() {
        listOf("sync.enabled", "sync.wifi_only", "sync.auto_sync").forEach { key ->
            assertTrue("`$key` 是用户拨的开关，应当能进备份", BackupKeys.isRestorable(key))
        }
    }

    // ---------------------------------------------------------------- 密钥

    /**
     * ⚠️ **主密钥绝不能进备份**。
     *
     * 它被 Keystore 包裹、**绑定本机** —— 带走既没用，又是一次不必要的外泄。
     */
    @Test
    fun `主密钥不能进备份`() {
        assertFalse(BackupKeys.isRestorable("sync.master_key"))
        assertTrue("而且要在 NEVER 清单里留下'为什么'", BackupKeys.NEVER.contains("sync.master_key"))
    }

    /** R2 的 Access Key 与 Secret 同样不能。 */
    @Test
    fun `R2 凭据不能进备份`() {
        listOf("sync.r2_access_key", "sync.r2_secret").forEach { key ->
            assertFalse("`$key` 是凭据", BackupKeys.isRestorable(key))
        }
    }

    /**
     * ⚠️ **恢复时也要过滤**。
     *
     * 备份文件是用户手里可编辑的 JSON。万一有人塞了个 `sync.master_key` 进去，
     * 而我们照单全收 —— 那就等于**让外部文件往本机密钥槽里写东西**。
     */
    @Test
    fun `恢复时拒绝白名单外的键`() {
        listOf(
            "sync.master_key",
            "sync.r2_secret",
            "sync.r2_access_key",
            "随便一个没见过的键",
        ).forEach { key ->
            assertFalse("`$key` 不该被写回库", BackupKeys.isRestorable(key))
        }
    }

    /** 「凭据」与「偏好」是**同一个前缀下的两类东西** —— 不能按前缀一刀切。 */
    @Test
    fun `同一个前缀里凭据与偏好要分开`() {
        // 偏好：可以带走
        assertTrue(BackupKeys.isRestorable("sync.r2_bucket"))
        assertTrue(BackupKeys.isRestorable("sync.r2_account_id"))
        // 凭据：不可以
        assertFalse(BackupKeys.isRestorable("sync.r2_secret"))
        assertFalse(BackupKeys.isRestorable("sync.r2_access_key"))
    }

    // ---------------------------------------------------------------- 脱敏

    /**
     * ⚠️ **AI 配置里剥掉 API Key，其余原样保留**。
     *
     * 这一条最要紧：配置（模型、温度、提示词）要备份，
     * 但 Key 要出去就完蛋了。而"重填一次 Key"只要十几秒，
     * "丢掉全部对话"却再也回不来。
     */
    @Test
    fun `AI 配置里的 Key 会被剥掉而其余保留`() {
        val raw = """
            {
              "providers": [
                {"id": "p1", "apiKey": "sk-绝密密钥", "model": "deepseek-flash", "baseUrl": "https://api.deepseek.com"}
              ],
              "temperature": 0.7,
              "maxTokens": 393216
            }
        """.trimIndent()

        val scrubbed = BackupKeys.scrub(BackupKeys.AI_SETTINGS, raw)!!

        assertFalse("密钥**绝不能**出现在结果里：$scrubbed", scrubbed.contains("sk-绝密密钥"))
        assertTrue("模型要保留", scrubbed.contains("deepseek-flash"))
        assertTrue("地址要保留", scrubbed.contains("api.deepseek.com"))
        assertTrue("温度要保留", scrubbed.contains("0.7"))
    }

    /** 多个 provider 时**每一个**都要清。 */
    @Test
    fun `多个 provider 的 Key 都会被剥掉`() {
        val raw = """
            {"providers": [
              {"id": "p1", "apiKey": "sk-aaa"},
              {"id": "p2", "apiKey": "sk-bbb"}
            ]}
        """.trimIndent()

        val scrubbed = BackupKeys.scrub(BackupKeys.AI_SETTINGS, raw)!!

        assertFalse(scrubbed.contains("sk-aaa"))
        assertFalse(scrubbed.contains("sk-bbb"))
        assertTrue("两个 provider 都要还在", scrubbed.contains("p1") && scrubbed.contains("p2"))
    }

    /**
     * ⚠️ **解析不了就整条不备份**，绝不"失败就原样返回"。
     *
     * 那个"原样返回"恰好会把 Key 带出去 —— 而这个函数的全部意义就是不带它出去。
     */
    @Test
    fun `解析失败时返回 null 而不是原样输出`() {
        val broken = "{这不是合法 JSON，里面可能还混着 sk-绝密密钥"

        assertNull(
            "解析失败必须返回 null（= 整条不备份）",
            BackupKeys.scrub(BackupKeys.AI_SETTINGS, broken),
        )
    }

    /** 不是 AI 设置的那些键原样通过（它们没有要脱敏的东西）。 */
    @Test
    fun `其它键原样通过`() {
        val json = """{"theme":"dark"}"""

        assertEquals(json, BackupKeys.scrub(BackupKeys.APP_SETTINGS, json))
        // 同步偏好是**普通字符串**（不是 JSON 对象），同样原样通过
        assertEquals("true", BackupKeys.scrub("sync.enabled", "true"))
        assertEquals("""{"enabled":true}""", BackupKeys.scrub("sync.auto_sync", """{"enabled":true}"""))
    }

    /** 脱敏后的产物必须是**合法 JSON**（否则恢复时解析不了）。 */
    @Test
    fun `脱敏结果仍是合法 JSON`() {
        val raw = """{"providers":[{"id":"p1","apiKey":"sk-x","model":"m"}]}"""

        val scrubbed = BackupKeys.scrub(BackupKeys.AI_SETTINGS, raw)!!

        val obj = JSONObject(scrubbed)
        assertEquals("空串而不是删掉这个键", "", obj.getJSONArray("providers").getJSONObject(0).getString("apiKey"))
    }

    /** 没有 providers 字段时也不该崩（正常返回即可）。 */
    @Test
    fun `没有 providers 时不崩`() {
        val raw = """{"temperature":0.7}"""

        assertEquals(raw, BackupKeys.scrub(BackupKeys.AI_SETTINGS, raw))
    }
}
