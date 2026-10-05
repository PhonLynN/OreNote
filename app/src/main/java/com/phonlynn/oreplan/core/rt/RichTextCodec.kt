package com.phonlynn.oreplan.core.rt

import org.json.JSONArray
import org.json.JSONObject

/**
 * [RichDoc] ↔ 落库字符串（2026-09-23 重写定稿）。
 *
 * # 格式
 *
 * ```json
 * {"v":2,"lines":[
 *   {"t":"标题","k":"text","s":[{"a":0,"b":3,"bold":true}]},
 *   {"t":"列表","k":"bullet"},
 *   {"t":"待办","k":"check","c":true}
 * ]}
 * ```
 *
 * 字段：`v` 版本 / `t` 纯正文 / `k` 段类型 / `c` 勾选 / `s` 样式区间
 * （`a`/`b` 起止；`bold`/`u`/`strike`/`i`/`hl` 五种属性，只在为真时写出）。
 *
 * # v1 → v2 的差别
 *
 * **只有一处**：新增了「显示层符号」的概念，但符号**不落库**。
 * 所以 `t` 的内容、`k` 的取值、`s` 的语义完全不变——
 * v1 的数据读进来就是 v2，写出去标成 v2。无迁移、无风险。
 *
 * # 兜底（绝不丢字）
 *
 * 解析失败时（更早的纯文本、Markdown 标记残留、手工改坏的 JSON），
 * 把整段内容当作**无格式纯文本**读入。老卡片打开后只是没有格式，而不是空白。
 *
 * # 防御性剥离
 *
 * 万一历史数据里混进了 0 宽字符或列表符号（例如某一版实现曾把它们写进去），
 * 解码时一并剥掉，避免它们在正文里累积成不可见字符。
 */
object RichTextCodec {

    /** 当前格式版本。 */
    const val VERSION = 2

    /**
     * 0 宽空格（U+200B）。
     *
     * **当前架构不再产生它**（文本流里只有正文与 `\n`）。
     * 这里保留常量只有一个用途：清洗历史上被写进正文的这类字符。
     */
    private const val ZWSP: Char = '\u200B'

    private const val KEY_VERSION = "v"
    private const val KEY_LINES = "lines"
    private const val KEY_TEXT = "t"
    private const val KEY_KIND = "k"
    private const val KEY_CHECKED = "c"
    private const val KEY_SPANS = "s"
    private const val KEY_SPAN_START = "a"
    private const val KEY_SPAN_END = "b"
    private const val KEY_SPAN_BOLD = "bold"
    private const val KEY_SPAN_UNDERLINE = "u"
    private const val KEY_SPAN_STRIKE = "strike"
    private const val KEY_SPAN_ITALIC = "i"
    private const val KEY_SPAN_HIGHLIGHT = "hl"

    // ---------------------------------------------------------------- 编码

