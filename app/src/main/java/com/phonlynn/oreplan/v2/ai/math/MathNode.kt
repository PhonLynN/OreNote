package com.phonlynn.oreplan.v2.ai.math

/**
 * **原子的类型** —— 公式里每个元素在排版上属于哪一类。
 *
 * ## ⚠️ 为什么要引入它（用户 2026-10-04）
 *
 * 用户原话：
 *
 * > 「如果符号间隙问题还需要我指出具体位置，说明你的渲染器设计**对不同字符是不公平的**，
 * >   现在字符之间的空隙是不一样的，有些留了，有些没留，我完全没法给你一一指出」
 *
 * 这句话指出了根本问题：原来的做法是**白名单** ——
 *
 * ```kotlin
 * if (symbol in MATH_BINARY_OPS || symbol in MATH_RELATIONS) { 加空隙 } else { 0f }
 * ```
 *
 * 于是**任何不在那两张表里的字符永远没有空隙**，而表是人手列的、必然有漏。
 * 用户说的"有些留了有些没留"不是随机的，就是"在不在表里"决定的。
 *
 * ## 正确做法：TeX 的原子类型 + 类型×类型间距表
 *
 * TeX 给**每一个**原子一个类型（8 种），间距由 [ATOM_SPACING] 这张
 * **8×8 表**决定 —— 这是**封闭**的：
 *
 * · 不会因为"漏了某个字符"而少空隙（默认类型 [AtomType.ORD] 也在表里有定义）
 * · 不会因为"多加了某个字符"而多空隙
 * · 新符号只要归对类，间距自动正确
 *
 * 这就是"对每个字符公平"的含义：**判定依据是类型，不是字符本身**。
 */
internal enum class AtomType {
    /** 普通字符：变量、数字、`/`、`∂`、`∇`… */
    ORD,

    /** 大型算符：`∑` `∫` `∏` */
    OP,

    /** 二元运算符：`+` `−` `×` `·` */
    BIN,

    /** 关系符：`=` `≤` `→` `∈` */
    REL,

    /** 开定界符：`(` `[` `{` */
    OPEN,

    /** 闭定界符：`)` `]` `}` */
    CLOSE,

    /** 标点：`,` `;` —— **后面**留一点空，前面不留 */
    PUNCT,

    /** 内部原子（分式、根号这类"整体"） */
    INNER,
}

/**
 * TeX 的 **atom spacing 表**（`tex.web` 里的 `mathex` / 附录 G 第 18 条）。
 *
 * ## 怎么读
 *
 * 行 = 左边的类型，列 = 右边的类型，值 = 两者之间插入的空隙（单位 em）。
 *
 * | 记号 | 宽度 |
 * |---|---|
 * | 0 | 不留空 |
 * | 1 | thin space (3/18 em ≈ 0.167) |
 * | 2 | medium space (4/18 em ≈ 0.222) |
 * | 3 | thick space (5/18 em ≈ 0.278) |
 *
 * ⚠️ 表是**封闭**的：每一格都有定义，所以不存在"漏掉某个字符"这回事。
 * 这正是用户要的"公平"。
 *
 * ⚠️ 与 TeX 的差异：TeX 区分 display/text style（display 下有些 0 会变成 thin space），
 * 我们只做行内与块级**同一套**（块级公式在手机上也按紧凑排）。
 */
private val ATOM_SPACING: Map<Pair<AtomType, AtomType>, Float> = buildMap {
    fun put(a: AtomType, b: AtomType, em: Float) { this[a to b] = em }

    // ---- ORD 在左
    put(AtomType.ORD, AtomType.OP, 1f)
    put(AtomType.ORD, AtomType.BIN, 2f)
    put(AtomType.ORD, AtomType.REL, 3f)
    put(AtomType.ORD, AtomType.INNER, 1f)
    // ---- OP 在左
    put(AtomType.OP, AtomType.ORD, 1f)
    put(AtomType.OP, AtomType.OP, 1f)
    put(AtomType.OP, AtomType.BIN, 2f)
    put(AtomType.OP, AtomType.REL, 3f)
    put(AtomType.OP, AtomType.INNER, 1f)
    // ---- BIN 在左
    put(AtomType.BIN, AtomType.ORD, 2f)
    put(AtomType.BIN, AtomType.OP, 2f)
    put(AtomType.BIN, AtomType.OPEN, 2f)
    put(AtomType.BIN, AtomType.INNER, 2f)
    // ---- REL 在左
    put(AtomType.REL, AtomType.ORD, 3f)
    put(AtomType.REL, AtomType.OP, 3f)
    put(AtomType.REL, AtomType.OPEN, 3f)
    put(AtomType.REL, AtomType.INNER, 3f)
    // ---- CLOSE 在左
    put(AtomType.CLOSE, AtomType.OP, 1f)
    put(AtomType.CLOSE, AtomType.BIN, 2f)
    put(AtomType.CLOSE, AtomType.REL, 3f)
    put(AtomType.CLOSE, AtomType.INNER, 1f)
    // ---- PUNCT 在左：**只有后面**留空（还额外加一点点，见 punctExtra）
    put(AtomType.PUNCT, AtomType.ORD, 1f)
    put(AtomType.PUNCT, AtomType.OP, 1f)
    put(AtomType.PUNCT, AtomType.OPEN, 1f)
    put(AtomType.PUNCT, AtomType.INNER, 1f)
    // ---- INNER 在左
    put(AtomType.INNER, AtomType.ORD, 1f)
    put(AtomType.INNER, AtomType.OP, 1f)
    put(AtomType.INNER, AtomType.BIN, 2f)
    put(AtomType.INNER, AtomType.REL, 3f)
    put(AtomType.INNER, AtomType.OPEN, 1f)
    put(AtomType.INNER, AtomType.PUNCT, 1f)
    put(AtomType.INNER, AtomType.INNER, 1f)
    // ---- OPEN 在左：**一律不留空**（`(a` 里的括号紧贴内容）
    // ---- 其余组合（含 OPEN 在左）默认 0，见 [atomGapEm]
}

