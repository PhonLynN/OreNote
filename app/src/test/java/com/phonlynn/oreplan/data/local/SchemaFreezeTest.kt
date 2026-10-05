package com.phonlynn.oreplan.data.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 表结构冻结 —— 把「加新功能不改老表」从**约定**变成**测试**。
 *
 * ## 为什么需要它
 *
 * 用户的硬性标准是「加新功能不用改老表、不用迁移、不动老代码」。
 * 光写在文档里的约定会被慢慢侵蚀：某天要给卡片加一个天气字段，
 * 「顺手加一列」是最省事的做法，而它恰好破坏这条标准（一次有风险的迁移 + 老代码改动）。
 *
 * 这个测试把老表的列集合**钉死在 v11**：
 * - 想给老表加列 → 这个测试立刻失败；
 * - 真要改结构，必须**同时修改下面的冻结清单** —— 那是一个有意识的、会被 review 看见的动作。
 *
 * ## 想加新字段时该怎么做
 *
 * 放进**扩展抽屉** `entity_ext`：
 * ```
 * extRepo.update(ExtOwner.BOARD_CARD, cardId) { it.putText("weather.sky", "晴") }
 * ```
 * 不用改表、不用迁移、不用动老代码。
 *
 * ## 什么时候可以合法地改这份清单
 *
 * 只有「基础设施级的结构变更」才值得改（例如将来做云同步要加墓碑列）。
 * 那种改动应当**单独一批、单独评估**，而不是混在某个功能的提交里顺手做掉。
 */
class SchemaFreezeTest {

    /**
     * v11 冻结清单。**这是当前数据库结构的唯一真相。**
     *
     * 22 张表 = 原有 20 张（一列未动）+ v11 新增 2 张。
     */
    private val frozenSchema: Map<String, List<String>> = mapOf(
        "app_meta" to listOf("meta_key", "meta_value"),
        "attachments" to listOf("id", "ownerType", "ownerId", "displayName", "mimeType", "sizeBytes", "storedPath", "createdAt"),
        "board_card_links" to listOf("cardId", "linkedCardId", "createdAt"),
        "board_card_tags" to listOf("cardId", "tagId"),
        "board_cards" to listOf("id", "type", "title", "body", "color", "pinned", "secret", "secretHint", "archived", "createdAt", "updatedAt", "sortIndex", "widthMode", "showDate", "imageLayout", "autoPinKind", "autoPinRule", "autoPinDurationMinutes", "autoPinResolvedAt"),
        "board_tags" to listOf("id", "name", "parentId", "sortIndex", "hideFromAll"),
        "board_todo_items" to listOf("id", "cardId", "text", "done", "sortIndex"),
        "checklist_entries" to listOf("id", "itemId", "category", "title", "done", "orderIndex", "createdAt", "updatedAt"),
        "course_sessions" to listOf("id", "courseId", "dayOfWeek", "startMinuteOfDay", "endMinuteOfDay", "startWeek", "endWeek", "parity", "location", "note"),
        "courses" to listOf("id", "name", "teacher", "defaultLocation", "colorHex", "note", "credit"),
        "daily_reviews" to listOf("epochDay", "text", "updatedAt"),
        "entity_ext" to listOf("ownerTable", "ownerId", "ext", "updatedAt"),
        "focus_sessions" to listOf("id", "startedAt", "endedAt", "minutes", "kind", "plannedMinutes", "completed", "label", "itemId", "createdAt"),
        "habit_logs" to listOf("itemId", "epochDay"),
        "item_tags" to listOf("itemId", "tagId"),
        "items" to listOf("id", "kind", "title", "note", "status", "priority", "startAt", "endAt", "allDay", "rrule", "rruleUntil", "softDueAt", "planStartDay", "planEndDay", "parentId", "treePath", "depth", "progress", "completedAt", "colorTag", "orderIndex", "createdAt", "updatedAt", "goalType", "unit", "targetValue", "stepOrderMode", "autoAdvance", "showOnToday", "pinned", "category", "goalNote", "location", "groupId"),
        "note_blocks" to listOf("id", "ownerId", "heading", "body", "orderIndex", "createdAt", "updatedAt"),
        "quantity_logs" to listOf("id", "itemId", "at", "amount", "label"),
        "recurrence_exceptions" to listOf("id", "itemId", "date", "action", "overrideStartAt", "overrideEndAt", "overrideTitle"),
        "reminders" to listOf("id", "itemId", "triggerAt", "offsetMinutes", "enabled"),
        "tags" to listOf("id", "name", "colorHex"),
        "terms" to listOf("id", "name", "startDate", "totalWeeks", "isActive"),
    )

    /** 冻结时的数据库版本。**这个数字不该再涨**（涨 = 又动了一次结构）。 */
    private val frozenVersion = 11

    @Test
    fun `数据库版本停在 v11`() {
        val actual = databaseJson().getInt("version")
        assertEquals(
            "数据库版本变了。如果是合法的基础设施变更，请同步更新 SchemaFreezeTest 的冻结清单；" +
                "如果只是想给某张表加个字段 —— 请改用扩展抽屉 entity_ext（不必迁移）。",
            frozenVersion,
            actual,
        )
    }

