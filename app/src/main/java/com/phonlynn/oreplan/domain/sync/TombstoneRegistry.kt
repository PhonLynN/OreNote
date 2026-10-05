package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **墓碑登记处**：哪些实体已经"被删了"。
 *
 * ## 它解决的问题
 *
 * 云同步要求删除可传播 ⇒ 删除必须变成**软删**（打墓碑），行还留在表里。
 * 但界面绝不能显示它们。`entity_ext` 是一张**独立的表**，
 * 没法参与 `items` 的 SQL 过滤（`WHERE deleted = 0` 写不出来），
 * 所以只能在**内存里**维护一份"已删集合"，在仓储出口统一过滤。
 *
 * ## 为什么把整份集合放内存
 *
 * 墓碑天生稀少（只有用户删过的东西），而**查询路径极多**（几十个 DAO 方法）。
 * 若改成"每次查询都去 join 抽屉表"，等于给每个查询加一次额外往返，
 * 且要逐个改 SQL —— 那是把成本摊到热路径上。
 * 放内存：启动读一次，之后只随删除操作增量更新，读取是 O(1) 的集合判断。
 *
 * ## 纪律（重要）
 *
 * 1. **仓储的每一个出口都要过滤**（列表、树、按 id 取、搜索结果、统计）。
 *    漏一处，删掉的东西就会在某个界面复活。
 * 2. 过滤要放在**仓储层**，不要放到 ViewModel —— 否则每个新界面都要记得写一次。
 * 3. 判断用 [isDeleted]（O(1)），不要自己去读抽屉。
 *
 * ## 正确性靠测试兜底
 *
 * 见 `TombstoneFilterTest`：它遍历全部 [SyncEntity]，
 * 断言"打了墓碑的实体在所有读取路径上都不出现"。
 * 新增一个读取路径却忘了过滤时，那条测试是唯一的警报。
 */
@Singleton
class TombstoneRegistry @Inject constructor(
    private val extRepo: EntityExtRepository,
) {

    private val mutex = Mutex()

    /** 表名 → 已删 id 集合。 */
    private val _deleted = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** 供界面订阅（例如"已归档数量"这类需要知道墓碑总数的场景）。 */
    val deleted: StateFlow<Map<String, Set<String>>> = _deleted.asStateFlow()

    /** 是否已经完成首次加载。首次加载前**不做过滤**（宁可显示，不可整个列表空掉）。 */
    private var loaded = false

    /** 从数据库装载一次。`@Singleton`，App 启动时调用一次即可。 */
    suspend fun load() = mutex.withLock {
        val all = extRepo.getAll()
        val map = HashMap<String, MutableSet<String>>()
        all.forEach { record ->
            if (SyncMeta.isTombstone(record.map)) {
                map.getOrPut(record.owner.table) { mutableSetOf() }.add(record.ownerId)
            }
        }
        _deleted.value = map
        loaded = true
    }

    /** 该实体是否已被软删。 */
    fun isDeleted(entity: SyncEntity, id: String): Boolean =
        _deleted.value[entity.table]?.contains(id) == true

    /** 按表名判断（仓储层拿到的常常是表名字符串）。 */
    fun isDeleted(table: String, id: String): Boolean =
        _deleted.value[table]?.contains(id) == true

    /** 过滤任意集合里的已删项。仓储出口统一走它。 */
    fun <T> filter(entity: SyncEntity, items: List<T>, idOf: (T) -> String): List<T> {
        if (!loaded) return items
        val dead = _deleted.value[entity.table] ?: return items
        if (dead.isEmpty()) return items
        return items.filterNot { idOf(it) in dead }
    }

    /**
     * 让一个来自 DAO 的流**跟着墓碑一起重算**。
     *
     * ## ⚠️ 这里是云同步 S1 引入的一个回归（用户报的"删除后不刷新"）
     *
     * 软删之前，删除是 `DELETE FROM items` —— **主表变了**，Room 的
     * `observeXxx()` 自然重新发射，界面立刻更新。
     *
     * 软删之后，删除只往 `entity_ext` 写墓碑，**主表一行都没动**。
     * 于是那个流**再也不会发射**，包在 `map` 里的 `filter(...)` 也就
     * 永远没机会重跑 —— 界面拿到的还是旧列表。
     *
     * 表现就是用户描述的那句：
     *
     * > 「删除内容没有实时更新，需要我切换一下页面，刷新一下才能更新状态」
     *
     * 切页面之所以能"修好"，是因为重进时重新订阅、`filter` 重新跑了一遍。
     *
     * ## 用法
     *
     * 所有**在 `map` 里做墓碑过滤**的流都必须经过它 ——
     * 漏掉任何一个出口，那个界面就会在删除后不刷新，而且
     * 只在"删过这一类数据"的设备上复现，极难定位（与 [filter] 的注释同一个道理）。
     *
     * @param entity 这个流对应的实体种类
     * @param source DAO 的原始流（**未过滤**）
     */
    fun <T> observing(entity: SyncEntity, source: Flow<List<T>>): Flow<List<T>> =
        combine(source, _deleted) { rows, _ ->
            // 墓碑变了也要重算；具体过滤在调用方的 map 里做
            rows
        }

    /** 单个实体：被删则返回 null。用于 `getById` 这类"按 id 取一个"的出口。 */
    fun <T : Any> filterOne(entity: SyncEntity, value: T?, id: String): T? =
        if (value != null && isDeleted(entity, id)) null else value

    /**
     * 标记为已删。**由仓储的删除路径调用**，不要直接调。
     *
     * 注意它只更新内存；墓碑写进抽屉是仓储的事（同一个事务里做）。
     */
    suspend fun markDeleted(entity: SyncEntity, id: String) = mutex.withLock {
        val current = _deleted.value
        val set = current[entity.table].orEmpty() + id
        _deleted.value = current + (entity.table to set)
    }

    /** 批量标记（删子树时用）。 */
    suspend fun markDeletedAll(entity: SyncEntity, ids: Collection<String>) = mutex.withLock {
        if (ids.isEmpty()) return@withLock
        val current = _deleted.value
        val set = current[entity.table].orEmpty() + ids
        _deleted.value = current + (entity.table to set)
    }

    /** 取消删除（恢复）。同步合并时"远端改 > 本地删"的例外场景用得上。 */
    suspend fun unmarkDeleted(entity: SyncEntity, id: String) = mutex.withLock {
        val current = _deleted.value
        val set = current[entity.table].orEmpty() - id
        _deleted.value = current + (entity.table to set)
    }

    /** 该表已删 id 的只读快照（清理、统计用）。 */
    fun deletedIds(entity: SyncEntity): Set<String> = _deleted.value[entity.table].orEmpty()

    companion object {
        /** 判断一个抽屉是不是墓碑 —— 供仓储在**装载前**的同步路径复用。 */
        fun isTombstone(map: ExtMap): Boolean = SyncMeta.isTombstone(map)
    }
}