/**
 * TeX 的三种间距宽度（单位 em，取自 `tex.web` 的附录 G 第 18 条）。
 *
 * | 记号 | TeX 名 | 宽度 | em |
 * |---|---|---|---|
 * | 1 | thin space | 3/18 quad | **0.1667** |
 * | 2 | medium space | 4/18 quad | **0.2222** |
 * | 3 | thick space | 5/18 quad | **0.2778** |
 *
 * ⚠️ 三种**不是等比的**（3/18、4/18、5/18），所以
 * [ATOM_SPACING] 表里存的是**档位编号**，由本函数映射成真实宽度。
 * 我第一版把档位直接乘一个固定的 thin 值，结果 thick = 3×thin = 0.5em，
 * 比 TeX 宽了近一倍（测试当场抓到）。
 */
private fun spaceEm(level: Float): Float = when (level) {
    1f -> 3f / 18f
    2f -> 4f / 18f
    3f -> 5f / 18f
    else -> 0f
}

/**
 * 两个原子类型之间该插多少空隙（em）。
 *
 * ⚠️ **表里没有的组合一律返回 0** —— 这不是"漏了"，而是 TeX 的规定
 *（开括号左侧、闭括号右侧本来就不留空）。
 * 与白名单式的区别在于：**每个原子都有类型**，所以判定永远有依据 ——
 * 这就是用户要的"对每个字符公平"。
 */
internal fun atomGapEm(left: AtomType, right: AtomType): Float =
    spaceEm(ATOM_SPACING[left to right] ?: 0f)

/**
 * 二元运算符 —— 它们构成 [AtomType.BIN]。
 *
 * ⚠️ 这张表的作用已经**只剩"分类"**，不再直接决定间距
 *（间距由 [atomGapEm] 按类型算）。所以即使漏了某个符号，
 * 后果也只是"它被当成 ORD"，而不是"它两侧完全没有空隙"。
 */
internal val MATH_BINARY_OPS = setOf(
    "+", "-", "−", "±", "∓", "×", "÷", "·", "∙", "∗", "⋆", "∘",
    "⊕", "⊗", "∪", "∩", "∧", "∨", "∖",
)

/** 关系符 —— 构成 [AtomType.REL]。 */
internal val MATH_RELATIONS = setOf(
    "=", "<", ">", "≤", "≥", "≠", "≡", "≈", "∼", "≃", "≅", "∝", "≪", "≫",
    "→", "←", "⇒", "⇐", "↔", "⇔", "↦", "⟹", "⟺",
    "∈", "∉", "∋", "⊂", "⊃", "⊆", "⊇", "∴", "∵", "⊥", "∥",
)

/**
 * 标点 —— 构成 [AtomType.PUNCT]（**后面**留空、前面不留）。
 *
 * ⚠️ 包含**中文标点**。原来只有英文 `,` `;`，
 * 而中文公式里 `，` `；` `。` 很常见 —— 那是"有些字符没空隙"的一个具体来源。
 */
internal val MATH_PUNCT = setOf(
    ",", ";",
    "\uFF0C", // ，
    "\uFF1B", // ；
    "\u3001", // 、
)

/** 定界符（左、右都有）。 */
internal val MATH_DELIMS = setOf("(", ")", "[", "]", "{", "}", "|", "⌈", "⌉", "⌊", "⌋", "⟨", "⟩")

/** **开**定界符 —— 构成 [AtomType.OPEN]（也是"一元前缀"的判据）。 */
internal val MATH_OPEN_DELIMS = setOf("(", "[", "{", "|", "⌈", "⌊", "⟨")

/** **闭**定界符 —— 构成 [AtomType.CLOSE]。 */
internal val MATH_CLOSE_DELIMS = setOf(")", "]", "}", "|", "⌉", "⌋", "⟩")

/**
 * 一个字符的 [AtomType]。
 *
 * ## ⚠️ 这个函数是"公平"的关键
 *
 * 它**对每个字符都有返回值**（兜底 [AtomType.ORD]），
 * 而不是"在表里就特殊、不在就完全不管"。
 * 于是间距判定永远有依据，不会出现"这个字符忘了加进表所以没空隙"。
 */
internal fun atomTypeOf(text: String): AtomType = when {
    text.length != 1 -> AtomType.INNER      // 多字符节点（合并过的标识符）当整体
    text in MATH_RELATIONS -> AtomType.REL
    text in MATH_BINARY_OPS -> AtomType.BIN
    text in MATH_PUNCT -> AtomType.PUNCT
    text in MATH_CLOSE_DELIMS -> AtomType.CLOSE
    text in MATH_OPEN_DELIMS -> AtomType.OPEN
    else -> AtomType.ORD                     // 兜底：变量、数字、∂、∇、…
}

/**
 * 这个单字符节点要不要**单独占一个原子**（不参与相邻合并）。
 *
 * 合并是为了让 `abc` 这类多字母标识符有正常字距；但运算符、关系符、标点、
 * 括号必须独立，否则 `-∇` 会被并成一个节点，间距规则就无从判断了。
 *
 * ⚠️ 判据用 [MATH_DELIMS]（左右都要独立）。注意这与"一元前缀只认左括号"
 * 是两件事 —— [atomTypeOf] 会把它们分别归成 OPEN / CLOSE。
 */
private fun isStandaloneAtom(text: String): Boolean =
    atomTypeOf(text) != AtomType.ORD && text.length == 1

