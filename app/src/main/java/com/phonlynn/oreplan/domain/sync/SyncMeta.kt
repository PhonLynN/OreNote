package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtValue

/**
 * 一个实体的**同步元数据**：修订号、逻辑时钟、最后写入设备、删除墓碑。
 *
 * ## 为什么放在扩展抽屉里，而不是给老表加列
 *
 * `items` 等 20 张老表已被 `SchemaFreezeTest` **冻结在 v11**，
 * 加列就等于一次有风险的迁移，而用户的第一条硬性标准是「加新功能不用改老表」。
 * 抽屉正是为这种场景准备的逃生出口（见 `EntityExt` 的文档）。
 *
 * ⚠️ 抽屉的键**约定为 `sync.` 命名空间**（不是系统保留的 `_` 前缀）：
 * 保留前缀留给数据库内务，而同步元数据是"业务可见"的附属信息，
 * 也应当能被备份带走、能被语义层读到（AI 需要知道哪条被删了）。
 *
 * ## 为什么 deleted 用 Flag 而不是时间戳
 *
 * 抽屉只支持 Text / Num / Flag 三种值。墓碑需要「删于何时」来做 90 天清理，
 * 所以**用 `sync.deletedAt` 存纳秒（Num）**，`sync.deleted` 存 Flag 作为
 * **快速判断位**（判断"是否已删"的调用点远多于"何时删的"）。
 * 两者冗余是有意的：Flag 可被索引式地快速读取，Num 承载时间信息。
 */
data class SyncMeta(
    /** 每次修改 +1，远端用它判断"本机这份是不是新的"。 */
    val rev: Long = 0,
    /** 最后写入事件的逻辑时钟；null = 从未参与过同步。 */
    val hlc: Hlc? = null,
    /** 最后写入的设备 id。 */
    val deviceId: String? = null,
    /** 删除墓碑：非 null = 已删（值为删除时刻的毫秒）。 */
    val deletedAt: Long? = null,
) {

    val isDeleted: Boolean get() = deletedAt != null

    /** 从未同步过（本机新建、还没上传）。 */
    val isNeverSynced: Boolean get() = hlc == null

    /** 写进抽屉。**null 的字段不写键**（抽屉里不留空壳）。 */
    fun toExt(base: ExtMap = ExtMap.EMPTY): ExtMap {
        var map = base
            .putNum(KEY_REV, rev.toDouble())
        map = hlc?.let { map.putText(KEY_HLC, it.encode()) } ?: map.remove(KEY_HLC)
        map = deviceId?.let { map.putText(KEY_DEVICE, it) } ?: map.remove(KEY_DEVICE)
        map = deletedAt?.let { map.putNum(KEY_DELETED_AT, it.toDouble()) }
            ?: map.remove(KEY_DELETED_AT)
        map = if (isDeleted) map.putFlag(KEY_DELETED, true) else map.remove(KEY_DELETED)
        return map
    }

    /** **清空同步元数据**：把与本实体相关的键全部摘掉，保留抽屉里其它功能的键。 */
    fun clearedFrom(base: ExtMap): ExtMap = base
        .remove(KEY_REV)
        .remove(KEY_HLC)
        .remove(KEY_DEVICE)
        .remove(KEY_DELETED)
        .remove(KEY_DELETED_AT)
        .remove(KEY_BLOB_HASH)

    companion object {
        const val KEY_REV = "sync.rev"
        const val KEY_HLC = "sync.hlc"
        const val KEY_DEVICE = "sync.device"
        const val KEY_DELETED = "sync.deleted"
        const val KEY_DELETED_AT = "sync.deletedAt"

        /**
         * 附件二进制的**内容哈希**（sha256 十六进制）—— 它在云端的身份。
         *
         * 与 `attachments.storedPath`（本地缓存路径）含义完全不同：
         * 路径换机就失效，hash 永久有效。跨端引用靠这个键，不靠路径。
         */
        const val KEY_BLOB_HASH = "sync.blobHash"

        /** 全部同步键。清理/过滤逻辑用它，避免散落字符串。 */
        val ALL_KEYS = listOf(KEY_REV, KEY_HLC, KEY_DEVICE, KEY_DELETED, KEY_DELETED_AT, KEY_BLOB_HASH)

        val EMPTY = SyncMeta()

        fun from(map: ExtMap): SyncMeta = SyncMeta(
            rev = map.num(KEY_REV)?.toLong() ?: 0L,
            hlc = map.text(KEY_HLC)?.let(Hlc::decode),
            deviceId = map.text(KEY_DEVICE),
            deletedAt = map.num(KEY_DELETED_AT)?.toLong(),
        )

        /** 该抽屉行是否是一个墓碑。 */
        fun isTombstone(map: ExtMap): Boolean = map.flag(KEY_DELETED) == true
    }
}

/**
 * 参与同步的**顶层实体**：每一条独立占据云端的一个对象。
 *
 * ## 为什么只给顶层实体加墓碑（用户 2026-10-02 拍板）
 *
 * 子表（清单项、笔记块、提醒、关联表）在语义上**必定随父级一起生灭**——
 * 没有「父卡还在、但它的某个笔记块被单独删除并需要传到另一台设备」这种场景。
 * 给每张表都做墓碑要改 16+ 张表与所有调用点，收益却只有"语义更纯"。
 * 所以：**子表仍是硬删，父级删除时整体带走**（事务内完成）。
 *
 * ## 每个枚举项一个云端对象
 *
 * [table] 同时是 `entity_ext` 的表名与云端对象路径的中段，
 * 用枚举而不用字符串是为了**让拼错变成编译错误**（抽屉与业务表之间没有外键）。
 */
enum class SyncEntity(val table: String, val ext: ExtOwner) {
    /** 条目：规划目标 / 子项 / 日程 / 待办 / 习惯提醒载体，全部在这张表。 */
    ITEM("items", ExtOwner.ITEM),

    /** 白板卡片。 */
    BOARD_CARD("board_cards", ExtOwner.BOARD_CARD),

    /** 白板标签。 */
    BOARD_TAG("board_tags", ExtOwner.BOARD_TAG),

    /** 课程。 */
    COURSE("courses", ExtOwner.COURSE),

    /** 标签（日程模块）。 */
    TAG("tags", ExtOwner.TAG),

    /** 学期。 */
    TERM("terms", ExtOwner.TERM),

    /** 专注记录。**只增不改**，但删除要传播，所以也要墓碑。 */
    FOCUS_SESSION("focus_sessions", ExtOwner.FOCUS_SESSION),

    /** 每日复盘。 */
    DAILY_REVIEW("daily_reviews", ExtOwner.DAILY_REVIEW),

    /**
     * 附件的**索引行**。二进制本身走 `BlobStore`（内容寻址），不进信封。
     *
     * 附件的删除语义与其它表不同：索引行不参与软删（见 TombstoneRegistry
     * 与 SyncTableAdapter 的纪律），但索引消失仍要能传播 —— 所以它仍是
     * 一个可同步的顶层实体。
     */
    ATTACHMENT("attachments", ExtOwner.ATTACHMENT),

    ;
}
