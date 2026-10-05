package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.Item
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * 条目仓储。日程、规划、目标走同一个入口 —— 它们共用一张表、一套模型。
 *
 * 仓储只做「读写 + 树结构维护」，不含业务规则；跨仓储的编排放在用例层。
 */
interface ItemRepository {

    /** 全部条目，按树序排列。 */
    fun observeAll(): Flow<List<Item>>

    /** 一次性取全部条目，给导出、小组件这类场景用。 */
    suspend fun getAll(): List<Item>

    /**
     * 取**全部物理行**，包含已软删（打了墓碑）的。
     *
     * ⚠️ 只有两类调用方该用它：
     *  1. **删除路径**：找子树必须看物理全量，否则会漏掉已有墓碑的节点、
     *     留下一批没打墓碑的孤儿（它们不可见、却会在下次同步时以"还活着"的身份传出去）；
     *  2. **同步上传**：要传的是物理全量（含墓碑），否则删除传不出去。
     *
     * 界面与业务查询**一律用 [getAll]** —— 那是过滤后的。
     * 用错这里的表现是"删掉的东西又出现了"，所以调用点要少而明确。
     */
    suspend fun getAllIncludingDeleted(): List<Item>

    /** 某一层的直接子节点。[parentId] 为 null 表示根层级。 */
    fun observeChildren(parentId: String?): Flow<List<Item>>

    /** 一次性取某一层的直接子节点。拖动落位要拿它算新排序键。 */
    suspend fun getChildren(parentId: String?): List<Item>

    /** 整棵子树（含自身），按树序排列。 */
    fun observeSubtree(rootId: String): Flow<List<Item>>

    /** 与 [since, until) 有时间交集的已排期条目。左闭右开，避免相邻日程被算成重叠。 */
    fun observeScheduledBetween(since: Instant, until: Instant): Flow<List<Item>>

    /** 期望完成期落在 [since, until] 内的规划条目。 */
    fun observeDueBetween(since: Instant, until: Instant): Flow<List<Item>>

    suspend fun getById(id: String): Item?

    /** 写入新条目，返回其 id。 */
    suspend fun create(item: Item): String

    /** 更新条目本身。**不负责**位置与路径，那两件事走 [reparent]。 */
    suspend fun update(item: Item)

    /** 删除整棵子树（含自身）。 */
    suspend fun deleteSubtree(rootId: String)

    /** 改挂整棵子树。[newParentId] 为 null 表示变成根节点。 */
    suspend fun reparent(itemId: String, newParentId: String?, orderIndex: Double? = null)

    /**
     * 把某一层的排序键重新均匀分布，返回重排后的子节点（已带新键）。
     *
     * 需要它的理由：反复往同一个缝隙里插入会把两个键的中点逼近到浮点精度以下，
     * 那时中点等于邻居，顺序就不再确定。
     */
    suspend fun renormalizeSiblings(parentId: String?): List<Item>

    /**
     * 待办页拖动排序落库：**一次事务**写序号 + 必要的换组/换父组。
     *
     * 为什么不循环调 [reparent]：同层重排会动整层（N 条），逐条写 = N 次发射
     * → N 次重排，松手瞬间整个列表会抖 N 下（0.1.8 的教训）。
     */
    suspend fun applyTodoOrder(changes: List<TodoOrderChange>)
}

/**
 * 拖动排序要写的一行改动。
 *
 * [setGroup] / [setParent] 是**显式开关**：字段是 `String?`，而 null 既可能是
 * 「移到顶层」也可能是「不动」，靠开关区分 —— 这种歧义最后一定会变成脏数据。
 */
data class TodoOrderChange(
    val itemId: String,
    val orderIndex: Double,
    /** 待办：新的归属组（[setGroup] 为 true 时生效；null = 移出到无组）。 */
    val groupId: String? = null,
    val setGroup: Boolean = false,
    /** 待办组：新的父组（[setParent] 为 true 时生效；null = 移到顶层）。 */
    val parentId: String? = null,
    val setParent: Boolean = false,
)
