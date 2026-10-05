package com.phonlynn.oreplan.data.mapper

import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

/**
 * 扩展抽屉的 JSON 编解码。
 *
 * 用 `org.json`（Android 框架自带，单元测试里由 `testImplementation(libs.org.json)` 提供），
 * **不引入任何新依赖** —— 这是「体积轻便」那条标准的一部分。
 *
 * 解析一律**不抛异常**：抽屉里是附属信息，脏数据不该让整张卡片读不出来
 * （与 `AutoPinCodec` / Mapper 层「认不出就降级」的既有约定一致）。
 */
internal object ExtCodec {

    /** 空抽屉不写库（省空间，也让「有没有抽屉」等价于「这一行在不在」）。 */
    fun encode(map: ExtMap): String? {
        if (map.isEmpty) return null
        return toJsonObject(map).toString()
    }

    fun decode(raw: String?): ExtMap {
        if (raw.isNullOrBlank()) return ExtMap.EMPTY
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return ExtMap.EMPTY
        return fromJsonObject(obj)
    }

    /**
     * 转成 JSON 对象。备份要把抽屉**原样嵌进备份文件**（而不是再套一层字符串），
     * 这样备份文件对人、对 AI、对其他工具都是可读的。
     */
    fun toJsonObject(map: ExtMap): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map.sortedEntries()) {
            when (value) {
                is ExtValue.Text -> obj.put(key, value.value)
                is ExtValue.Flag -> obj.put(key, value.value)
                is ExtValue.Num -> obj.put(key, numberFor(value.value))
            }
        }
        return obj
    }

    fun fromJsonObject(obj: JSONObject?): ExtMap {
        if (obj == null) return ExtMap.EMPTY
        val out = LinkedHashMap<String, ExtValue>()
        for (key in obj.keys()) {
            when (val v = obj.get(key)) {
                is Boolean -> out[key] = ExtValue.Flag(v)
                is Number -> out[key] = ExtValue.Num(v.toDouble())
                is String -> out[key] = ExtValue.Text(v)
                // 嵌套对象/数组：转成文本，而不是丢掉。
                // 「安静地丢数据」正是这一轮要根治的毛病，抽屉不能重蹈覆辙。
                is JSONObject, is JSONArray -> out[key] = ExtValue.Text(v.toString())
                else -> Unit // JSONObject.NULL 之类：没有信息量，跳过
            }
        }
        return ExtMap(out)
    }

    /**
     * 整数写成整数、小数写成小数。
     * 让 `mood.score = 3` 落库为 `3` 而不是 `3.0` —— 人和 AI 读起来都更自然。
     */
    private fun numberFor(value: Double): Any =
        if (value.isFinite() && value == floor(value) && abs(value) < 9.0e15) {
            value.toLong()
        } else {
            value
        }
}
