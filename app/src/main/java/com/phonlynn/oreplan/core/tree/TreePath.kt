package com.phonlynn.oreplan.core.tree

/**
 * 层级条目用**物化路径**存树：每个节点存形如 `/a1b2/c3d4/` 的完整路径（含自身 id）。
 *
 * 为什么选它而不是闭包表或纯递归：
 *  - 查子树只需要一次 `LIKE '前缀%'`，不需要递归查询；
 *  - 移动整棵子树只需要一条 UPDATE 重写路径前缀，不需要逐节点操作；
 *  - 排序直接按路径排，天然就是稳定的树序。
 *
 * 本地单用户场景下闭包表属于过度设计（关系行数会膨胀、维护成本更高）。
 *
 * 这里全部是纯函数，方便单测；与数据库的配合在 ItemRepository 里。
 */
object TreePath {

    const val SEPARATOR = '/'

    /** 根节点路径。 */
    fun root(id: String): String = "$SEPARATOR$id$SEPARATOR"

    /** 在父路径下挂一个子节点。 */
    fun childOf(parentPath: String, id: String): String = "$parentPath$id$SEPARATOR"

    /** `LIKE` 前缀，匹配整棵子树（含自身）。 */
    fun subtreePrefix(path: String): String = "$path%"

    private fun segments(path: String): List<String> =
        path.split(SEPARATOR).filter { it.isNotEmpty() }

    /** 层级深度，根为 0。 */
    fun depthOf(path: String): Int = (segments(path).size - 1).coerceAtLeast(0)

    /** 路径末段的 id，也就是该节点自身的 id。 */
    fun idOf(path: String): String = segments(path).lastOrNull().orEmpty()

    /** 父节点路径；根节点返回 null。 */
    fun parentPathOf(path: String): String? {
        val items = segments(path)
        if (items.size <= 1) return null
        return items.dropLast(1).joinToString(
            separator = SEPARATOR.toString(),
            prefix = SEPARATOR.toString(),
            postfix = SEPARATOR.toString(),
        )
    }

    /** 从根到该节点自身的所有路径（含自身）。 */
    fun pathChainOf(path: String): List<String> {
        val items = segments(path)
        return (1..items.size).map { take ->
            items.take(take).joinToString(
                separator = SEPARATOR.toString(),
                prefix = SEPARATOR.toString(),
                postfix = SEPARATOR.toString(),
            )
        }
    }

    /**
     * 把 [path] 从旧祖先前缀改成新祖先前缀。用于整棵子树一次性改挂。
     * [oldAncestor] 必须是 [path] 的前缀，否则原样返回。
     */
    fun reparent(path: String, oldAncestor: String, newAncestor: String): String =
        if (path.startsWith(oldAncestor)) newAncestor + path.removePrefix(oldAncestor) else path

    /**
     * 校验一次改挂是否合法。
     *
     * 唯一的非法情形：把节点挂到**自己或自己的后代**下面，那会形成环，
     * 整棵子树会从树上脱落并且无法再被遍历到。[newParentPath] 为 null 表示改为根节点。
     */
    fun canReparent(path: String, newParentPath: String?): Boolean {
        if (newParentPath == null) return true
        return !newParentPath.startsWith(path)
    }

    /** 深度变化量，用于批量更新子树里每个节点的 depth。 */
    fun depthDelta(oldParentDepth: Int, newParentDepth: Int): Int = newParentDepth - oldParentDepth
}
