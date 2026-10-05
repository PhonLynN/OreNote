package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.NoteBlock
import kotlinx.coroutines.flow.Flow

/**
 * 计划笔记小节的仓储。
 *
 * 与 `ItemRepository` 分开：笔记小节不是条目的一部分，它的读写节奏也不同
 * （条目编辑是整条替换，小节是一条条加删改）。分开之后，以后要把笔记做成独立模块
 * 也不需要动条目那条线。
 */
interface NoteBlockRepository {

    fun observeOf(ownerId: String): Flow<List<NoteBlock>>

    suspend fun getOf(ownerId: String): List<NoteBlock>

    suspend fun getAll(): List<NoteBlock>

    suspend fun upsert(block: NoteBlock)

    suspend fun upsertAll(blocks: List<NoteBlock>)

    suspend fun delete(id: String)

    /** 删除某归属下的全部小节。 */
    suspend fun deleteOf(ownerId: String)

    /**
     * 只保留 [keepIds] 指向的小节，其余删除 —— 编辑器是按「保存时整体对齐」写入的，
     * 逐个 diff 判断太容易漏。注意 [keepIds] 为空即「全删」，由实现转成 [deleteOf]。
     */
    suspend fun retainOnly(ownerId: String, keepIds: List<String>)
}
