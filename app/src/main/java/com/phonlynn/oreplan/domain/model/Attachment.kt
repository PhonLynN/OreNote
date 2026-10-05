package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import com.phonlynn.oreplan.core.id.Ids
import java.time.Instant

/** 附件挂在谁身上。清单项、条目、白板卡片，或者**尚未安排**。 */
enum class AttachmentOwner {
    /** 备忘清单的一条。 */
    CHECKLIST,

    /** 条目本身。 */
    ITEM,

    /** 白板卡片。 */
    BOARD_CARD,

    /**
     * **尚未分配** —— 用户从 AI 对话里选进来、还没被安排到任何对象上的文件。
     *
     * ## 为什么需要这一档（用户口径）
     *
     * > 「模型能读取的文件类型很有限，遇到这种，就不发给 ai，而是放入和
     * > 其他附件在一起的数据库，给 ai 文件索引，让 ai 根据用户指令安排
     * > 这些附件的位置，ai 负责给对应位置的对象添加对应的文件索引。」
     *
     * 也就是说 **导入** 与 **归属** 是两件分开的事：
     *
     * ```
     * 用户选文件  →  先落库（ownerType = UNASSIGNED）
     *                    ↓
     *              AI 只拿到"有这么几个文件"的索引
     *                    ↓
     *          用户说"把这个放到第三章那页"
     *                    ↓
     *              AI 调工具改 ownerType / ownerId   ← 这才是"安排位置"
     * ```
     *
     * ## 为什么不能挂在"当前对话"上
     *
     * 那样对话一删文件就失去入口（而文件**真的在磁盘上**）。
     * 用独立的 `UNASSIGNED` 表示"还没有归属"，语义是诚实的：
     * 它确实不属于任何对象，正等着被安排。
     *
     * ## ⚠️ 加这一档**不需要动 Room schema**
     *
     * 枚举值存在**字符串列**里，没有新增列、没有改类型 ——
     * 所以数据库版本不用升。`ownerId` 在这一档下是空串。
     */
    UNASSIGNED,
}

/**
 * 一个附件的元信息。**二进制不在这里，也不在数据库里** ——
 * 文件被复制进应用内部存储（`filesDir/attachments/…`），库里只留一条索引。
 *
 * 为什么复制而不是记原始 URI：SAF 给的是临时授权，重启或清理后就读不到了；
 * 而「点开还能看」是这份清单存在的意义。代价是占一份空间，所以提供孤儿清理
 * （见 `AttachmentStorage.sweep`）。
 *
 * [storedPath] 存**相对** `filesDir` 的路径，不存绝对路径 —— 绝对路径里带
 * 应用数据目录前缀，恢复备份或被系统迁移后就失效了。
 */
@Immutable
data class Attachment(
    val id: String,
    val ownerType: AttachmentOwner,
    val ownerId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storedPath: String,
    val createdAt: Instant,
) {
    /** 图片可以内嵌缩略图直接看，不必先打开外部应用。 */
    val isImage: Boolean get() = mimeType.startsWith("image/")

    val isAudio: Boolean get() = mimeType.startsWith("audio/")

    /** 视频以外、又不像图片/音频的，交给系统查看器（pdf / word / excel 都走这条）。 */
    val isDocument: Boolean get() = !isImage && !isAudio

    companion object {
        fun new(
            ownerType: AttachmentOwner,
            ownerId: String,
            displayName: String,
            mimeType: String,
            sizeBytes: Long,
            storedPath: String,
            now: Instant,
            id: String = Ids.newId(),
        ): Attachment = Attachment(
            id = id,
            ownerType = ownerType,
            ownerId = ownerId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            storedPath = storedPath,
            createdAt = now,
        )
    }
}