/**
 * 数学公式的节点树。
 *
 * ## 为什么是树而不是字符串
 *
 * 分数要**上下叠**、根号要**罩住**被开方数、上下标要**缩小并偏移** ——
 * 这些都不是一段文本能表达的。必须先解析成结构，再按结构排版。
 *
 * ## 支持的 LaTeX 子集
 *
 * 覆盖大模型实际会吐出来的那些（不是完整 LaTeX，也不打算是）：
 *
 * · 希腊字母、关系符、运算符、箭头、大型算符（∑ ∏ ∫ ⋯）
 * · `\frac{}{}`（含 `\dfrac` / `\tfrac`）、`\sqrt[]{}`
 * · `^{}` `_{}` 上下标（可嵌套）
 * · `\sum_{i=1}^{n}` 这类**上下限排在正上/正下**的大型算符
 * · `\overline{}`、`\hat{}` `\vec{}` `\bar{}` `\tilde{}` `\dot{}`
 * · `\text{}` `\mathrm{}` `\mathbf{}` `\operatorname{}`
 * · `\left(` `\right)`（括号本身照常显示，只是不参与定高）
 * · `\quad` `\,` `\;` 等间距
 *
 * ## 明确不支持
 *
 * `\begin{matrix}` 这类环境、`\overbrace`、自定义宏、颜色。
 * 遇到不认识的命令时**退化成它的名字**（正体），而不是原样吐 `\xxx` ——
 * 后者在对话里非常刺眼。
 */
sealed interface MathNode {

    /**
     * **显式的间距**（`\,` `\:` `\;` `\quad` …）。
     *
     * ## ⚠️ 为什么必须与 [Sym] 分开（用户 2026-10-04 第七张图）
     *
     * 用户的问题：
     *
     * > 「为什么中间式子这个 miu 和 delta 间距要大一些，
     * >   又为什么只有他们两个之间大一些」
     *
     * 原文那一截是 `\frac{2}{3}\mu\,\delta_{ij}` —— `μ` 和 `δ` 之间有个 **`\,`**。
     *
     * 原来把 `\,` 解析成一个 [Sym]（内容是 U+2009 空白字符），于是它**既是内容、
     * 又参与间距计算**：
     *
     * ```
     * μ  [\, 这个"原子"]  δ
     *      ↑ 自己的字形就是一段空白 (≈2.8dp)
     *      ↑ 还被当普通原子，与左右各算一次间距 —— 再叠一层
     * ```
     *
     * 结果那一处 3.6dp，而周围都是 1~3px。**公式里只有那里有 `\,`，所以只有那里大**。
     *
     * ## 正确做法：间距就是间距，不占原子
     *
     * `\,` 这类命令的语义是"**在这里插入一段空白**"，它没有字形、也不该
     * 参与 atom spacing。所以建一个独立的 [Space] 节点，
     * 渲染层直接出一个 `Spacer`，不查间距表。
     *
     * @param em 宽度（em）。`\,`=3/18、`\:`=4/18、`\;`=5/18、`\quad`=1、`\qquad`=2
     */
    data class Space(val em: Float) : MathNode

    /**
     * 一段字面文本。数学变量用斜体，数字/运算符/函数名用正体（TeX 惯例）。
     */
    data class Sym(
        val text: String,
        val italic: Boolean = false,
        val bold: Boolean = false,
    ) : MathNode

    /** 横向序列。 */
    data class Row(val children: List<MathNode>) : MathNode

    /** 分数：分子 / 分数线 / 分母。 */
    data class Frac(val numerator: MathNode, val denominator: MathNode) : MathNode

    /** 根号。[index] 是开方次数（`\sqrt[3]{}` 的 3）。 */
    data class Sqrt(val index: MathNode?, val body: MathNode) : MathNode

    /** 上下标。两者可以同时存在（`x_i^2`）。 */
    data class Script(val base: MathNode, val sup: MathNode?, val sub: MathNode?) : MathNode

    /**
     * 大型算符。
     *
     * [limitsAbove] 区分两种排版惯例：
     *  · `true` —— 上下限排在算符**正上/正下**（∑、∏、lim、max）
     *  · `false` —— 上下限排在算符**右侧**（∫，行内习惯）
     */
    data class BigOp(
        val text: String,
        val sup: MathNode?,
        val sub: MathNode?,
        val limitsAbove: Boolean,
    ) : MathNode

    /** 上划线（`\overline{}`）。 */
    data class Overline(val body: MathNode) : MathNode

    /**
     * **可自动定高的括号对**（`\left( … \right)`）。
     *
     * ## 为什么单独立一个节点（用户 2026-10-04）
     *
     * 用户原话：
     *
     * > 「ds的括号适配了公式高度，而不是一直使用一行高的小括号」
     *
     * 原来的实现把 `\left` / `\right` 当成**噪音直接丢掉**，
     * 只把括号字符本身当普通 [Sym] 吐出来 —— 于是无论里面装多高的东西
     *（分式、根号、嵌套矩阵），括号永远是**一行高的小括号**，
     * 看起来像"括号被内容撑破了"。
     *
     * TeX 的 `\left(` / `\right)` 语义就是"括号按内容高度自动伸缩"。
     * 这里把它建成一个节点，渲染层据此画**自适应的弧线**。
     *
     * @param left 左定界符（`(`、`[`、`{`、`|`、`⌈`…）；`null` 表示不可见（`\left.`）
     * @param right 右定界符；`null` 表示不可见（`\right.`）
     * @param body 括号里的内容
     */
    data class Delimited(
        val left: String?,
        val right: String?,
        val body: MathNode,
    ) : MathNode
}

/**
 * LaTeX 子集解析器。
 *
 * 手写递归下降。不依赖任何三方库（项目纪律：零第三方依赖，
 * 而 LaTeX 排版库的体积与 kotlin-stdlib 版本约束都不合适）。
 *
 * **容错优先**：公式里出现不认识的东西时尽量跳过并继续，
 * 而不是整段放弃 —— 半条公式能看，总比露出原始 LaTeX 源码强。
 */
