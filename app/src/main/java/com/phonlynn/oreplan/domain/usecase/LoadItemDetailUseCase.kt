package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.domain.model.ItemDetail
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.ChecklistRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import javax.inject.Inject

/**
 * 取一条条目的完整内容（条目 + 备忘清单 + 附件），给展示模式用。
 *
 * 附件分两批取：清单项挂的，以及条目本身挂的。仓储按 `ownerId` 查询，
 * 所以这里先把「清单项 id + 条目 id」一次收集齐，再逐个取 —— 清单项通常只有几条，
 * 不会成为瓶颈；而为此加一个 `IN (...)` 的批量接口会让仓储多一个只有一处用得到的方法。
 */
class LoadItemDetailUseCase @Inject constructor(
    private val itemRepository: ItemRepository,
    private val checklistRepository: ChecklistRepository,
    private val attachmentRepository: AttachmentRepository,
) {

    suspend operator fun invoke(itemId: String): ItemDetail? {
        val item = itemRepository.getById(itemId) ?: return null
        val checklist = checklistRepository.getOf(itemId)
        val attachments = buildList {
            addAll(attachmentRepository.getOf(itemId))
            checklist.forEach { entry -> addAll(attachmentRepository.getOf(entry.id)) }
        }
        return ItemDetail(item = item, checklist = checklist, attachments = attachments)
    }
}
