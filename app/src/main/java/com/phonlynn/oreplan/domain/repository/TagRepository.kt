package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.Tag
import kotlinx.coroutines.flow.Flow

interface TagRepository {

    fun observeTags(): Flow<List<Tag>>

    fun observeTagsOf(itemId: String): Flow<List<Tag>>

    suspend fun upsert(tag: Tag)

    suspend fun delete(tagId: String)

    /** 整体替换某个条目的标签集合。 */
    suspend fun setTagsOf(itemId: String, tagIds: List<String>)

    /**
     * **物理全量**（含已软删的）：云同步打包用。
     *
     * 为什么不复用 [getAll]：那个会过滤墓碑（S1 加的软删语义），
     * 而同步**必须**看到墓碑才能把"删除"传到另一台设备 ——
     * 用过滤后的集合，删除就永远传不出去。
     *
     * ⚠️ 只有两类调用方该用它：**同步打包**与**墓碑回收**。
     * 界面与业务查询一律用带过滤的那些方法。
     */
    suspend fun allTagsIncludingDeleted(): List<Tag>
}
