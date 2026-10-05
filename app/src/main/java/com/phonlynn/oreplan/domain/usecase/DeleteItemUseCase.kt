package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.ChecklistRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import javax.inject.Inject

/**
 * 删除条目（连同整棵子树），并清理它挂着的**备忘清单、提醒与重复例外**。
 *
 * ## 为什么要专门做一个用例
 *
 * 原先各处的删除路径都是直接 `itemRepository.deleteSubtree(id)`，只删 `items` 一张表。
 * 但 `checklist_entries` 与 `attachments` 都**没有外键约束**（见实体类注释：这两张表是后加的，
 * 为免迁移而没建），于是删条目之后：
 *
 *  - 清单行与附件索引行留在库里，成为指向已删条目的死行；
 *  - 更要紧的是**附件文件永远清不掉** —— 启动时的孤儿清理把「库里存在的任何附件行」
 *    当作有效集合（`OrePlanApplication.sweepOrphanAttachments`），死行让文件被判定为仍然有效。
 *
 * ## ⚠️ 云同步带来的语义变化（2026-10-02）
 *
 * 删除现在是**软删**：条目行留下、只打墓碑，靠 `TombstoneRegistry` 在读取时过滤。
 * 这带来两个必须一起改的点：
 *
 * 1. **子树要用"物理全量"来找**。`itemRepository.getAll()` 现在会过滤掉已软删的行 ——
 *    若用它来做"找子树"，重复删除（或删一棵内部已有墓碑的树）会漏掉节点，
 *    留下一批**没打墓碑的孤儿**：它们不可见、却会在下次同步时以"还活着"的身份传出去。
 *    所以这里改用 daofirst 拿物理行。
 *
 * 2. **附件索引行不能删**。原先这里是 `attachmentRepository.deleteOf(...)` ——
 *    删掉索引行后，附件文件立刻变成"孤儿"，下次启动就被 `AttachmentStorage.sweep` **从磁盘删掉**。
 *    而条目只是软删，墓碑会把"删除"这件事同步到另一台设备 ——
 *    于是另一台设备上点开该条目的附件时，**文件已经不在了**。
 *    这是不可逆的数据丢失，所以：**软删路径不碰附件索引行与文件**。
 *    附件随宿主条目一起被过滤（界面看不见），文件留到墓碑过期回收时再清。
 *
 * ## ⚠️ 提醒与重复例外必须删掉（用户 2026-10-03 报的「孤儿提醒会响」）
 *
 * 这两张表同样没有外键，所以删条目**不会**自动带走它们。
 *
 * 我一开始以为"不会响所以无所谓"—— **那个判断是错的**，
 * 因为 `ReminderPlanner` 确实会跳过"条目已不存在"的提醒
 *（`itemsById[reminder.itemId] ?: return@mapNotNull null`）。
 * 所以它**今天不会响**。但留下的行有三个真实代价：
 *
 * | 代价 | 说明 |
 * |---|---|
 * | 参与云同步 | 它们会被传到别的设备，白占流量与体积 |
 * | 永久累积 | 每次删条目都留一批，谁也清不掉 |
 * | **恢复条目后会诈尸** | 同步/回滚让同 id 的条目回来时，旧提醒**立刻复活**并开始响 |
 *
 * 最后一条是最要命的：那种"我明明删过它"的不确定性，比一条立刻响的提醒更难排查。
 *
 * ### 为什么提醒可以删、附件不能删
 *
 * 两者的**数据性质不同**：
 *
 * · 提醒是**纯元数据**（行里只有 id / 时刻 / 开关）—— 删掉不丢任何用户内容，
 *   而且它的宿主条目已经软删了，提醒单独活着没有意义
 * · 附件的**二进制在磁盘上**，索引行是它与 App 的唯一联系 ——
 *   删索引等于**把用户文件交给清理器**，不可逆
 *
 * 换句话说：**删提醒是可逆的（重建即可），删附件索引是不可逆的。**
 * 这条界线是"哪些子表能跟着删"的判据，以后加新子表时照它评估。
 *
 * ## 文件为什么不在这里删
 *
 * 同上：删除路径不做磁盘 IO，就不会出现"文件删了一半"的中间态。
 * 真正回收交给墓碑过期后的批量清理（S6）。
 */
class DeleteItemUseCase @Inject constructor(
    private val itemRepository: ItemRepository,
    private val checklistRepository: ChecklistRepository,
    private val reminderRepository: ReminderRepository,
    private val exceptionRepository: RecurrenceExceptionRepository,
) {

    suspend operator fun invoke(rootId: String) {
        val all = itemRepository.getAllIncludingDeleted()
        val root = all.firstOrNull { it.id == rootId } ?: return

        // 子树 = 物化路径以根为前缀的全部条目（`deleteSubtree` 用的是同一条规则）
        val subtreeIds = all
            .filter { it.treePath.startsWith(root.treePath) }
            .map { it.id }

        subtreeIds.forEach { itemId ->
            // 只清清单行（子表、随条目生灭、不参与同步）。
            // **不动附件索引行** —— 见类注释第 2 点，那是数据丢失。
            checklistRepository.deleteOf(itemId)

            /*
             * 提醒与重复例外：**要删**（见类注释）。
             *
             * 它们同样是"随条目生灭"的子表，只是没有外键、不会自动清。
             * 不删的话会累积、会同步、还会在条目恢复时诈尸。
             */
            reminderRepository.deleteOf(itemId)
            exceptionRepository.deleteOf(itemId)
        }

        itemRepository.deleteSubtree(rootId)
    }
}