    @Test
    fun `表的集合与冻结时完全一致`() {
        val actual = actualColumns().keys
        val expected = frozenSchema.keys

        assertEquals(
            "表集合变了。\n多出来的表：${(actual - expected).sorted()}\n" +
                "少掉的表：${(expected - actual).sorted()}\n" +
                "新表要在 SchemaFreezeTest 里登记（并接进备份与导出）。",
            expected.sorted(),
            actual.sorted(),
        )
    }

    @Test
    fun `每一张表的列与冻结时逐字一致`() {
        val problems = diffProblems(frozenSchema, actualColumns())

        assertTrue(
            "有人改了老表的结构。\n" +
                problems.joinToString("\n") +
                "\n\n如果只是要加一个字段：请用扩展抽屉 entity_ext，" +
                "不必迁移、不必动老表（见 data-structure-contract.md 第三节）。",
            problems.isEmpty(),
        )
    }

    /**
     * **给「守卫本身」做的测试。**
     *
     * 一个从没见它失败过的测试是不可信的 —— 它可能只是恒真。
     * 这里用合成的输入证明：[diffProblems] 确实能抓出加列、删列、改顺序三种情况。
     * 下面三个用例的输入都是假的，不碰真实 schema。
     */
    @Test
    fun `比对逻辑确实能抓出加列 删列 与改顺序`() {
        val frozen = mapOf(
            "t" to listOf("a", "b", "c"),
        )

        assertTrue(
            "加列没被抓住",
            diffProblems(frozen, mapOf("t" to listOf("a", "b", "c", "d"))).isNotEmpty(),
        )
        assertTrue(
            "删列没被抓住",
            diffProblems(frozen, mapOf("t" to listOf("a", "b"))).isNotEmpty(),
        )
        assertTrue(
            "改顺序没被抓住",
            diffProblems(frozen, mapOf("t" to listOf("a", "c", "b"))).isNotEmpty(),
        )
        assertTrue(
            "完全一致时不该报问题",
            diffProblems(frozen, mapOf("t" to listOf("a", "b", "c"))).isEmpty(),
        )
    }

    /** 逐表求差。抽成函数是为了让上面那个「守卫自测」能直接调用它。 */
    private fun diffProblems(
        frozen: Map<String, List<String>>,
        actual: Map<String, List<String>>,
    ): List<String> {
        val problems = mutableListOf<String>()
        for ((table, columns) in frozen) {
            val now = actual[table] ?: continue // 表缺失由「表集合」那个测试负责报
            if (now != columns) {
                val added = now.filterNot { it in columns }
                val removed = columns.filterNot { it in now }
                problems += buildString {
                    append("表 `").append(table).append("` 的结构变了：")
                    if (added.isNotEmpty()) append("\n    新增列：$added")
                    if (removed.isNotEmpty()) append("\n    删除列：$removed")
                    if (added.isEmpty() && removed.isEmpty()) {
                        append("\n    列相同但顺序不同（顺序变化同样会让 Room 认为 schema 不一致）")
                    }
                }
            }
        }
        return problems
    }

    /**
     * 扩展抽屉必须存在 —— 它是「不改老表」这条标准得以成立的前提。
     * 少了它，下一个新字段就又没有地方可放，只能回头去改老表。
     */
    @Test
    fun `扩展抽屉存在且列齐全`() {
        val columns = actualColumns()["entity_ext"]
        assertEquals(
            listOf("ownerTable", "ownerId", "ext", "updatedAt"),
            columns,
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun databaseJson(): JSONObject {
        val dir = candidateSchemaDirs().firstOrNull { it.isDirectory }
            ?: error("找不到 Room schema 目录（工作目录：${File(".").absolutePath}）")
        val latest = dir.listFiles { f -> f.name.matches(Regex("""\d+\.json""")) }
            ?.maxByOrNull { it.name.removeSuffix(".json").toInt() }
            ?: error("schema 目录 $dir 里没有版本 JSON")
        return JSONObject(latest.readText()).getJSONObject("database")
    }

    /** 按 schema 里的原始顺序取列 —— 顺序也是 schema 的一部分（Room 会校验）。 */
    private fun actualColumns(): Map<String, List<String>> {
        val entities = databaseJson().getJSONArray("entities")
        val out = LinkedHashMap<String, List<String>>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val fields = entity.getJSONArray("fields")
            val columns = ArrayList<String>(fields.length())
            for (j in 0 until fields.length()) {
                columns += fields.getJSONObject(j).getString("columnName")
            }
            out[entity.getString("tableName")] = columns
        }
        return out.toSortedMap()
    }

    private fun candidateSchemaDirs(): List<File> = listOf(
        File("schemas/com.phonlynn.oreplan.data.local.AppDatabase"),
        File("app/schemas/com.phonlynn.oreplan.data.local.AppDatabase"),
        File("../app/schemas/com.phonlynn.oreplan.data.local.AppDatabase"),
    )
}
