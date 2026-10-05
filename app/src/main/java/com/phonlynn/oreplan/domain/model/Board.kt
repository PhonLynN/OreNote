package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * 白板卡片类型 —— **历史字段，界面上已经没有这个概念了**。
 *
 * ## ⚠️ 别再基于它做界面
 *
 * 用户 2026-10-03 原话：「白板只有一种，四种类型都是哪年的老黄历了」。
 *
 * 查证过的事实：
 *
 * · 类型选择器 `TypePill`、类型徽章 `BoardTypeBadge`、样式表 `BoardTypeStyle`
 *   **全部零调用点**，已在同一次清理里删掉
 * · 新建卡片固定是 [QUICK]（`BoardCardScreenV2` 里写死）
 * · 所以用户既**选不了**、也**看不到**类型
 *
 * ## 那为什么还留着这个枚举
 *
 * 因为它是**持久化字段**（数据库 + 备份都存 `type`），老数据里确实有
 * `TODO` / `QUOTE` / `GOAL`。删掉枚举就要同时做数据迁移，
 * 而那跟功能无关、风险却更高，所以保留字段本身，只清掉界面侧的死代码。
 *
 * 还活着的引用只剩两类（都是读老数据，不是给用户看的）：
 *
 * · `BoardSearch` 的类型别名 —— 搜「待办」「摘抄」时按类型匹配老卡片
 * · `BoardUi` / `BoardFocusOverlay` 里按类型分支 —— 老卡片的内联待办、配色
 */
enum class BoardCardType(val key: String, val label: String) {
    QUICK("QUICK", "速记"),
    TODO("TODO", "待办"),
    QUOTE("QUOTE", "摘抄"),
    GOAL("GOAL", "目标"),
    ;

    companion object {
        fun fromKey(key: String?): BoardCardType =
            entries.firstOrNull { it.key == key } ?: QUICK
    }
}

/**
 * 白板卡片颜色。存字符串键；自定义颜色存 "#RRGGBB"。
 * null 表示默认白色卡片。
 */
object BoardColors {
    const val WHITE = "white"
    const val ACCENT = "accent"
    const val AMBER = "amber"
    const val LILAC = "lilac"
    const val ROSE = "rose"
    const val GREY = "grey"

    val presetKeys = listOf(WHITE, ACCENT, AMBER, LILAC, ROSE, GREY)

    fun isCustom(color: String?): Boolean = color != null && color.startsWith("#")

    /**
     * 随机挑一个「有彩色」作为新卡片的默认颜色。
     * 刻意排除白色：白色是中性底色，随机包含它会让一半新卡片看起来没上色，
     * 失去「自动分配颜色」的意图。要纯色卡片可以手动选白。
     */
    fun randomPreset(): String =
        listOf(ACCENT, AMBER, LILAC, ROSE, GREY).random()
}

/**
 * 动态置顶规则。
 *
 * 与手动的 [BoardCard.pinned] **独立并存**：pinned 是用户显式钉住、永久有效；
 * 本类型是「规则驱动的临时浮起」——到点浮到顶部，持续一段时间后自动归位。
 *
 * 用密封类型而不是几个可空字段：两种模式的参数结构不同，
 * 打成可空字段会立刻出现「周期模式下日期字段有值但无意义」这类非法状态。
 * 密封类型让非法组合在编译期就不存在。
 *
 * ## 时长是唯一的真值
 *
 * 「下沉时刻」不入库，它 = 浮起时刻 + [durationMinutes]，是完全派生的值。
 * 存下来会立刻出现「两者不一致时以谁为准」的问题。UI 上的「下沉时间」输入
 * 只是反算写回本字段。
 */
@Immutable
sealed interface AutoPinRule {
    /** 本次浮起的时长（分钟）。默认 1440 = 24 小时。下沉时刻由它算出。 */
    val durationMinutes: Int

    /**
     * 指定日期：到点浮起，持续 [durationMinutes] 分钟后自动归位。
     *
     * [at] 是**浮起的绝对时刻**（已含日期与时分，精确到分钟）。
     */
    data class OnDate(
        val at: Instant,
        override val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
    ) : AutoPinRule