object MathParser {

    /** 解析失败（括号不配对等）时返回 null，调用方退回显示原文。 */
    fun parse(source: String): MathNode? = runCatching {
        val parser = Parser(source)
        val row = parser.parseRow(topLevel = true)
        if (row.isEmpty()) null else MathNode.Row(row)
    }.getOrNull()

    // ---------------------------------------------------------------- 实现

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseRow(topLevel: Boolean): List<MathNode> {
            val out = ArrayList<MathNode>()
            while (pos < src.length) {
                val c = src[pos]
                if (c == '}') {
                    if (topLevel) pos++ else break
                    continue
                }
                // `&` 是表格/对齐符，`\\` 是换行 —— 都不支持，跳过
                if (c == '&') { pos++; continue }
                if (c.isWhitespace()) { pos++; continue }
                val atom = parseAtom() ?: continue
                out += atom
            }
            return merge(out)
        }

        private fun parseAtom(): MathNode? {
            var node = parseBareAtom() ?: return null

            // 上下标：可能连续出现（`x_i^2` 与 `x^2_i` 都合法）
            var sup: MathNode? = null
            var sub: MathNode? = null
            while (true) {
                skipSpaces()
                when (src.getOrNull(pos)) {
                    '^' -> { pos++; sup = parseScriptArg() }
                    '_' -> { pos++; sub = parseScriptArg() }
                    else -> break
                }
            }

            if (sup != null || sub != null) {
                node = if (node is MathNode.BigOp) {
                    node.copy(sup = sup ?: node.sup, sub = sub ?: node.sub)
                } else {
                    MathNode.Script(node, sup, sub)
                }
            }
            return node
        }

        /**
         * 一个"裸"原子：**不消费后面的上下标**。
         *
         * ## 为什么必须和 [parseAtom] 分开（单测抓到的 bug）
         *
         * `x_i^2` 的语义是「x 带下标 i、再带上标 2」。若上下标的内容也走
         * [parseAtom]（会继续吃 `^`），那么 `_i` 的参数会把 `^2` 一起吞掉，
         * 解析成「x 下标为 `i²`」—— 上标跑到下标里面去了。
         *
         * 这个写法在模型输出里极其常见（`a_i^2`、`x_n^k`），所以必须修对。
         */
        private fun parseBareAtom(): MathNode? {
            skipSpaces()
            if (pos >= src.length) return null
            return when (val c = src[pos]) {
                '\\' -> parseCommand()
                '{' -> {
                    pos++
                    val inner = MathNode.Row(parseRow(topLevel = false))
                    expect('}')
                    inner
                }
                '}', '^', '_' -> { pos++; null }
                else -> { pos++; plainChar(c) }
            }
        }

        /** 上下标的内容：`{...}` 或紧跟的**单个**原子（不再吃它后面的上下标）。 */
        private fun parseScriptArg(): MathNode {
            skipSpaces()
            if (src.getOrNull(pos) == '{') {
                pos++
                val inner = MathNode.Row(parseRow(topLevel = false))
                expect('}')
                return inner
            }
            return parseBareAtom() ?: MathNode.Row(emptyList())
        }

