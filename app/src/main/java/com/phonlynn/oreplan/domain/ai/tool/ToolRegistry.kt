package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.ai.protocol.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全部工具的注册表。
 *
 * ## 它是"工具能随意添加"的落点
 *
 * 用户要求「工具部分应该可以随意添加」。做法是让**别处只认 [AiTool] 接口**：
 * 调度、协议、界面都不认识任何具体工具。加一个工具 = 加一个实现类 +
 * 在 [AiToolModule] 里加一行，**不需要改这个文件**。
 *
 * ## 为什么按名字查而不是按顺序
 *
 * 模型返回的是工具**名字**。名字是它唯一的定位方式，
 * 所以这里也只能按名字查 —— 加一层索引和"按顺序取"都无处可用。
 */
@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards AiTool>,
) {

    /**
     * 按**名字**排序，不是按注入顺序。
     *
     * 顺序会影响 prompt 里工具定义的排列，进而轻微影响模型的选择。
     * 注入顺序（Hilt 的 Set 是 `LinkedHashSet`，顺序取决于模块里的声明顺序）
     * 会随着"有人在中间插了一个工具"而整体变化 —— 那会让模型的行为
     * 在毫无道理的地方发生变化。排序后至少是**确定**的。
     */
    private val ordered: List<AiTool> = tools.sortedBy { it.name }

    private val byName: Map<String, AiTool> = ordered.associateBy { it.name }

    /** 发给模型的全部工具定义。 */
    fun specs(): List<ToolSpec> = ordered.map { it.spec() }

    /** 模型要求调用的工具。名字对不上时返回 null（上层要把它当成错误回给模型）。 */
    fun find(name: String): AiTool? = byName[name]

    /** 只读工具的名字，用于提示词里说明"哪些操作不需要确认"之类的场景。 */
    fun namesOf(danger: ToolDanger): List<String> =
        ordered.filter { it.danger == danger }.map { it.name }

    /** 名字 → 给用户看的名字（工具卡标题）。 */
    fun displayName(name: String): String = byName[name]?.displayName ?: name
}
