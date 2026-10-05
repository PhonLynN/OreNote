package com.phonlynn.oreplan.domain.sync.r2

/**
 * `ListObjectsV2` 响应的解析。
 *
 * ## 为什么手写而不是用 XML 解析器
 *
 * `ListObjectsV2` 的响应结构**极其固定**（一层 `Contents`，里面 `Key` / `ETag` / `Size`），
 * 而引入 XML 解析器会带来两个真实成本：
 *  ① 单元测试里 Android 的 `XmlPullParser` 不可用，得额外引 Robolectric；
 *  ② 通用解析器要处理命名空间、实体、CDATA 等本场景不会出现的东西。
 *
 * 手写解析的前提是**只认这一种形状**，所以这里做三件事保证安全：
 *  · 只按标签切分，不假设标签顺序（`Size` 可能在 `Key` 之前）；
 *  · XML 实体做最小必要反转义（对象名里可能有 `&amp;`、`&quot;`）；
 *  · 解析不出来就返回空/跳过该条，而不是抛异常 —— 一条坏记录不该让整次同步失败。
 */
object R2ListParser {

    data class Parsed(val keys: List<R2Client.ObjectInfo>, val nextToken: String?)

    fun parse(xml: String): Parsed {
        val keys = ArrayList<R2Client.ObjectInfo>()
        // 按 <Contents> 切块 —— 每块是一个对象。用非贪婪避免跨块匹配。
        val contentsRegex = Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL)
        contentsRegex.findAll(xml).forEach { match ->
            val block = match.groupValues[1]
            val key = tag(block, "Key")
            if (key != null) {
                keys += R2Client.ObjectInfo(
                    key = key,
                    // ETag 带双引号是 S3 的惯例，去掉后与 If-Match 头里的格式一致
                    eTag = tag(block, "ETag")?.trim('"').orEmpty(),
                    size = tag(block, "Size")?.toLongOrNull() ?: 0L,
                )
            }
        }
        return Parsed(keys = keys, nextToken = tag(xml, "NextContinuationToken"))
    }

    /** 取某个标签的值；不存在或为空返回 null。 */
    private fun tag(xml: String, name: String): String? {
        val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        val raw = m.groupValues[1]
        return unescape(raw).takeIf { it.isNotEmpty() }
    }

    /**
     * 最小必要反转义。
     *
     * ⚠️ `&amp;` **必须最后替换** —— 否则 `&amp;lt;` 会被先当成 `&amp;` 处理成 `<`，
     * 再被后续规则误伤，结果比输入更糟。
     */
    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
