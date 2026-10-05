package com.phonlynn.oreplan.domain.backup

import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * **带从属数据的打包**：云同步用，把"父子一起走"这件事表达清楚。
 *
 * ## 为什么卡片/课程要把子数据打进同一个 payload
 *
 * 白板卡片的清单项、标签关联、卡片关联，以及课程的上课安排，都是**子表**：
 *  · 语义上必定随父级生灭（S1 用户拍板：只给顶层实体加墓碑）；
 *  · 大多是复合主键的关联表，无法独立表达"删除"。
 *
 * 若把它们拆成独立对象同步，会出现"父级已到、子级还在路上"的中间态 ——
 * 界面上表现为一张卡片短暂地空着标签、或课表上短暂缺一块。
 * 打进同一个对象就没有这个窗口：**要么整张卡片都到了，要么都还没到**。
 *
 * 代价是 payload 变大（一张卡片连同清单一起传）。个人数据量级下可接受，
 * 换来的是"不会出现半截状态"这个更强的保证。
 *
 * ## 为什么放这里而不是 BackupCodec 里
 *
 * `BackupCodec` 的职责是**整库快照**（所有表一起），它的 bundle 概念是"整个备份"。
 * 这里的 bundle 是**单个实体的闭包**，服务于同步的逐对象粒度。
 * 两者形状相近但用途不同；混在一起会让 `BackupCodec` 长出只给同步用的函数。
 * 复用的是它已经有的**单实体映射**（`boardCardToJson` 等，已改为 internal）。
 */
object SyncBundleCodec {

    // ---------------------------------------------------------------- 白板卡片

    /**
     * 一张卡片 + 它的清单项 + 标签 id + 关联卡片 id。
     *
     * 只存**关联的 id**（而不是把被引用对象也打包进来）：被引用的卡片
     * 自己也是顶层实体、有自己的同步对象。打包进来会导致同一张卡片
     * 出现在多个对象里 —— 那就是两份真值，必然不一致。
     */
    fun boardCardToJson(
        card: BoardCard,
        todos: List<BoardTodoItem>,
        tagIds: List<String>,
        linkedCardIds: List<String>,
    ): JSONObject = JSONObject().apply {
        put("card", BackupCodec.boardCardToJson(card))
        put("todos", JSONArray().apply { todos.forEach { put(BackupCodec.boardTodoItemToJson(it)) } })
        put("tagIds", JSONArray().apply { tagIds.forEach { put(it) } })
        put("linkedCardIds", JSONArray().apply { linkedCardIds.forEach { put(it) } })
    }

    data class BoardCardBundle(
        val card: BoardCard,
        val todos: List<BoardTodoItem>,
        val tagIds: List<String>,
        val linkedCardIds: List<String>,
    )

    fun boardCardFromJson(obj: JSONObject): BoardCardBundle = BoardCardBundle(
        card = BackupCodec.boardCardFromJson(obj.getJSONObject("card")),
        todos = obj.optJSONArray("todos").mapObjects(BackupCodec::boardTodoItemFromJson),
        tagIds = obj.optJSONArray("tagIds").toStringList(),
        linkedCardIds = obj.optJSONArray("linkedCardIds").toStringList(),
    )

    // ---------------------------------------------------------------- 课程

    fun courseToJson(course: Course, sessions: List<CourseSession>): JSONObject = JSONObject().apply {
        put("course", BackupCodec.courseToJson(course))
        put("sessions", JSONArray().apply { sessions.forEach { put(BackupCodec.sessionToJson(it)) } })
    }

    data class CourseBundle(val course: Course, val sessions: List<CourseSession>)

    fun courseFromJson(obj: JSONObject): CourseBundle = CourseBundle(
        course = BackupCodec.courseFromJson(obj.getJSONObject("course")),
        sessions = obj.optJSONArray("sessions").mapObjects(BackupCodec::sessionFromJson),
    )

    // ---------------------------------------------------------------- 小工具

    /**
     * 数组缺失时返回空列表，而不是抛异常。
     *
     * 这条对**向前/向后兼容**很关键：将来若给 bundle 加一个数组字段，
     * 旧版本写入的对象里没有这个键 —— 新版本读到 `null` 时应当当作"空"，
     * 而不是崩掉整次同步。
     */
    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            optJSONObject(i)?.let { out += transform(it) }
        }
        return out
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }
}
