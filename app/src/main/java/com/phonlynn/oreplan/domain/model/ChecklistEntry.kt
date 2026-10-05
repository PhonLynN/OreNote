package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import com.phonlynn.oreplan.core.id.Ids
import java.time.Instant

/**
 * 备忘清单的一条。
 *
 * 「做这件事之前要准备什么」是最容易被忘掉的一环：要带的证件、要填的表、要去打的卡。
 * 它们既不是子任务（不产生独立的时间安排），也不是备注（需要逐项勾选），所以单独存一张表。
 *
 * 分类固定成这几类，是因为对照的是一次真实出行/办事的准备流程；
 * 存字符串且**不做数据库约束**，以后要加一类（比如「要交的费用」）不需要迁移。
 */
enum class ChecklistCategory {
    /** 要准备的材料（复印件、成绩单…）。 */
    MATERIAL,

    /** 要随身带的物品（身份证、水杯、充电宝…）。 */
    CARRY,

    /** 要填写的表单（报名表、报销单…）。 */
    FORM,

    /** 要完成的打卡/签到（学习打卡、健康打卡…）。 */
    CHECKIN,

    /** 不属于上面四类的其他事项。 */
    OTHER,
}

/**
 * 备忘清单的一条记录。[itemId] 指向所属条目，[category] 决定它落在哪一组。
 *
 * 附件不放在这里，而是用 `attachments` 表以 `(ownerType = CHECKLIST, ownerId = 本条 id)`
 * 关联 —— 一条可以挂多个，且以后条目本身也能用同一张附件表，不必写第二套。
 */
@Immutable
data class ChecklistEntry(
    val id: String,
    val itemId: String,
    val category: ChecklistCategory,
    val title: String,
    val done: Boolean = false,
    val orderIndex: Double = 0.0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun new(
            itemId: String,
            category: ChecklistCategory,
            title: String,
            orderIndex: Double,
            now: Instant,
            id: String = Ids.newId(),
        ): ChecklistEntry = ChecklistEntry(
            id = id,
            itemId = itemId,
            category = category,
            title = title,
            orderIndex = orderIndex,
            createdAt = now,
            updatedAt = now,
        )
    }
}

/** 某条目的清单进度：已勾选 / 总数。用于列表徽标与「还剩多少没准备」的提示。 */
@Immutable
data class ChecklistCount(val done: Int, val total: Int) {
    val isComplete: Boolean get() = total > 0 && done == total
}
