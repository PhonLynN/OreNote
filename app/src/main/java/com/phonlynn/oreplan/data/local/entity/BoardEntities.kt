package com.phonlynn.oreplan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "board_cards",
    indices = [Index(value = ["updatedAt"]), Index(value = ["archived"])],
)
data class BoardCardEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String?,
    val body: String?,
    val color: String?,
    val pinned: Boolean,
    val secret: Boolean,
    /**
     * 保密卡在主页显示的暗号文案（自我提醒）。
     * 它是「模糊块上写给自己看的一句话」，不是解锁密码——没有输入解锁这回事。
     * null/空 时主页显示默认的「已隐藏」。
     */
    @ColumnInfo(defaultValue = "NULL") val secretHint: String? = null,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** 手动排序键（拖动排序用）。 */
    @ColumnInfo(defaultValue = "0") val sortIndex: Double = 0.0,
    /** 卡片宽度：null=自动 / "half" / "full"。 */
    @ColumnInfo(defaultValue = "NULL") val widthMode: String? = null,
    /** 是否在卡片底部显示创建日期。 */
    @ColumnInfo(defaultValue = "1") val showDate: Boolean = true,
    /**
     * 有图片时的展示样式：null=跟全局默认 / "fill"=横向填充（1 张大图）/ "grid"=缩略网格（一排 3 个）。
     * 与 widthMode 同类的可空字符串字段，新增时走 AutoMigration。
     */
    @ColumnInfo(defaultValue = "NULL") val imageLayout: String? = null,
    /**
     * 动态置顶：规则类型（"DATE" / "RECUR"），null = 未启用。
     * 与 pinned（手动置顶）独立并存。
     */
    @ColumnInfo(defaultValue = "NULL") val autoPinKind: String? = null,
    /** 动态置顶：规则参数（紧凑 JSON）。非法值在解码时降级为 null。 */
    @ColumnInfo(defaultValue = "NULL") val autoPinRule: String? = null,
    /** 动态置顶：本次浮起的持续时长（分钟）。1 分钟 ~ 365 天。 */
    @ColumnInfo(defaultValue = "NULL") val autoPinDurationMinutes: Int? = null,
    /** 动态置顶：本次浮起已被归位的时刻（手动沉下或自动到期）。 */
    @ColumnInfo(defaultValue = "NULL") val autoPinResolvedAt: Long? = null,
)

@Entity(
    tableName = "board_todo_items",
    indices = [Index(value = ["cardId"])],
)
data class BoardTodoItemEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val text: String,
    val done: Boolean,
    val sortIndex: Int,
)

@Entity(
    tableName = "board_tags",
    indices = [Index(value = ["parentId"])],
)
data class BoardTagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val sortIndex: Int,
    /** 该标签（及其子标签）下的卡片是否**不在「全部」中显示**。 */
    @ColumnInfo(defaultValue = "0") val hideFromAll: Boolean = false,
)

@Entity(
    tableName = "board_card_tags",
    primaryKeys = ["cardId", "tagId"],
    indices = [Index(value = ["tagId"])],
)
data class BoardCardTagEntity(
    val cardId: String,
    val tagId: String,
)

@Entity(
    tableName = "board_card_links",
    primaryKeys = ["cardId", "linkedCardId"],
    indices = [Index(value = ["linkedCardId"])],
)
data class BoardCardLinkEntity(
    val cardId: String,
    val linkedCardId: String,
    val createdAt: Long,
)
