package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * 扩展抽屉里的一条值。
 *
 * 只支持 JSON 的三种基本类型，这是**有意为之的限制**：抽屉的用途是「给已有实体挂几个新字段」，
 * 扁平结构足够用；而且扁平结构能被语义层直接渲染成一行纯文本给 AI 读。
 * 嵌套对象/数组在读入时会被转成文本（见 `ExtCodec`），不会让模型变得递归。
 */
@Immutable
sealed interface ExtValue {

    /** 文本。 */
    data class Text(val value: String) : ExtValue

    /** 数字。整数与小数统一用 Double 承载（个人数据的量级远不到精度上限）。 */
    data class Num(val value: Double) : ExtValue

    /** 真假。 */
    data class Flag(val value: Boolean) : ExtValue
}

/**
 * 一个实体的「扩展抽屉」内容：**扁平的 名字 → 值** 映射。
 *
 * ## 为什么要有它
 *
 * 用户的第一条硬性标准是「加新功能不用改老表、不用迁移、不动老代码」。
 * 数据库表结构是强类型的、列是固定的，所以「加一个天气字段」在传统做法下
 * 必须动表结构（= 一次有风险的迁移）。本类型是那条规则的逃生出口：
 * 新字段放进抽屉，表结构一次都不用动。
 *
 * ## 使用纪律（重要）
 *
 * 1. **键要带命名空间**，形如 `feature.field`（如 `weather.sky`、`mood.score`）。
 *    抽屉是全表共用的，不带前缀迟早撞名。
 * 2. **下划线开头的键是系统保留**（[RESERVED_PREFIX]），业务不许占用。
 * 3. 抽屉里的东西**应当是「附属信息」**：能被删掉而不影响主流程。
 *    真正需要索引、排序、频繁查询的字段，仍然应该走正式列（那时才值得一次迁移）。
 */
@Immutable
data class ExtMap(val values: Map<String, ExtValue> = emptyMap()) {

    val isEmpty: Boolean get() = values.isEmpty()

    val size: Int get() = values.size

    operator fun get(key: String): ExtValue? = values[key]

    fun text(key: String): String? = (values[key] as? ExtValue.Text)?.value

    fun num(key: String): Double? = (values[key] as? ExtValue.Num)?.value

    fun flag(key: String): Boolean? = (values[key] as? ExtValue.Flag)?.value

    fun putText(key: String, value: String): ExtMap = withEntry(key, ExtValue.Text(value))

    fun putNum(key: String, value: Double): ExtMap = withEntry(key, ExtValue.Num(value))

    fun putFlag(key: String, value: Boolean): ExtMap = withEntry(key, ExtValue.Flag(value))

    fun put(key: String, value: ExtValue): ExtMap = withEntry(key, value)

    fun remove(key: String): ExtMap = ExtMap(values - key)

    /**
     * 按键名排序后的条目。渲染、比对、测试都走它，
     * 保证「同样的内容永远输出同样的顺序」（Map 的迭代顺序不该泄漏到输出里）。
     */
    fun sortedEntries(): List<Pair<String, ExtValue>> =
        values.entries.sortedBy { it.key }.map { it.key to it.value }

    private fun withEntry(key: String, value: ExtValue): ExtMap {
        require(key.isNotBlank()) { "扩展键不能为空" }
        require(!key.startsWith(RESERVED_PREFIX)) {
            "扩展键不能以「$RESERVED_PREFIX」开头（系统保留前缀）：$key"
        }
        return ExtMap(values + (key to value))
    }

    companion object {
        val EMPTY = ExtMap()

        /** 系统保留前缀：下划线开头的键留给以后的内务用途，业务不许占用。 */
        const val RESERVED_PREFIX = "_"
    }
}

/**
 * 一条「已落盘的抽屉内容」：谁 + 内容（+ 最后写入时刻）。
 *
 * 备份要按行带走全部抽屉，AI 上下文要一次读一批，
 * 两者都需要一个把归属和内容绑在一起的值对象。
 *
 * [updatedAt] 可空只为兼容「刚构造、还没落库」的场景（那时由写入侧补当前时刻）。
 * 它必须进备份：别的表的 `createdAt/updatedAt` 都进了，
 * 抽屉的行如果唯独漏掉时间戳，就又回到「备份不忠实」的老问题上。
 */
@Immutable
data class ExtRecord(
    val owner: ExtOwner,
    val ownerId: String,
    val map: ExtMap,
    val updatedAt: Instant? = null,
)

/**
 * 扩展抽屉能挂到哪些表上 —— 用枚举而不是散落的字符串，**让拼错表名变成编译错误**
 * （抽屉与业务表之间没有外键，拼错了不会报错，只会静默读不到东西）。
 *
 * ## 只列「单列主键」的表
 *
 * `ownerId` 是一个字符串，所以只收录主键是单列的表。复合主键的表
 * （`habit_logs`、`item_tags`、`board_card_tags`、`board_card_links`）暂不收录。
 *
 * **以后要加不需要迁移**：只要在这里补一个枚举项、并约定该表的「身份字符串」格式即可 ——
 * 抽屉表本身存的就是字符串，不关心它怎么拼出来的。
 */
enum class ExtOwner(val table: String) {
    ITEM("items"),
    TERM("terms"),
    COURSE("courses"),
    COURSE_SESSION("course_sessions"),
    TAG("tags"),
    REMINDER("reminders"),
    RECURRENCE_EXCEPTION("recurrence_exceptions"),
    NOTE_BLOCK("note_blocks"),
    CHECKLIST_ENTRY("checklist_entries"),
    ATTACHMENT("attachments"),
    BOARD_CARD("board_cards"),
    BOARD_TODO_ITEM("board_todo_items"),
    BOARD_TAG("board_tags"),
    QUANTITY_LOG("quantity_logs"),
    DAILY_REVIEW("daily_reviews"),
    FOCUS_SESSION("focus_sessions"),
    ;

    companion object {
        /** 从数据库里的表名字符串还原。认不出返回 null（脏数据不该让读取崩溃）。 */
        fun fromTable(table: String): ExtOwner? = entries.firstOrNull { it.table == table }
    }
}