        private fun parseCommand(): MathNode {
            pos++ // 吃掉反斜杠
            if (pos >= src.length) return MathNode.Sym("\\")

            if (!src[pos].isLetter()) {
                val c = src[pos]
                pos++
                return when (c) {
                    /*
                     * 间距命令 → [MathNode.Space]。
                     *
                     * ⚠️ **不能再是 [MathNode.Sym]**（用户 2026-10-04 第七张图）：
                     * 那样它既是一段空白字形、又参与 atom spacing，**叠两层**，
                     * 于是 `\mu\,\delta` 那一处比周围宽（用户问"为什么只有他们两个之间大"）。
                     *
                     * 宽度按 TeX 的 `\thinmuskip` / `\medmuskip` / `\thickmuskip`：
                     * `\,`=3/18、`\:`=4/18、`\;`=5/18 em。
                     */
                    ',' -> MathNode.Space(3f / 18f)
                    ':' -> MathNode.Space(4f / 18f)
                    ';' -> MathNode.Space(5f / 18f)
                    ' ' -> MathNode.Space(1f / 3f)      // `\ `：一个普通空格宽
                    '!' -> MathNode.Row(emptyList())     // 负间距：什么也不加
                    '\\' -> MathNode.Row(emptyList())    // 换行：这里不处理，交给外层
                    else -> MathNode.Sym(c.toString())
                }
            }

            val name = buildString {
                while (pos < src.length && src[pos].isLetter()) append(src[pos++])
            }

            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" ->
                    MathNode.Frac(parseRequiredGroup(), parseRequiredGroup())

                "sqrt" -> {
                    val index = if (peekNonSpace() == '[') parseBracketGroup() else null
                    MathNode.Sqrt(index, parseRequiredGroup())
                }

                /*
                 * ⚠️ **`\text{}` 与 `\mathbf{}` 必须分开处理**（用户 2026-10-04 的 `\boldsymbol` 缺陷）。
                 *
                 * 两者看着像，语义完全不同：
                 *
                 * | 命令 | 花括号里的内容是 | 取法 |
                 * |---|---|---|
                 * | `\text{...}` / `\mathrm{...}` | **文本**（`\text{N-S方程}`） | [readGroupRawText] 原样取 |
                 * | `\mathbf{...}` / `\boldsymbol{...}` | **数学内容**（`\boldsymbol{\tau}`） | [parseRequiredGroup] 按数学解析 |
                 *
                 * 原来这两类共用 `readGroupRawText()`，于是
                 * `\boldsymbol{\tau}` 里的 `\tau` 被当成**字面文本**，
                 * 原样输出成带反斜杠的 `\tau` ——
                 * 真机截图上就是 `∇·\tau + ρf`（用户报的那个"很奇怪为什么出现 \tau"）。
                 *
                 * 加粗这一步在渲染层由 `bold = true` 表达；
                 * 花括号里的命令/符号必须照常解析。
                 */
                "text", "mathrm", "operatorname", "mbox", "textnormal" ->
                    MathNode.Sym(readGroupRawText(), italic = false, bold = false)

                // ⚠️ 用 parseRequiredGroup（按数学解析），**不是** readGroupRawText
                "mathbf", "boldsymbol", "bm", "textbf" -> boldGroup()

                "mathit", "textit" -> MathNode.Sym(readGroupRawText(), italic = true)

                // 花体/黑板体没有完整的 Unicode 映射，老实按正体显示
                "mathcal", "mathbb", "mathfrak", "mathsf", "mathtt", "mathscr" ->
                    MathNode.Sym(readGroupRawText(), italic = false)

                "overline", "bar" -> MathNode.Overline(parseRequiredGroup())

                "hat" -> accent(parseRequiredGroup(), '\u0302')
                "widehat" -> accent(parseRequiredGroup(), '\u0302')
                "tilde" -> accent(parseRequiredGroup(), '\u0303')
                "widetilde" -> accent(parseRequiredGroup(), '\u0303')
                "vec" -> accent(parseRequiredGroup(), '\u20D7')
                "dot" -> accent(parseRequiredGroup(), '\u0307')
                "ddot" -> accent(parseRequiredGroup(), '\u0308')

                /*
                 * `\left( … \right)` —— **自动定高的括号**。
                 *
                 * ⚠️ 必须排在下面那组之前单独处理：原来它和 `\big` 之类一起走
                 * `parseDelimiterOrNothing()`，把 `\left`/`\right` 当噪音丢掉、
                 * 只吐出一个普通括号字符 —— 于是括号永远一行高（用户 2026-10-04 报的）。
                 *
                 * 现在扫到配对的 `\right`，把中间内容包成一个 [MathNode.Delimited]。
                 */
                "left" -> parseDelimited()

                "right", "middle", "big", "Big", "bigg", "Bigg",
                "bigl", "bigr", "Bigl", "Bigr", "displaystyle", "textstyle",
                "scriptstyle", "limits", "nolimits", "mathstrut",
                -> parseDelimiterOrNothing()

                "quad" -> MathNode.Space(1f)
                "qquad" -> MathNode.Space(2f)
                "space" -> MathNode.Space(1f / 3f)
                "hspace", "kern", "mskip" -> { parseOptionalGroup(); MathNode.Row(emptyList()) }

                // 矩阵等环境：丢掉 begin/end 标记，内容按普通序列流下去
                "begin", "end" -> { parseOptionalGroup(); MathNode.Row(emptyList()) }

                // 上下限排在正上/正下的大型算符
                "sum" -> bigOp(SYMBOLS.getValue("sum"), limitsAbove = true)
                "prod" -> bigOp(SYMBOLS.getValue("prod"), limitsAbove = true)
                "coprod" -> bigOp(SYMBOLS.getValue("coprod"), limitsAbove = true)
                "bigcup" -> bigOp(SYMBOLS.getValue("bigcup"), limitsAbove = true)
                "bigcap" -> bigOp(SYMBOLS.getValue("bigcap"), limitsAbove = true)
                "lim", "max", "min", "sup", "inf", "argmax", "argmin", "det", "gcd" ->
                    bigOp(name, limitsAbove = true)

                // 积分：上下限排在右侧（行内习惯）
                "int" -> bigOp(SYMBOLS.getValue("int"), limitsAbove = false)
                "iint" -> bigOp(SYMBOLS.getValue("iint"), limitsAbove = false)
                "iiint" -> bigOp(SYMBOLS.getValue("iiint"), limitsAbove = false)
                "oint" -> bigOp(SYMBOLS.getValue("oint"), limitsAbove = false)

                // 具名函数：正体
                "sin", "cos", "tan", "cot", "sec", "csc",
                "arcsin", "arccos", "arctan",
                "sinh", "cosh", "tanh", "coth",
                "log", "ln", "lg", "exp", "deg", "dim", "hom", "ker", "mod",
                -> MathNode.Sym(name, italic = false)

                // `\ ` 之外还有 `\alpha` 这类符号
                else -> SYMBOLS[name]?.let { MathNode.Sym(it, italic = isLowerCaseGreek(it)) }
                    // 不认识的命令：显示它的名字，但**不带反斜杠**
                    ?: MathNode.Sym(name, italic = false)
            }
        }

        private fun bigOp(text: String, limitsAbove: Boolean) =
            MathNode.BigOp(text, sup = null, sub = null, limitsAbove = limitsAbove)

        /**
         * `\left( … \right)` —— 解析成 [MathNode.Delimited]，让括号能按内容高度伸缩。
         *
         * ## 做法
         *
         * `\left` 已经消费掉了，这里：
         *
         * 1. 读左定界符字符（`(`、`[`、`\{`、`|`、`.`=不可见）
         * 2. **记录当前位置**，然后一路 `parseAtom()` 直到遇见 `\right`
         * 3. 读右定界符，把中间已经解析好的原子包成 `Delimited`
         *
         * ## ⚠️ 为什么用"先记位置、再重放"而不是一次扫完
         *
         * 括号里可以是任意内容（分式、根号、甚至嵌套的 `\left…\right`）。
         * 直接复用 `parseAtom()` 就能天然处理嵌套 ——
         * 遇到内层的 `\left` 时它会递归调用本函数。
         *
         * 所以这里只需要：**不断 parseAtom，直到看见 `\right`**。
         * 而 `parseAtom` 不会消费 `\right`（它是命令，由 parseCommand 分发），
         * 于是循环条件判断 `pos` 处是不是 `\right` 即可。
         */
        private fun parseDelimited(): MathNode {
            val left = readDelimiterChar()
            val body = ArrayList<MathNode>()
            while (pos < src.length) {
                if (atCommand("right")) break
                // `\middle` 之类的中间定界符：当普通符号画出来，不参与配对
                if (atCommand("middle")) { pos += "\\middle".length; continue }
                val atom = parseAtom() ?: break
                body += atom
            }

            var right: String? = null
            if (atCommand("right")) {
                pos += "\\right".length
                right = readDelimiterChar()
            }

            return MathNode.Delimited(
                left = left,
                right = right,
                body = MathNode.Row(merge(body)),
            )
        }

        /** 判断 [pos] 处是不是 `\name`（后面不能紧跟字母，避免 `\rightx` 误配）。 */
        private fun atCommand(name: String): Boolean {
            if (!src.startsWith("\\$name", pos)) return false
            val after = pos + 1 + name.length
            return after >= src.length || !src[after].isLetter()
        }

        /**
         * 读一个定界符字符。
         *
         * `.` 表示**不可见**定界符（`\left.` / `\right.`）—— 返回 null，
         * 渲染层据此不画任何东西。
         */
        private fun readDelimiterChar(): String? {
            skipSpaces()
            val c = src.getOrNull(pos) ?: return null
            // `\{` `\}` `\|` 这类带转义的定界符
            if (c == '\\') {
                val next = src.getOrNull(pos + 1)
                if (next == '{' || next == '}' || next == '|') {
                    pos += 2
                    return next.toString()
                }
                return null
            }
            if (c == '.') { pos++; return null }
            pos++
            return c.toString()
        }

        /** `\left(` 这类：跳过命令，把后面的定界符本身显示出来。 */
        private fun parseDelimiterOrNothing(): MathNode {
            skipSpaces()
            val c = src.getOrNull(pos) ?: return MathNode.Row(emptyList())
            return when {
                c == '\\' -> parseCommand()
                c == '.' -> { pos++; MathNode.Row(emptyList()) } // `\left.` 不可见定界符
                c == '{' || c == '}' || c == '[' || c == ']' ||
                    c == '(' || c == ')' || c == '|' -> { pos++; MathNode.Sym(c.toString()) }
                else -> MathNode.Row(emptyList())
            }
        }

        private fun parseRequiredGroup(): MathNode {
            skipSpaces()
            if (src.getOrNull(pos) != '{') {
                // 没写花括号：退化成"吃一个原子"
                return parseAtom() ?: MathNode.Row(emptyList())
            }
            pos++
            val inner = MathNode.Row(parseRow(topLevel = false))
            expect('}')
            return inner
        }

        /** `[n]` 形式（`\sqrt[3]{}`）。 */
        private fun parseBracketGroup(): MathNode {
            skipSpaces()
            if (src.getOrNull(pos) != '[') return MathNode.Row(emptyList())
            pos++
            val out = ArrayList<MathNode>()
            while (pos < src.length && src[pos] != ']') {
                val atom = parseAtom() ?: break
                out += atom
            }
            expect(']')
            return MathNode.Row(merge(out))
        }

        private fun parseOptionalGroup(): MathNode {
            skipSpaces()
            if (src.getOrNull(pos) != '{') return MathNode.Row(emptyList())
            return parseRequiredGroup()
        }

        /**
         * `\mathbf{...}` / `\boldsymbol{...}`：把花括号里的内容**按数学规则解析**，
         * 然后整体标成粗体。
         *
         * ⚠️ 关键是**按数学解析**（[parseRequiredGroup]），不是原样取文本 ——
         * 否则 `\boldsymbol{\tau}` 会输出字面量 `\tau`（用户报的那个缺陷）。
         *
         * 花括号里若有多个原子（`\mathbf{u v}`），返回 [MathNode.Row]；
         * 只有一个原子时也包一层 Row —— 渲染层对 Row 的处理是等价的，
         * 而包一层能避免"把单个 Sym 的 italic/bold 标志覆盖掉"这种细节问题。
         */
        private fun boldGroup(): MathNode {
            val inner = parseRequiredGroup()
            return when (inner) {
                // 单个符号：直接改它的 bold 标志（保留 italic，如 `\boldsymbol{\tau}` 的斜体）
                is MathNode.Sym -> inner.copy(bold = true)
                // 多个原子：逐个标粗
                is MathNode.Row -> MathNode.Row(
                    inner.children.map { child ->
                        if (child is MathNode.Sym) child.copy(bold = true) else child
                    },
                )
                else -> inner
            }
        }

        /** `\text{...}` 这类要**原样**取文本，不按数学规则拆。 */
        private fun readGroupRawText(): String {            skipSpaces()
            if (src.getOrNull(pos) != '{') {
                val atom = parseAtom()
                return if (atom is MathNode.Sym) atom.text else ""
            }
            pos++
            val start = pos
            var depth = 1
            while (pos < src.length) {
                when (src[pos]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                pos++
            }
            val text = src.substring(start, pos)
            expect('}')
            return text
        }

        private fun expect(c: Char) {
            skipSpaces()
            if (src.getOrNull(pos) == c) pos++
        }

        private fun peekNonSpace(): Char? {
            skipSpaces()
            return src.getOrNull(pos)
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        /** 把单字符符号 + 组合附加符并成一段，减少布局节点。 */
        private fun accent(body: MathNode, combining: Char): MathNode =
            if (body is MathNode.Sym && body.text.isNotEmpty()) {
                body.copy(text = body.text + combining)
            } else {
                body
            }

        /**
         * 普通字符。字母按变量（斜体），其余按正体。
         *
         * ## ⚠️ ASCII 的"替身字符"要换成真正的数学符号
         *
         * `-`（U+002D）是**连字符 hyphen**：短、位置偏高。
         * 数学里的减号是 **U+2212 MINUS SIGN**：更长、居中在数学轴上，
         * 笔画粗细与位置和 `+`、`=` 一致。
         *
         * 拿数学字体去渲染一个连字符，就会出现用户报的那种违和感 ——
         * 「减号字体仍然不是优化字体，看起来很违和」：
         * 旁边 `+` `=` 都是正常数学符号，只有它又短又高。
         *
         * ## 为什么在**解析阶段**换，而不是渲染阶段
         *
         * `\text{}` / `\mathrm{}` 里的内容走 [readGroupRawText]，
         * **完全不经过这里** —— 所以 `\text{N-S方程}` 里的连字符仍是连字符，
         * 不会被误改成减号。在渲染阶段换就做不到这个区分（那里已经看不出
         * 一个字符是来自数学模式还是文本模式）。
         *
         * 放在解析阶段还有个好处：[MathNode.flatten] 的行内公式也一并生效。
         */
        private fun plainChar(c: Char): MathNode = when {
            c.isLetter() -> MathNode.Sym(c.toString(), italic = true)
            c == '-' -> MathNode.Sym(MINUS, italic = false)
            c.isDigit() -> MathNode.Sym(c.toString(), italic = false)
            else -> MathNode.Sym(c.toString(), italic = false)
        }

        /**
         * 合并**样式相同**的相邻文本节点。
         *
         * 只为减少布局节点、并让 `abc` 这类多字母标识符有正常字距
         *（每个 Text 各自排版，没有跨节点的 kerning）。
         *
         * ⚠️ 但**运算符 / 关系符 / 标点 / 括号不能合并**（见 [isStandaloneAtom]）：
         * 它们的左右间距是排版规则算出来的，一旦被并进相邻节点就再也判断不出来。
         * 比如 `-\nabla` 若并成 `-∇`，那个减号就会被当成普通符号，两侧不留空。
         */
        private fun merge(nodes: List<MathNode>): List<MathNode> {
            if (nodes.size < 2) return nodes
            val out = ArrayList<MathNode>(nodes.size)
            for (node in nodes) {
                val prev = out.lastOrNull()
                val standalone = (prev is MathNode.Sym && isStandaloneAtom(prev.text)) ||
                    (node is MathNode.Sym && isStandaloneAtom(node.text))
                if (prev is MathNode.Sym && node is MathNode.Sym &&
                    prev.italic == node.italic && prev.bold == node.bold && !standalone
                ) {
                    out[out.lastIndex] = prev.copy(text = prev.text + node.text)
                } else {
                    out += node
                }
            }
            return out
        }
    }

    // ---------------------------------------------------------------- 常量

    private const val THIN_SPACE = "\u2009"
    private const val SPACE = " "
    private const val EM_SPACE = "\u2003"

    /**
     * **U+2212 MINUS SIGN** —— 不是 ASCII 的连字符 `-`（U+002D）。
     *
     * 两者外形不同：连字符短且偏高，减号长且居中在数学轴上。
     * 数学字体里的减号才和 `+`、`=`、`×` 是一套。
     */
    private const val MINUS = "\u2212"

    /**
     * 小写希腊字母在数学里是**变量**，要用斜体（TeX 惯例）。
     *
     * `\alpha` 是斜体 `𝛼`，而 `\Gamma`（大写）保持正体 —— 这一条与 TeX 一致。
     * 之前一律按正体渲染，所以 `ρ`、`μ` 看起来是"直立的希腊字母"，
     * 和参考图里的斜体字形明显不同。
     */
    private fun isLowerCaseGreek(text: String): Boolean =
        text.length == 1 && text[0] in '\u03B1'..'\u03C9'

    /**
     * LaTeX 命令 → Unicode。
     *
     * 只收**有确定 Unicode 对应**的。没有对应的一律不进表 ——
     * 猜一个相近的字形比老实显示名字更容易误导。
     */
    private val SYMBOLS: Map<String, String> = buildMap {
        // 小写希腊字母
        put("alpha", "α"); put("beta", "β"); put("gamma", "γ"); put("delta", "δ")
        put("epsilon", "ε"); put("varepsilon", "ε"); put("zeta", "ζ"); put("eta", "η")
        put("theta", "θ"); put("vartheta", "ϑ"); put("iota", "ι"); put("kappa", "κ")
        put("lambda", "λ"); put("mu", "μ"); put("nu", "ν"); put("xi", "ξ")
        put("omicron", "ο"); put("pi", "π"); put("varpi", "ϖ"); put("rho", "ρ")
        put("varrho", "ϱ"); put("sigma", "σ"); put("varsigma", "ς"); put("tau", "τ")
        put("upsilon", "υ"); put("phi", "φ"); put("varphi", "φ"); put("chi", "χ")
        put("psi", "ψ"); put("omega", "ω")
        // 大写希腊字母
        put("Gamma", "Γ"); put("Delta", "Δ"); put("Theta", "Θ"); put("Lambda", "Λ")
        put("Xi", "Ξ"); put("Pi", "Π"); put("Sigma", "Σ"); put("Upsilon", "Υ")
        put("Phi", "Φ"); put("Psi", "Ψ"); put("Omega", "Ω")
        // 大型算符
        put("sum", "∑"); put("prod", "∏"); put("coprod", "∐")
        put("bigcup", "⋃"); put("bigcap", "⋂")
        put("int", "∫"); put("iint", "∬"); put("iiint", "∭"); put("oint", "∮")
        put("infty", "∞"); put("partial", "∂"); put("nabla", "∇")
        // 运算与关系
        put("times", "×"); put("div", "÷"); put("cdot", "·"); put("pm", "±")
        put("mp", "∓"); put("ast", "∗"); put("star", "⋆"); put("circ", "∘")
        put("bullet", "∙"); put("oplus", "⊕"); put("otimes", "⊗")
        put("le", "≤"); put("leq", "≤"); put("ge", "≥"); put("geq", "≥")
        put("ne", "≠"); put("neq", "≠"); put("equiv", "≡"); put("approx", "≈")
        put("sim", "∼"); put("simeq", "≃"); put("cong", "≅"); put("propto", "∝")
        put("ll", "≪"); put("gg", "≫"); put("doteq", "≐")
        // 箭头
        put("to", "→"); put("rightarrow", "→"); put("leftarrow", "←")
        put("gets", "←"); put("Rightarrow", "⇒"); put("Leftarrow", "⇐")
        put("leftrightarrow", "↔"); put("Leftrightarrow", "⇔")
        put("mapsto", "↦"); put("implies", "⟹"); put("iff", "⟺")
        put("uparrow", "↑"); put("downarrow", "↓")
        // 集合与逻辑
        put("in", "∈"); put("notin", "∉"); put("ni", "∋")
        put("subset", "⊂"); put("supset", "⊃")
        put("subseteq", "⊆"); put("supseteq", "⊇")
        put("cup", "∪"); put("cap", "∩"); put("setminus", "∖")
        put("emptyset", "∅"); put("varnothing", "∅")
        put("forall", "∀"); put("exists", "∃"); put("nexists", "∄")
        put("neg", "¬"); put("lnot", "¬"); put("land", "∧"); put("lor", "∨")
        put("therefore", "∴"); put("because", "∵")
        // 省略号
        put("ldots", "…"); put("dots", "…"); put("cdots", "⋯")
        put("vdots", "⋮"); put("ddots", "⋱")
        // 杂项
        put("angle", "∠"); put("perp", "⊥"); put("parallel", "∥")
        put("triangle", "△"); put("square", "□"); put("checkmark", "✓")
        put("hbar", "ℏ"); put("ell", "ℓ"); put("aleph", "ℵ")
        put("Re", "ℜ"); put("Im", "ℑ"); put("prime", "′")
        put("degree", "°"); put("deg", "°")
        put("lceil", "⌈"); put("rceil", "⌉"); put("lfloor", "⌊"); put("rfloor", "⌋")
        put("langle", "⟨"); put("rangle", "⟩")
        put("S", "§"); put("P", "¶"); put("copyright", "©"); put("pounds", "£")
        put("euro", "€"); put("dag", "†"); put("dagger", "†")
    }
}

