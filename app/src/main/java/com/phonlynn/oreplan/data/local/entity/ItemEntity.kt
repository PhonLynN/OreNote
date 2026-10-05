package com.phonlynn.oreplan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 统一条目表 —— 日程、规划、目标共用。
 *
 * 用**一张宽表 + kind 判别**而不是三张分表：纯本地单用户，量级在几千行以内，
 * 宽表的 NULL 列没有实际成本；而「今天所有要做的事」这种查询只需要一次时间范围扫描，
 * 不用三表 UNION。
 *
 * 时间列分两组，语义不混：`startAt/endAt` 是精确时刻（日程用），
 * `planStartDay/planEndDay` 是精度到日的计划跨度（甘特条用）。
 *
 * `kind` / `status` 存字符串而**不做数据库枚举约束** —— 以后加 `NOTE` 类型
 * 不需要做数据库迁移。
 */
@Entity(
    tableName = "items",
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["treePath"]),
        Index(value = ["startAt"]),
        Index(value = ["softDueAt"]),
        Index(value = ["kind"]),
    ],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val note: String?,
    val status: String,
    val priority: Int,
    val startAt: Long?,
    val endAt: Long?,
    val allDay: Boolean,
    val rrule: String?,
    val rruleUntil: Long?,
    val softDueAt: Long?,
    val planStartDay: Int?,
    val planEndDay: Int?,
    val parentId: String?,
    /** 物化路径，形如 `/根id/子id/`，含自身 id。 */
    val treePath: String,
    val depth: Int,
    val progress: Float?,
    val completedAt: Long?,
    val colorTag: String?,
    val orderIndex: Double,
    val createdAt: Long,
    val updatedAt: Long,
    /** 目标类型：STEP / QUANTITY / HABIT（仅 GOAL 使用）。 */
    @ColumnInfo(defaultValue = "NULL") val goalType: String? = null,
    /** 数量目标的单位（本 / 份 / 天）。 */
    @ColumnInfo(defaultValue = "NULL") val unit: String? = null,
    /** 数量目标的目标值。 */
    @ColumnInfo(defaultValue = "NULL") val targetValue: Long? = null,
    /** 分步目标的推进方式：SEQ（按顺序解锁）/ FREE（自由完成）。 */
    @ColumnInfo(defaultValue = "NULL") val stepOrderMode: String? = null,
    /** 分步目标：完成后自动推进到下一步。 */
    @ColumnInfo(defaultValue = "0") val autoAdvance: Boolean = false,
    /** 是否出现在今日列表。 */
    @ColumnInfo(defaultValue = "0") val showOnToday: Boolean = false,
    /** 固定到列表顶部。 */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** 分类（学习 / 生活 / 健康…）。 */
    @ColumnInfo(defaultValue = "NULL") val category: String? = null,
    /** 目标备注（详情页琥珀提示条）。 */
    @ColumnInfo(defaultValue = "NULL") val goalNote: String? = null,
    /** 地点（日程的地点输入）。 */
    @ColumnInfo(defaultValue = "NULL") val location: String? = null,
    /**
     * 归属的待办组（仅待办用；指向一条 kind=TODO_GROUP 的条目）。
     *
     * 与 parentId 分开：那个字段对任务是「关联事项」语义（可指向任务/目标）。
     * v10 新增：纯加列、可空、DEFAULT NULL → AutoMigration 9→10。
     */
    @ColumnInfo(defaultValue = "NULL") val groupId: String? = null,
)
