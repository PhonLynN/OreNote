package com.phonlynn.oreplan.core.rt

/**
 * 光标与选区的坐标换算（2026-09-23，沿用旧版口径）。
 *
 * 编辑状态里同时存在两套坐标：
 *
 * - **全局下标**：把 [RichDoc.plainText]（各段用 `\n` 连接）当成一个字符串时的下标。
 *   这是编辑操作与选区的口径。
 * - **段内坐标** `(line, col)`：第几段、段内第几个字符。
 *
 * 两套之间必须严格互转：差一个字符，光标就会跳到相邻行或落到段外，
 * 表现为「打字位置乱跳」「退格删错字」。所以换算单独成文件并逐项单测。
 *
 * # 与显示文字的换算
 *
 * 文本流里每段还多了**符号前缀**（见 [RichTextBuild]）。
 * 因此另有一套 `content ⇄ display` 的换算，在 [RichTextBuild] 里，
 * 因为只有构造显示文字的那一层才知道前缀长度怎么算。
 * 本文件只负责纯正文这一侧的坐标。
 */

/** 段内坐标。 */
data class CaretPos(val line: Int, val col: Int)

/**
 * 全局下标 → 段内坐标。
 *
 * `\n` 本身属于「前一段的末尾之后」：位置落在 `\n` 上时归到**下一段的 0 列**
 * （与 Compose 的光标语义一致——光标在行首）。
 *
 * 越界一律夹到合法范围，绝不抛异常。
 */
fun RichDoc.caretOf(global: Int): CaretPos {
    val g = global.coerceIn(0, textLength)
    var acc = 0
    for (i in lines.indices) {
        val len = lines[i].text.length
        if (g <= acc + len) return CaretPos(i, g - acc)
        acc += len + 1
    }
    val last = lines.lastIndex.coerceAtLeast(0)
    return CaretPos(last, lines.getOrNull(last)?.text?.length ?: 0)
}

/**
 * 段内坐标 → 全局下标。
 *
 * 与 [caretOf] 严格互逆（在合法坐标域内）。
 */
fun RichDoc.globalOf(line: Int, col: Int): Int {
    val li = line.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    var acc = 0
    for (i in 0 until li) acc += lines[i].text.length + 1
    val len = lines.getOrNull(li)?.text?.length ?: 0
    return acc + col.coerceIn(0, len)
}

/**
 * 取某段在全局坐标里的**起始下标**。
 *
 * 用途：把「段内列」换算成「全局列」，供选区判断等使用。
 */
fun RichDoc.lineStartGlobal(line: Int): Int {
    val li = line.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    var acc = 0
    for (i in 0 until li) acc += lines[i].text.length + 1
    return acc
}

/**
 * 把全局选区裁到**单段内**，返回 `(段号, 段内起, 段内止)`。
 *
 * 工具栏的「对选区切换属性」按段处理：选区跨段时只作用于**光标所在段**
 * （与通用编辑器的行为一致，也避免一次点击改动整篇）。
 * 返回的 `from < to` 才表示该段内真的有选中内容。
 */
fun RichDoc.selectionInLine(selStart: Int, selEnd: Int): Triple<Int, Int, Int> {
    val a0 = minOf(selStart, selEnd)
    val b0 = maxOf(selStart, selEnd)
    val pos = caretOf(b0)
    val li = pos.line.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    val len = lines.getOrNull(li)?.text?.length ?: 0

    val startGlobal = lineStartGlobal(li)
    val endGlobal = startGlobal + len

    val from = (maxOf(a0, startGlobal) - startGlobal).coerceIn(0, len)
    val to = (minOf(b0, endGlobal) - startGlobal).coerceIn(from, len)
    return Triple(li, from, to)
}

/**
 * 有序列表在某段显示的编号。
 *
 * 编号不落库（不写进正文），每次渲染按当前位置算 —— 于是插入/删除
 * 有序项时编号自动重排，不需要改任何数据。
 *
 * ## 规则（用户 2026-09-30 明确）
 *
 * **编号连续，类型不转换**：
 * - 只数**有序行**（每行占一个号）；
 * - **无序行 / 复选框行**：**跳过**——既**不占号**也**不重置**编号，
 *   下一条有序行接着数（例：`1.甲` / `- 乙` / `2.丙`，丙 = 2）；
 * - **纯文本行（含空行）**：**截断**——编号从此重新从 1 开始。
 *
 * 即：只有「空行」或「主动转成纯文本」才打断有序列表；
 * 夹在中间的其它列表类型不打断它。
 */
fun orderedNumberAt(doc: RichDoc, index: Int): Int {
    var n = 1
    var i = index - 1
    while (i >= 0) {
        when (doc.lines[i].kind) {
            RichLineKind.ORDERED -> n++
            // 纯文本（含空行）= 截断：编号重新起算。
            RichLineKind.TEXT -> break
            // 无序 / 复选框：跳过，不占号也不打断。
            else -> Unit
        }
        i--
    }
    return n
}

/**
 * 取某段在**显示文字**里的起始下标（**含前缀**）。
 *
 * 与 [lineStartGlobal] 的区别：那个是「纯正文」口径（用于编辑操作），
 * 这个是「显示流」口径（用于把符号画到正确的横向位置）。
 */
fun RichDoc.displayLineStart(line: Int): Int {
    val li = line.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    var acc = 0
    for (i in 0 until li) {
        acc += RichTextMark.prefixLengthOf(lines[i].kind) + lines[i].text.length + 1
    }
    return acc
}

/** 当前文档在显示文字里的总长度（含前缀与换行符）。 */
fun RichDoc.displayLength(): Int {
    var acc = 0
    lines.forEachIndexed { i, l ->
        acc += RichTextMark.prefixLengthOf(l.kind) + l.text.length
        if (i < lines.lastIndex) acc += 1
    }
    return acc
}
