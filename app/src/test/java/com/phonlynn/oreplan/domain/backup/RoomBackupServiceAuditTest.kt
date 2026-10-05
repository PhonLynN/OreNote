package com.phonlynn.oreplan.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `RoomBackupService` 的导出与恢复**是否把每个字段都接上了**。
 *
 * ## 为什么现有测试抓不到这类问题
 *
 * 项目里已经有两道备份测试，但它们各自只守一半：
 *
 * | 测试 | 基准 | 覆盖 |
 * |---|---|---|
 * | `BackupCodecTest` | 手写字段清单 | `encode ↔ decode` 往返 |
 * | `BackupCompletenessTest` | Room 生成的 schema JSON | 每个**列**在 codec 里有没有对应字段 |
 *
 * 两道都**不碰 [com.phonlynn.oreplan.data.backup.RoomBackupService]** ——
 * 也就是真正把数据从库里读出来、再写回库里的那两个函数。
 *
 * 那里的漏法很具体：`BackupSnapshot` 新增一个数组、codec 也写了，
 * 但 `export()` 忘了赋值（导出恒为空）或 `restore()` 忘了写回（恢复后数据不回来、
 * 而且旧数据也不清 —— 用户看到的是混合状态，还以为恢复成功了）。
 * 两种情况下**上面两道测试全绿**。
 *
 * 这个事故在 `board_card_links.createdAt` 上真实发生过一次，所以这里补上守卫。
 *
 * ## 做法
 *
 * 纯源码结构扫描，不起数据库（项目零依赖、没有 Robolectric，也没有可用的真机）。
 * 三个清单必须一一对上；它不认识字段的语义，只认识"有没有接上"。
 * 解析逻辑抽成纯函数，并在最后用**故意漏字段的合成源码**自检 ——
 * 一个不会失败的守卫等于没有守卫。
 */
class RoomBackupServiceAuditTest {

    private val serviceText: String by lazy { readSource(SERVICE_PATHS, "RoomBackupService.kt") }
    private val snapshotText: String by lazy { readSource(SNAPSHOT_PATHS, "BackupSnapshot.kt") }

    // ---------------------------------------------------------------- 解析（纯函数，可自检）

    /**
     * 快照里**要落库的**集合字段。
     *
     * ⚠️ 同时认 `List<…>` 与 `Map<…>` —— v5 加的 `settings` 是
     * `Map<String, String>`（设置本来就是 key→value）。
     * 只认 `List` 的话，那个字段会被当成"快照上没有的字段"而误报，
     * 或者更糟：**它逃过审计**，于是导出漏了它都没人发现。
     */
    private fun parseSnapshotFields(text: String): List<String> {
        val block = text.substringAfter("data class BackupSnapshot(", "")
            .substringBefore("\n) {")
        return Regex("""^\s*val (\w+): (?:List|Map)<""", RegexOption.MULTILINE)
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()
    }