    /**
     * 周期：按 RRULE 子集重复浮起。
     *
     * 周期规则只决定「哪些日子浮起」；[minuteOfDay]（浮起时刻）与
     * [durationMinutes]（持续时长）对每次触发**共享同一套值**，
     * 不在每个发生日单独配置。
     *
     * [minuteOfDay] 为当天分钟数 0..1439（与 VTimePickerDialog 的返回口径一致）。
     * 时长允许跨日（如 22:00 浮起 + 480 分钟 = 次日 06:00 下沉）。
     */
    data class Recurring(
        val rule: RecurrenceRule,
        val minuteOfDay: Int = 8 * 60,
        override val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
    ) : AutoPinRule

    companion object {
        /** 默认持续时长：24 小时。 */
        const val DEFAULT_DURATION_MINUTES = 24 * 60

        /** 持续时长下限：1 分钟（不允许零时长浮起）。 */
        const val MIN_DURATION_MINUTES = 1

        /**
         * 持续时长上限：365 天。
         *
         * 这个上界不只是 UI 校验，而是系统的**不变量**：它保证动态置顶
         * **最长一年后必然归位**，因此不会退化成永久置顶——
         * 即使用户把时长设到极限，顶部最终也一定会被清空。
         */
        const val MAX_DURATION_MINUTES = 365 * 24 * 60
    }
}

/** 白板卡片。速记/摘抄是自由文本；待办带清单；目标关联到规划。 */
@Immutable
data class BoardCard(
    val id: String,
    val type: BoardCardType,
    val title: String? = null,
    val body: String? = null,
    val color: String? = null,
    val pinned: Boolean = false,
    val secret: Boolean = false,
    /**
     * 保密卡在主页显示的暗号文案：模糊块上写给自己的一句提醒。
     * 不是解锁密码（没有输入解锁这回事）；为空时主页显示「已隐藏」。
     */
    val secretHint: String? = null,
    val archived: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 手动排序键（拖动排序用）。 */
    val sortIndex: Double = 0.0,
    /** 卡片宽度：null=自动 / "half" / "full"。 */
    val widthMode: String? = null,
    /** 是否在卡片底部显示创建日期。 */
    val showDate: Boolean = true,
    /**
     * 有图片时的展示样式：null=跟全局默认 / "fill"=横向填充（1 张大图）/
     * "grid"=缩略网格（一排 3 个）。
     */
    val imageLayout: String? = null,
    /** 动态置顶规则。null = 未启用。与 [pinned] 独立并存。 */
    val autoPin: AutoPinRule? = null,
    /**
     * 本次浮起是否已被归位（用户手动沉下，或自动到期）。
     *
     * 判定时必须与「本次浮起起始时刻」比较（`resolvedAt >= at`），
     * 不能只判非空：周期型每轮都会再浮一次，只判非空会让卡片
     * **第一次沉下后永远不再浮起**。
     */
    val autoPinResolvedAt: Instant? = null,
)

/** 待办卡片里的清单项。 */
@Immutable
data class BoardTodoItem(
    val id: String,
    val cardId: String,
    val text: String,
    val done: Boolean = false,
    val sortIndex: Int = 0,
)

/** 白板标签，支持两级（parentId 指向父标签）。 */
@Immutable
data class BoardTag(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortIndex: Int = 0,
    /** 开启后，该标签（及其子标签）下的卡片**不在「全部」中显示**。 */
    val hideFromAll: Boolean = false,
)

/** 卡片与标签的关联。 */
@Immutable
data class BoardCardTag(
    val cardId: String,
    val tagId: String,
)

/** 卡片之间的关联（双向展示，单向存储）。 */
@Immutable
data class BoardCardLink(
    val cardId: String,
    val linkedCardId: String,
    /**
     * 建立关联的时刻。可空只为兼容「构造时还没落库」的场景；
     * 数据库里这一列是非空的。
     *
     * 它一度**没有进备份**（`BackupCodec` 漏了这个字段），恢复时被改写成「恢复那一刻」，
     * 于是「关联建立时间」被静默改掉。现已在备份里带上。
     */
    val createdAt: Instant? = null,
)