    fun encode(doc: RichDoc): String {
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)
        val arr = JSONArray()
        doc.lines.forEach { arr.put(lineToJson(it)) }
        root.put(KEY_LINES, arr)
        return root.toString()
    }

    private fun lineToJson(line: RichLine): JSONObject = JSONObject().apply {
        // 落库前剥掉显示层字符：JSON 里的 t 永远是纯正文。
        put(KEY_TEXT, sanitizeContent(line.text))
        put(KEY_KIND, kindToKey(line.kind))
        if (line.kind == RichLineKind.CHECK) put(KEY_CHECKED, line.checked)
        val spans = line.normalized().spans
        if (spans.isNotEmpty()) {
            val sa = JSONArray()
            spans.forEach { sp ->
                sa.put(JSONObject().apply {
                    put(KEY_SPAN_START, sp.start)
                    put(KEY_SPAN_END, sp.end)
                    if (sp.bold) put(KEY_SPAN_BOLD, true)
                    if (sp.underline) put(KEY_SPAN_UNDERLINE, true)
                    if (sp.strike) put(KEY_SPAN_STRIKE, true)
                    if (sp.italic) put(KEY_SPAN_ITALIC, true)
                    if (sp.highlight) put(KEY_SPAN_HIGHLIGHT, true)
                })
            }
            put(KEY_SPANS, sa)
        }
    }

    private fun kindToKey(kind: RichLineKind): String = when (kind) {
        RichLineKind.TEXT -> "text"
        RichLineKind.BULLET -> "bullet"
        RichLineKind.ORDERED -> "ordered"
        RichLineKind.CHECK -> "check"
    }

    private fun keyToKind(key: String?): RichLineKind = when (key) {
        "bullet" -> RichLineKind.BULLET
        "ordered" -> RichLineKind.ORDERED
        "check" -> RichLineKind.CHECK
        else -> RichLineKind.TEXT
    }

    // ---------------------------------------------------------------- 解码

    fun decode(raw: String?): RichDoc {
        if (raw.isNullOrEmpty()) return RichDoc.EMPTY
        val trimmed = raw.trim()
        // 新格式一定以 { 开头；否则直接按纯文本处理（不做两次解析尝试）。
        if (!trimmed.startsWith("{")) return plainFallback(raw)
        return runCatching { parseJson(trimmed) }.getOrElse { plainFallback(raw) }
    }

    private fun parseJson(text: String): RichDoc {
        val root = JSONObject(text)
        val arr = root.optJSONArray(KEY_LINES) ?: return RichDoc.EMPTY
        val lines = ArrayList<RichLine>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            lines.add(lineFromJson(obj))
        }
        if (lines.isEmpty()) return RichDoc.EMPTY
        return RichDoc(lines)
    }

    private fun lineFromJson(obj: JSONObject): RichLine {
        val body = sanitizeContent(obj.optString(KEY_TEXT, ""))
        val kind = keyToKind(obj.optString(KEY_KIND, "text"))
        val checked = obj.optBoolean(KEY_CHECKED, false) && kind == RichLineKind.CHECK
        val spans = ArrayList<RichSpan>()
        obj.optJSONArray(KEY_SPANS)?.let { sa ->
            for (i in 0 until sa.length()) {
                val so = sa.optJSONObject(i) ?: continue
                spans.add(
                    RichSpan(
                        start = so.optInt(KEY_SPAN_START, 0),
                        end = so.optInt(KEY_SPAN_END, 0),
                        bold = so.optBoolean(KEY_SPAN_BOLD, false),
                        underline = so.optBoolean(KEY_SPAN_UNDERLINE, false),
                        strike = so.optBoolean(KEY_SPAN_STRIKE, false),
                        italic = so.optBoolean(KEY_SPAN_ITALIC, false),
                        highlight = so.optBoolean(KEY_SPAN_HIGHLIGHT, false),
                    ),
                )
            }
        }
        // normalized() 保证区间被裁到文字长度内、且已合并。
        return RichLine(body, kind, checked, spans).normalized()
    }

    /** 兜底：整段当作无格式纯文本，按行拆段。 */
    private fun plainFallback(raw: String): RichDoc =
        RichDoc(raw.split('\n').map { RichLine(sanitizeContent(it)) })

    // ---------------------------------------------------------------- 纯正文净化

    /**
     * 剥掉不该出现在纯正文里的显示层字符（标记字符 + 紧随的宽度字符）。
     *
     * 两个用途：
     * 1. **防漏** —— 合并两行时行的前缀会落到正文中间（见 [RichTextMark.stripMarks]），
     *    必须在落库前清理，否则会存成带肉眼可见空格的正文；
     * 2. **清历史脏数据** —— 早期版本把 0 宽空格写进过正文，那些数据可能还在库里。
     *
     * 幂等：已经干净的字符串原样返回。编解码两端都调用它。
     */
    fun sanitizeContent(text: String): String = RichTextMark.stripMarks(text)
}
