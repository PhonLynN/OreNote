package com.phonlynn.oreplan.domain.sync.r2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ListObjectsV2` 响应解析。
 *
 * 解析错了的表现是"云端有对象、但同步认为没有" ⇒ 重复上传、或漏下。
 * 而 R2 的响应形状由 S3 规范固定，所以这里把各种边界都钉住。
 */
class R2ListParserTest {

    @Test
    fun `解析单个对象`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult>
              <Name>orenote-sync</Name>
              <Contents>
                <Key>orenote/entities/item/a.bin</Key>
                <LastModified>2026-08-30T12:00:00.000Z</LastModified>
                <ETag>"d41d8cd98f00b204e9800998ecf8427e"</ETag>
                <Size>1024</Size>
              </Contents>
              <IsTruncated>false</IsTruncated>
            </ListBucketResult>
        """.trimIndent()

        val parsed = R2ListParser.parse(xml)

        assertEquals(1, parsed.keys.size)
        assertEquals("orenote/entities/item/a.bin", parsed.keys[0].key)
        assertEquals("ETag 的双引号要去掉", "d41d8cd98f00b204e9800998ecf8427e", parsed.keys[0].eTag)
        assertEquals(1024L, parsed.keys[0].size)
        assertNull(parsed.nextToken)
    }

    @Test
    fun `解析多个对象`() {
        val xml = """
            <ListBucketResult>
              <Contents><Key>a</Key><ETag>"e1"</ETag><Size>1</Size></Contents>
              <Contents><Key>b</Key><ETag>"e2"</ETag><Size>2</Size></Contents>
              <Contents><Key>c</Key><ETag>"e3"</ETag><Size>3</Size></Contents>
            </ListBucketResult>
        """.trimIndent()

        assertEquals(listOf("a", "b", "c"), R2ListParser.parse(xml).keys.map { it.key })
    }

    @Test
    fun `空列表解析为空`() {
        val xml = "<ListBucketResult><Name>b</Name><KeyCount>0</KeyCount></ListBucketResult>"
        assertTrue(R2ListParser.parse(xml).keys.isEmpty())
    }

    @Test
    fun `解析翻页 token`() {
        val xml = """
            <ListBucketResult>
              <Contents><Key>a</Key><ETag>"e"</ETag><Size>1</Size></Contents>
              <NextContinuationToken>1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM=</NextContinuationToken>
              <IsTruncated>true</IsTruncated>
            </ListBucketResult>
        """.trimIndent()

        assertEquals("1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM=", R2ListParser.parse(xml).nextToken)
    }

    /**
     * 标签顺序不固定：S3 规范没保证 `Size` 在 `Key` 之后。
     * 若实现假设了顺序，换个 S3 兼容实现就会解析出错。
     */
    @Test
    fun `标签顺序不影响解析`() {
        val xml = """
            <ListBucketResult>
              <Contents><Size>2048</Size><ETag>"e"</ETag><Key>reordered</Key></Contents>
            </ListBucketResult>
        """.trimIndent()

        val info = R2ListParser.parse(xml).keys.single()
        assertEquals("reordered", info.key)
        assertEquals(2048L, info.size)
    }

    /** 对象名里的 XML 实体必须反转义，否则同步过去的就是错的文件名。 */
    @Test
    fun `XML 实体被反转义`() {
        val xml = """
            <ListBucketResult>
              <Contents><Key>a&amp;b&lt;c&gt;d&quot;e&apos;f</Key><ETag>"e"</ETag><Size>1</Size></Contents>
            </ListBucketResult>
        """.trimIndent()

        assertEquals("a&b<c>d\"e'f", R2ListParser.parse(xml).keys.single().key)
    }

    /**
     * `&amp;lt;` 的正确结果是字面量 `&lt;`，不是 `<`。
     * 若替换顺序写错（先换 `&amp;`），就会把 `&amp;lt;` 变成 `<` —— 比输入更错。
     */
    @Test
    fun `转义顺序正确不会被二次反转义`() {
        val xml = """
            <ListBucketResult>
              <Contents><Key>x&amp;lt;y</Key><ETag>"e"</ETag><Size>1</Size></Contents>
            </ListBucketResult>
        """.trimIndent()

        assertEquals("x&lt;y", R2ListParser.parse(xml).keys.single().key)
    }

    /** 中文对象名（UTF-8）不能被破坏。 */
    @Test
    fun `中文对象名原样解析`() {
        val xml = """
            <ListBucketResult>
              <Contents><Key>orenote/blobs/笔记.bin</Key><ETag>"e"</ETag><Size>1</Size></Contents>
            </ListBucketResult>
        """.trimIndent()

        assertEquals("orenote/blobs/笔记.bin", R2ListParser.parse(xml).keys.single().key)
    }

    // ---------------------------------------------------------------- 健壮性

    /**
     * **坏掉的一条记录不该让整次同步失败**。
     * 缺 Key 的 Contents 被跳过，其余正常解析。
     */
    @Test
    fun `缺少 Key 的条目被跳过而不是报错`() {
        val xml = """
            <ListBucketResult>
              <Contents><ETag>"e1"</ETag><Size>1</Size></Contents>
              <Contents><Key>good</Key><ETag>"e2"</ETag><Size>2</Size></Contents>
            </ListBucketResult>
        """.trimIndent()

        val parsed = R2ListParser.parse(xml)
        assertEquals(listOf("good"), parsed.keys.map { it.key })
    }

    @Test
    fun `Size 非法时退化为 0 而不是报错`() {
        val xml = """
            <ListBucketResult>
              <Contents><Key>k</Key><ETag>"e"</ETag><Size>not-a-number</Size></Contents>
            </ListBucketResult>
        """.trimIndent()

        assertEquals(0L, R2ListParser.parse(xml).keys.single().size)
    }

    @Test
    fun `缺 ETag 时为空串而不是 null`() {
        val xml = "<ListBucketResult><Contents><Key>k</Key><Size>1</Size></Contents></ListBucketResult>"
        assertEquals("", R2ListParser.parse(xml).keys.single().eTag)
    }

    @Test
    fun `完全不是 XML 时返回空而不是抛异常`() {
        assertTrue(R2ListParser.parse("this is not xml at all").keys.isEmpty())
        assertTrue(R2ListParser.parse("").keys.isEmpty())
    }
}
