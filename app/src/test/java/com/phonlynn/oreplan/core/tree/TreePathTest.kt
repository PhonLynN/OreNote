package com.phonlynn.oreplan.core.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreePathTest {

    @Test
    fun `根节点路径用斜杠包住自身 id`() {
        assertEquals("/a/", TreePath.root("a"))
    }

    @Test
    fun `子节点路径接在父路径后面`() {
        assertEquals("/a/b/", TreePath.childOf("/a/", "b"))
    }

    @Test
    fun `深度按层数算 根为 0`() {
        assertEquals(0, TreePath.depthOf("/a/"))
        assertEquals(1, TreePath.depthOf("/a/b/"))
        assertEquals(3, TreePath.depthOf("/a/b/c/d/"))
    }

    @Test
    fun `取末段 id 得到自身 id`() {
        assertEquals("d", TreePath.idOf("/a/b/c/d/"))
        assertEquals("a", TreePath.idOf("/a/"))
    }

    @Test
    fun `根节点没有父路径`() {
        assertNull(TreePath.parentPathOf("/a/"))
        assertEquals("/a/", TreePath.parentPathOf("/a/b/"))
        assertEquals("/a/b/", TreePath.parentPathOf("/a/b/c/"))
    }

    @Test
    fun `子树前缀能匹配自身与全部后代`() {
        val prefix = TreePath.subtreePrefix("/a/")
        assertEquals("/a/%", prefix)
        assertTrue("/a/".startsWith("/a/"))
        assertTrue("/a/b/".startsWith("/a/"))
        assertTrue("/a/b/c/".startsWith("/a/"))
        assertFalse("/ab/".startsWith("/a/"))
    }

    @Test
    fun `前缀匹配不会把兄弟节点吃进来`() {
        // 这是物化路径最经典的一个坑：如果分隔符处理不当，/a/ 会匹配到 /aa/。
        assertFalse("/aa/b/".startsWith("/a/"))
    }

    @Test
    fun `改挂把整棵子树从旧前缀换到新前缀`() {
        val moved = TreePath.reparent("/a/b/c/", oldAncestor = "/a/", newAncestor = "/x/")
        assertEquals("/x/b/c/", moved)
    }

    @Test
    fun `改挂到根层级`() {
        assertEquals("/b/c/", TreePath.reparent("/a/b/c/", "/a/", "/"))
    }

    @Test
    fun `改挂对不匹配的路径原样返回`() {
        assertEquals("/other/", TreePath.reparent("/other/", "/a/", "/x/"))
    }

    @Test
    fun `不能挂到自己的后代下面`() {
        assertFalse(TreePath.canReparent("/a/", "/a/b/"))
        assertFalse(TreePath.canReparent("/a/b/", "/a/b/c/d/"))
    }

    @Test
    fun `可以挂到自己身上视为不动 但挂到兄弟下是合法的`() {
        assertFalse(TreePath.canReparent("/a/", "/a/"))
        assertTrue(TreePath.canReparent("/a/b/", "/x/y/"))
    }

    @Test
    fun `可以改为根节点`() {
        assertTrue(TreePath.canReparent("/a/b/", null))
    }

    @Test
    fun `路径链从根一路到自身`() {
        assertEquals(listOf("/a/"), TreePath.pathChainOf("/a/"))
        assertEquals(listOf("/a/", "/a/b/", "/a/b/c/"), TreePath.pathChainOf("/a/b/c/"))
    }

    @Test
    fun `深度变化量按新旧父节点深度差算`() {
        // 从根层级(父深度 -1 视作不存在的父)挂到深度 1 的父下面 => 深度 2
        assertEquals(2, TreePath.depthDelta(oldParentDepth = 0, newParentDepth = 2))
        assertEquals(-1, TreePath.depthDelta(oldParentDepth = 1, newParentDepth = 0))
    }
}