    /**
     * `export()` 里被赋值的字段。
     *
     * ⚠️ **必须按缩进锁定层级**（这里恰好是 8 个空格）。
     * 只用 `^\s*(\w+) =` 会把内层构造函数的命名参数一起扫进来 ——
     * `boardCardLinks = …map { BoardCardLink(cardId = …, createdAt = …) }`
     * 里的 `cardId` / `createdAt` 会被误当成快照字段（第一版就是这么错的）。
     */
    private fun parseExportedFields(text: String): Set<String> {
        val body = text.substringAfter("suspend fun export(): BackupSnapshot = BackupSnapshot(", "")
            .substringBefore("\n    )")
        return Regex("""^ {8}(\w+) =""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** `restore()` 里读写过的字段（`snapshot.字段`）。 */
    private fun parseRestoredFields(text: String): Set<String> {
        val body = text.substringAfter("suspend fun restore(snapshot: BackupSnapshot)", "")
            .substringBefore("\n    }\n}")
        return Regex("""snapshot\.(\w+)""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    // ---------------------------------------------------------------- 真实源码

    private val snapshotFields by lazy { parseSnapshotFields(snapshotText) }
    private val exportedFields by lazy { parseExportedFields(serviceText) }
    private val restoredFields by lazy { parseRestoredFields(serviceText) }

    /** 扫描本身要能工作，否则后面的断言是假绿。 */
    @Test
    fun `扫描能读到三份字段清单`() {
        assertTrue("没解析出任何快照字段，扫描规则和源码对不上了", snapshotFields.size >= 20)
        assertTrue("没解析出任何导出字段", exportedFields.size >= 20)
        assertTrue("没解析出任何恢复字段", restoredFields.size >= 20)
    }

    /**
     * 每个快照字段都必须在 `export()` 里被赋值。
     *
     * 漏了 → 备份文件里那个数组恒为空 → **导出报成功，内容是空的**。
     */
    @Test
    fun `每个快照字段都被导出`() {
        val missing = snapshotFields.filterNot { it in exportedFields }
        assertEquals(
            "这些字段在 BackupSnapshot 和 codec 里都有，但 export() 没赋值，" +
                "导出的备份里它们恒为空：$missing",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * 每个快照字段都必须在 `restore()` 里被写回。
     *
     * 漏了 → 那张表既不被清空也不被写入 → 恢复后数据不回来，
     * 旧数据却还在（用户看到混合状态，而且以为恢复成功了）。
     */
    @Test
    fun `每个快照字段都被恢复`() {
        val missing = snapshotFields.filterNot { it in restoredFields }
        assertEquals(
            "这些字段在 BackupSnapshot 和 codec 里都有，但 restore() 没用它们：" +
                "恢复后它们的数据不会回来（旧数据也不会被清）：$missing",
            emptyList<String>(),
            missing,
        )
    }

    /** 反向：`export()` 里不该出现快照上不存在的字段（防止扫描规则失效后假绿）。 */
    @Test
    fun `导出没有多余字段`() {
        val known = snapshotFields.toSet() + "exportedAt"
        assertEquals(
            "export() 里有快照上不存在的字段：${exportedFields - known}",
            emptySet<String>(),
            exportedFields - known,
        )
    }

    // ---------------------------------------------------------------- 自检

    /**
     * **守卫本身必须会失败。**
     *
     * 用一段合成的源码：快照里有三个字段，但 `export()` 只赋了两个、
     * `restore()` 只用了两个。三条断言都必须报出那个漏掉的 `noteBlocks`。
     *
     * 没有这一条的话，将来有人把正则改坏（改成永远匹配不到），
     * 前面三条会以"集合都是空的、差集也是空的"的方式**全部通过**。
     */
    @Test
    fun `漏字段时守卫会报错`() {
        val fakeSnapshot = """
            data class BackupSnapshot(
                val formatVersion: Int = 1,
                val exportedAt: Instant,
                val items: List<Item> = emptyList(),
                val tags: List<Tag> = emptyList(),
                val noteBlocks: List<NoteBlock> = emptyList(),
            ) {
        """.trimIndent()

        val fakeService = """
            class RoomBackupService {
                suspend fun export(): BackupSnapshot = BackupSnapshot(
                    exportedAt = Instant.now(),
                    items = itemDao.findAll().map(ItemEntity::toDomain),
                    tags = tagDao.findAll().map(TagEntity::toDomain),
                )

                suspend fun restore(snapshot: BackupSnapshot) = database.withTransaction {
                    snapshot.items.forEach { itemDao.upsert(it.toEntity()) }
                    snapshot.tags.forEach { tagDao.upsert(it.toEntity()) }
                }
            }
        """.trimIndent()

        val fields = parseSnapshotFields(fakeSnapshot)
        assertEquals(listOf("items", "tags", "noteBlocks"), fields)

        assertTrue(
            "export 漏了 noteBlocks 却没被扫出来",
            "noteBlocks" !in parseExportedFields(fakeService),
        )
        assertTrue(
            "restore 漏了 noteBlocks 却没被扫出来",
            "noteBlocks" !in parseRestoredFields(fakeService),
        )

        // 反过来：合成源码的"多余字段"检查也要能工作
        assertEquals(
            setOf<String>(),
            parseExportedFields(fakeService) - (fields.toSet() + "exportedAt"),
        )
    }

    private companion object {
        val SERVICE_PATHS = listOf(
            "src/main/java/com/phonlynn/oreplan/data/backup/RoomBackupService.kt",
            "app/src/main/java/com/phonlynn/oreplan/data/backup/RoomBackupService.kt",
            "../app/src/main/java/com/phonlynn/oreplan/data/backup/RoomBackupService.kt",
        )
        val SNAPSHOT_PATHS = listOf(
            "src/main/java/com/phonlynn/oreplan/domain/backup/BackupSnapshot.kt",
            "app/src/main/java/com/phonlynn/oreplan/domain/backup/BackupSnapshot.kt",
            "../app/src/main/java/com/phonlynn/oreplan/domain/backup/BackupSnapshot.kt",
        )

        /** 三个候选路径都试：Gradle 的工作目录可能是模块目录，也可能是仓库根。 */
        fun readSource(paths: List<String>, name: String): String =
            paths.firstOrNull { File(it).exists() }?.let { File(it).readText() }
                ?: error("找不到 $name。当前目录是 `${File(".").absolutePath}`")
    }
}
