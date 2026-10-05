package com.phonlynn.oreplan.domain.sync

import org.json.JSONObject

/**
 * 一个顶层实体在云端的样子：**元数据 + 完整字段**。
 *
 * ## 为什么 payload 用 JSON 而不是二进制序列化
 *
 * · 调试时能直接看懂（`R2Client` 抓下来的对象解开就是可读 JSON）；
 * · 加字段不会破坏旧版本 —— 旧版本读到一个不认识的键会忽略它，
 *   而二进制格式（如 protobuf 的字段号）需要额外的兼容纪律；
 * · 与项目已有的 `BackupCodec` 同一套思路（它也是 JSON）。
 *
 * 代价是体积：JSON 比 protobuf 大约 2-3 倍。但这些数据**先被 AES-GCM 加密**，
 * 而加密后的密文不做压缩（会泄露长度信息），所以这个差别在同步流量上是常数级——
 * 个人数据量级下无所谓。
 *
 * ## 为什么带上 `table`
 *
 * 云端对象路径里已有表名，但把表名也放进内容里可以防一件事：
 * **密文被搬到错误的对象名下**。有了 `table` + AAD 绑定 key，
 * 搬运后的对象会因为表名不匹配而被识别出来。
 */
data class SyncEnvelope(
    /** `SyncEntity.table`。 */
    val table: String,
    val id: String,
    /** 修订号：每次本地修改 +1。 */
    val rev: Long,
    /** 逻辑时钟（编码后的字符串）。null = 从未同步。 */
    val hlc: String?,
    val deviceId: String?,
    /** 删除时刻（毫秒）。非 null = 墓碑。 */
    val deletedAt: Long?,
    /** 实体字段。墓碑时为空对象。 */
    val payload: JSONObject,
) {

    val isTombstone: Boolean get() = deletedAt != null

    val hlcValue: Hlc? get() = hlc?.let(Hlc::decode)

    fun toJson(): String = JSONObject().apply {
        put("table", table)
        put("id", id)
        put("rev", rev)
        put("hlc", hlc ?: JSONObject.NULL)
        put("deviceId", deviceId ?: JSONObject.NULL)
        put("deletedAt", deletedAt ?: JSONObject.NULL)
        put("payload", payload)
    }.toString()

    companion object {
        /**
         * 解析。**失败返回 null 而不是抛异常** ——
         * 一个坏对象不该让整次同步崩掉（它可能来自旧版本、或写入时中断）。
         */
        fun fromJson(text: String): SyncEnvelope? = runCatching {
            val o = JSONObject(text)
            SyncEnvelope(
                table = o.getString("table"),
                id = o.getString("id"),
                rev = o.optLong("rev", 0L),
                hlc = o.optStringOrNull("hlc"),
                deviceId = o.optStringOrNull("deviceId"),
                deletedAt = o.optLongOrNull("deletedAt"),
                payload = o.optJSONObject("payload") ?: JSONObject(),
            )
        }.getOrNull()

        /**
         * `optString` 在键不存在时返回空串、在值为 JSON null 时返回 `"null"` 字符串 ——
         * 两个都不是我们要的语义。这个扩展把两者都正确归一成 null。
         */
        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).takeIf { it.isNotEmpty() }
        }

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (!has(key) || isNull(key)) null else optLong(key)
    }
}