/**
 * 把公式树**压平成一串 Unicode 文本**。
 *
 * ## 用途：行内的 `$...$`
 *
 * 行内公式混在正文段落里，而正文是一个 `AnnotatedString`（不能塞 composable，
 * 见 `AiMarkdownText` 的注释）。所以行内只能走文本形式，
 * 块级 `$$...$$` 才用 [com.phonlynn.oreplan.v2.ai.math.MathView] 做真正的排版。
 *
 * 因此这里的原则是**尽量用 Unicode 表意**：
 *  · 上下标 → 上标/下标字符（`x^2` → `x²`），映射不到才退回 `^(...)`
 *  · 分数 → `a/b`，分子分母复杂时加括号
 *  · 根号 → `√(x)`
 */
fun MathNode.flatten(): String = when (this) {
    is MathNode.Sym -> text
    is MathNode.Row -> children.joinToString("") { it.flatten() }
    is MathNode.Frac -> wrap(numerator) + "/" + wrap(denominator)
    is MathNode.Sqrt -> (index?.let { it.flatten().toSuperscript() } ?: "") + "√(" + body.flatten() + ")"
    is MathNode.Script -> buildString {
        append(base.flatten())
        sup?.let { append(it.flatten().toSuperscript()) }
        sub?.let { append(it.flatten().toSubscript()) }
    }
    is MathNode.BigOp -> buildString {
        append(text)
        sub?.let { append(it.flatten().toSubscript()) }
        sup?.let { append(it.flatten().toSuperscript()) }
    }
    is MathNode.Overline -> body.flatten() + "\u0304"
    /*
     * `\left( … \right)`：行内只能压平成字符。
     *
     * ⚠️ 这里用**普通的**括号字符（不是可伸缩弧）—— 行内在 `AnnotatedString` 里，
     * 画不了自绘弧线。但把 `\left` 丢掉、只保留括号字符，
     * 至少比露出 `\left` 原文好得多（那正是这一版之前的行为）。
     */
    is MathNode.Delimited ->
        (left ?: "") + body.flatten() + (right ?: "")

    /*
     * 显式间距：压平成一个**普通空格**。
     *
     * ⚠️ 不能再用 U+2009 —— 行内压平的输出会进 `AnnotatedString`，
     * 而那里已经由 `AiMarkdownText` 负责标点间距；再来一个看不见的空白字符
     * 会让"哪里多了空格"变得难查。普通空格在文本层是可解释的。
     */
    is MathNode.Space -> " "
}

private fun wrap(node: MathNode): String {
    val inner = node.flatten()
    return if (node is MathNode.Row && node.children.size > 1) "($inner)" else inner
}

/** 逐字符转上标；有一个字符转不了就整体退回 `^(...)`。 */
private fun String.toSuperscript(): String {
    val out = StringBuilder(length)
    for (c in this) out.append(SUP[c] ?: return "^($this)")
    return out.toString()
}

private fun String.toSubscript(): String {
    val out = StringBuilder(length)
    for (c in this) out.append(SUB[c] ?: return "_($this)")
    return out.toString()
}

private val SUP: Map<Char, Char> = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'n' to 'ⁿ', 'i' to 'ⁱ',
)

private val SUB: Map<Char, Char> = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
    'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
    'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
    'v' to 'ᵥ', 'x' to 'ₓ',
)
