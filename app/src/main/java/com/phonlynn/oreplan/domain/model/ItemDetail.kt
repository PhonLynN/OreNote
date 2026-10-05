package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable

/**
 * 一条条目的完整内容，供「展示模式」使用。
 *
 * 为什么不直接用 [Item]：展示模式要同时看得见备忘清单与附件 —— 这些东西存在别的表里，
 * 界面上分三次查库会让打开速度变差，也会让「查了一份、另一份还没回来」的中间态外泄。
 * 一次取齐、一次交出去，界面只负责画。
 */
@Immutable
data class ItemDetail(
    val item: Item,
    val checklist: List<ChecklistEntry> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
) {
    /** 按分类分组，且只在有内容的分类上出现分组标题。 */
    val checklistByCategory: Map<ChecklistCategory, List<ChecklistEntry>>
        get() = checklist
            .groupBy { it.category }
            .toSortedMap(compareBy { it.ordinal })

    /** 清单进度。没建清单时为 null，展示模式不画徽标。 */
    val checklistCount: ChecklistCount?
        get() = checklist
            .takeIf { it.isNotEmpty() }
            ?.let { ChecklistCount(done = it.count { e -> e.done }, total = it.size) }

    /** 某个清单项挂的附件。 */
    fun attachmentsOfChecklistEntry(entryId: String): List<Attachment> =
        attachments.filter { it.ownerType == AttachmentOwner.CHECKLIST && it.ownerId == entryId }

    /** 直接挂在条目本身上的附件。 */
    val itemAttachments: List<Attachment>
        get() = attachments.filter { it.ownerType == AttachmentOwner.ITEM }
}
