package com.phonlynn.oreplan.core.order

import com.phonlynn.oreplan.data.mapper.AutoPinCodec
import com.phonlynn.oreplan.domain.model.AutoPinRule
import org.json.JSONObject

/**
 * 未写完的新卡片草稿的序列化。纯函数，便于把「往返不丢字段」钉在单测里。
 *
 * 为什么单独抽出来：草稿字段会随卡片功能增加而增长，一旦编码/解码两边漏掉某个字段，
 * 症状是「暂存了但回填丢失了某一项」——很隐蔽。用往返测试盯住它。
 */
object BoardDraftCodec {

    fun encode(p: BoardDraftPayload): String = JSONObject().apply {
        put("type", p.typeKey)
        put("title", p.title)
        put("body", p.body)
        put("color", p.color ?: JSONObject.NULL)
        put("pinned", p.pinned)
        put("secret", p.secret)
        put("secretHint", p.secretHint)
        put("tagId", p.tagId ?: JSONObject.NULL)
        put("widthMode", p.widthMode ?: JSONObject.NULL)
        put("imageLayout", p.imageLayout ?: JSONObject.NULL)
        put("showDate", p.showDate)
        put("reminderAtMillis", p.reminderAtMillis ?: JSONObject.NULL)
        // 动态置顶：与 AutoPinCodec 共用编解码，避免「暂存了但回填丢了一项」。
        put("autoPinKind", AutoPinCodec.kindOf(p.autoPin) ?: JSONObject.NULL)
        put("autoPinRule", p.autoPin?.let { AutoPinCodec.encodeRule(it) } ?: JSONObject.NULL)
        put("autoPinDurationMinutes", p.autoPin?.durationMinutes ?: JSONObject.NULL)
    }.toString()

    /** 解出草稿；空串或非法 JSON 返回 null（表示没有草稿）。 */
    fun decode(raw: String?): BoardDraftPayload? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            BoardDraftPayload(
                typeKey = o.optString("type", "QUICK"),
                title = o.optString("title", ""),
                body = o.optString("body", ""),
                color = o.optString("color").takeIf { it.isNotBlank() && it != "null" },
                pinned = o.optBoolean("pinned", false),
                secret = o.optBoolean("secret", false),
                secretHint = o.optString("secretHint", ""),
                tagId = o.optString("tagId").takeIf { it.isNotBlank() && it != "null" },
                widthMode = o.optString("widthMode").takeIf { it.isNotBlank() && it != "null" },
                imageLayout = o.optString("imageLayout").takeIf { it.isNotBlank() && it != "null" },
                showDate = o.optBoolean("showDate", true),
                reminderAtMillis = o.optLong("reminderAtMillis")
                    .takeIf { o.has("reminderAtMillis") && !o.isNull("reminderAtMillis") },
                autoPin = AutoPinCodec.decode(
                    kind = o.optString("autoPinKind").takeIf { it.isNotBlank() && it != "null" },
                    rule = o.optString("autoPinRule").takeIf { it.isNotBlank() && it != "null" },
                    durationMinutes = if (o.has("autoPinDurationMinutes") && !o.isNull("autoPinDurationMinutes")) {
                        o.optInt("autoPinDurationMinutes", AutoPinRule.DEFAULT_DURATION_MINUTES)
                    } else {
                        null
                    },
                ),
            )
        }.getOrNull()
    }
}

/** 暂存的未完成草稿（只含卡片自身字段；附件不跨会话保留）。 */
data class BoardDraftPayload(
    val typeKey: String,
    val title: String,
    val body: String,
    val color: String?,
    val pinned: Boolean,
    val secret: Boolean,
    val secretHint: String,
    val tagId: String?,
    val widthMode: String?,
    val imageLayout: String?,
    val showDate: Boolean,
    val reminderAtMillis: Long?,
    /** 动态置顶规则。null = 未启用。 */
    val autoPin: AutoPinRule? = null,
) {
    /** 是否有值得暂存的内容（标题或正文非空）。 */
    val hasContent: Boolean get() = title.isNotBlank() || body.isNotBlank()
}
