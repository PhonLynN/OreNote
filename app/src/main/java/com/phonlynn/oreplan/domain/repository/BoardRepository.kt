package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardLink
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import kotlinx.coroutines.flow.Flow

/** 白板（卡片 + 标签 + 清单）。 */
interface BoardRepository {

    fun observeCards(): Flow<List<BoardCard>>

    fun observeArchivedCards(): Flow<List<BoardCard>>

    fun observeCard(id: String): Flow<BoardCard?>

    fun observeTags(): Flow<List<BoardTag>>

    fun observeTodoItems(): Flow<List<BoardTodoItem>>

    fun observeCardTags(): Flow<List<BoardCardTag>>

    fun observeCardLinks(): Flow<List<BoardCardLink>>

    suspend fun setCardLinks(cardId: String, linkedCardIds: List<String>)

    /**
     * **整体替换**卡片自身 + 它的清单项 + 标签关联（一个事务）。
     *
     * ⚠️ 名字里的 `replace` 是**警告**，不是修辞：本方法会**先删掉该卡的全部清单项与
     * 标签关联，再按传入的列表重建**。所以：
     *  - 只改卡片自身某个字段时，**不要**用它，改用对应的 `setCardXxx(id, value)`；
     *  - 传 `emptyList()` 会**清空**该卡的清单/标签（本项目曾因此出现
     *    「拖动排序清空所有卡片标签」的事故）。
     *
     * 两个关联参数**可为 null**，语义是「本次不改动该关联」：
     *  - `todoItems == null` → 保留现有清单项，不动；
     *  - `tagIds == null` → 保留现有标签关联，不动。
     * 传空列表才是「清空」。这样把「忘了传关联」从**静默清空**变成**显式不动**。
     */
    suspend fun replaceCardWithRelations(
        card: BoardCard,
        todoItems: List<BoardTodoItem>?,
        tagIds: List<String>?,
    )

    suspend fun deleteCard(id: String)

    suspend fun setCardArchived(id: String, archived: Boolean)

    suspend fun setCardPinned(id: String, pinned: Boolean)

    /**
     * 记下「本次动态浮起已归位」（用户点了沉下）。
     *
     * 只影响**这一次**浮起——下一次到期仍会照常浮起，
     * 所以不能把它做成「关闭动态置顶」。
     */
    suspend fun setCardAutoPinResolvedAt(id: String, at: java.time.Instant)

    suspend fun setCardSecret(id: String, secret: Boolean)

    /**
     * 只改正文（主页勾选复选框用）。
     * **不得影响标签与待办**（同 [setCardSortIndex]，不走 [replaceCardWithRelations]）。
     */
    suspend fun setCardBody(id: String, body: String?)

    /**
     * 只改**标题与正文**（全屏编辑页对勾保存用）。
     * **不得影响标签/待办/颜色/置顶等其余字段**（不走 [replaceCardWithRelations]）。
     */
    suspend fun setCardTitleBody(id: String, title: String?, body: String?)

    /** 只改排序键（拖动排序用）。**不得影响标签与待办**。 */
    suspend fun setCardSortIndex(id: String, sortIndex: Double)

    /**
     * 批量写排序键（拖动落位用）。
     * 必须在**一个事务**里完成：逐张写会让每个 upsert 各发一次 Flow，
     * 拖动落位时触发 N 次重组/重排，看起来就是全墙卡片一起抖一下。
     */
    suspend fun setCardSortIndices(updates: List<Pair<String, Double>>)

    suspend fun toggleTodoItem(id: String, done: Boolean)

    suspend fun upsertTag(tag: BoardTag)

    /** 删除标签：子标签升级到其父级；卡片本身不受影响。 */
    suspend fun deleteTag(id: String)

    suspend fun setCardTags(cardId: String, tagIds: List<String>)

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
    suspend fun allCardsIncludingDeleted(): List<BoardCard>

    /**
     * **物理全量白板标签**（含已软删的）：云同步打包用。
     *
     * 为什么不叫 `allTagsIncludingDeleted`：那个名字已被 `TagRepository`
     * （日程模块的标签）占用，两者是**不同的表**，名字必须能区分。
     */
    suspend fun allBoardTagsIncludingDeleted(): List<BoardTag>
}
