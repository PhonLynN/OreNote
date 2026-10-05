package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import com.phonlynn.oreplan.core.id.Ids
import java.time.Instant

/**
 * 计划笔记里的一个小节。
 *
 * 规划条目（以及工作区）可以挂任意多个小节，每个小节是一对「小节标题 + 正文」。
 * 为什么不把整篇笔记塞进 `items.note` 一个字段：一篇笔记里的「感想」「注意点」「参考资料」
 * 是并列的多块内容，需要各自折叠、各自排序；挤进一个字符串就只能靠约定去解析，
 * 一旦用户想调顺序就得改文本，很脆。
 *
 * [orderIndex] 与条目同级排序用同一套规则（见 `OrderKeys`），因此拖动排序可以直接复用。
 */
@Immutable
data class NoteBlock(
    val id: String,
    val ownerId: String,
    val heading: String,
    val body: String,
    val orderIndex: Double = 0.0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** 标题与正文都空的小节没有展示价值，界面上用来判断是否默认折叠。 */
    val isEmpty: Boolean get() = heading.isBlank() && body.isBlank()

    companion object {
        fun new(
            ownerId: String,
            heading: String,
            body: String,
            orderIndex: Double,
            now: Instant,
            id: String = Ids.newId(),
        ): NoteBlock = NoteBlock(
            id = id,
            ownerId = ownerId,
            heading = heading,
            body = body,
            orderIndex = orderIndex,
            createdAt = now,
            updatedAt = now,
        )
    }
}
