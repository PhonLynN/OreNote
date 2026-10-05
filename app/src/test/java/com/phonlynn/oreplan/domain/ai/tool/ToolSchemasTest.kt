package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.data.local.dao.AppMetaDao
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.domain.repository.ChecklistRepository
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.NoteBlockRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import com.phonlynn.oreplan.domain.usecase.BuildAgendaUseCase
import com.phonlynn.oreplan.domain.usecase.DeleteItemUseCase
import com.phonlynn.oreplan.domain.usecase.ExpandTimetableUseCase
import com.phonlynn.oreplan.domain.usecase.LoadItemDetailUseCase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 全部工具的定义。
 *
 * ## 为什么必须一次检查**所有**工具
 *
 * 工具定义是**每次请求全都发出去**的。所以任何一个工具的 `parameters` 是坏 schema，
 * 都会让**每一次**请求被服务端拒掉 —— 表现是"AI 完全不能用了"，
 * 而原因藏在 12 个工具里的某一个。这种"一个坏了全坏"的结构必须整批校验。
 *
 * ## 具体校验什么
 *
 * | 项 | 不满足会怎样 |
 * |---|---|
 * | `name` 只含 `[a-zA-Z0-9_-]` 且 ≤64 | OpenAI 的硬约束，违反直接 400 |
 * | 名字唯一 | 模型要求调 `x` 时不知道该给哪个 |
 * | `description` 非空 | 模型没有依据选工具 |
 * | `type == "object"` | 参数就不是 JSON 对象，无法解析 |
 * | `properties` 非空 | 一个不收参数的工具不该这样声明 |
 * | `required ⊆ properties` | **服务端会校验**：required 里出现不存在的字段 → 400 |
 *
 * ## 依赖怎么来的
 *
 * `spec()` 不碰仓储（只返回三个静态字段），所以这里用 `Proxy` 造**动态假件**
 * 就够了 —— 不用手写十几个接口的几百行空实现。`Proxy` 是 JDK 自带的，
 * 不算三方依赖，而且它只出现在测试里。
 *
 * 动态代理的每个方法都返回**该返回类型的零值**（`Flow` 之类的对象类型返回 null），
 * 反正不会被调用；真被调到了会立刻 NPE，说明 `spec()` 偷偷碰了仓储 —— 那也是 bug。
 */
class ToolSchemasTest {

    /** 造一个接口的假实现：所有方法返回返回类型的零值。 */
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> fake(): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Void.TYPE -> null
                else -> null
            }
        } as T

    /** 用假件把 12 个工具都造出来。 */
    private fun allTools(): List<AiTool> {
        val items = fake<ItemRepository>()
        val terms = fake<TermRepository>()
        val courses = fake<CourseRepository>()
        val board = fake<BoardRepository>()

        return listOf(
            // 读
            GetCurrentTimeTool(),
            FindFreeSlotsTool(
                BuildAgendaUseCase(
                    items,
                    terms,
                    courses,
                    fake<RecurrenceExceptionRepository>(),
                    fake<ChecklistRepository>(),
                ),
            ),
            GetItemsTool(
                BuildAgendaUseCase(
                    items,
                    terms,
                    courses,
                    fake<RecurrenceExceptionRepository>(),
                    fake<ChecklistRepository>(),
                ),
                fake<ReminderRepository>(),
            ),
            GetTimetableTool(terms, courses, ExpandTimetableUseCase(terms, courses)),
            GetItemDetailTool(
                LoadItemDetailUseCase(items, fake<ChecklistRepository>(), fake<AttachmentRepository>()),
                fake<NoteBlockRepository>(),
                fake<ReminderRepository>(),
            ),
            ReadBoardTool(board),
            // 写
            CreateItemTool(items, fake<ReminderRepository>()),
            UpdateItemTool(items),
            DeleteItemsTool(
                items,
                DeleteItemUseCase(
                    items,
                    fake<ChecklistRepository>(),
                    fake<ReminderRepository>(),
                    fake<com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository>(),
                ),
            ),
            CreateBoardCardTool(board, AppSettingsStore(fake<AppMetaDao>()), items, fake<ReminderRepository>()),
            UpdateBoardCardTool(board, items, fake<ReminderRepository>()),
            DeleteBoardCardsTool(board),
            // 交互
            AskUserTool(),
        )
    }

    /** OpenAI 对函数名的硬约束。违反它整次请求会被拒，而且报错很难懂。 */
    private val namePattern = Regex("^[a-zA-Z0-9_-]{1,64}$")

    @Test
    fun `工具数量与预期一致`() {
        assertEquals("工具增减时这条要一起改，免得悄悄少了一个", 13, allTools().size)
    }

    @Test
    fun `名字合法且唯一`() {
        val names = allTools().map { it.name }
        names.forEach { name ->
            assertTrue("名字 `$name` 不符合 OpenAI 的约束（只允许字母数字下划线连字符）", namePattern.matches(name))
        }
        assertEquals("有重名工具，模型分不清该调哪个", names.size, names.toSet().size)
    }

    @Test
    fun `描述非空且够用`() {
        allTools().forEach { tool ->
            assertTrue("`${tool.name}` 没有 description，模型没有依据选它", tool.description.isNotBlank())
            assertTrue(
                "`${tool.name}` 的描述太短，模型很难判断什么时候该用",
                tool.description.length >= 10,
            )
            assertTrue("`${tool.name}` 没有 displayName，卡片上会空着", tool.displayName.isNotBlank())
        }
    }

    /**
     * 每个工具的 `parameters` 必须是**服务端能接受的** JSON Schema。
     *
     * `required ⊆ properties` 这条尤其重要：服务端**真的会校验**它，
     * 而 typo 一个字段名（`requried` 之类）在这里看不出任何问题。
     */
    @Test
    fun `参数 schema 合法`() {
        allTools().forEach { tool ->
            val p = tool.parameters
            val where = "`${tool.name}` 的参数定义"

            assertEquals("$where 的 type 必须是 object", "object", p.optString("type"))

            val properties = p.optJSONObject("properties")
            // `properties` 必须**存在**，但可以是空对象 ——
            // 「当前时间」这类工具天然不收参数，逼它加个占位参数才是错的
            assertTrue("$where 缺少 properties（无参数的工具也要有空对象）", properties != null)

            val required = p.optJSONArray("required")
            if (required != null) {
                for (i in 0 until required.length()) {
                    val field = required.optString(i)
                    assertTrue(
                        "$where 的 required 里写了 `$field`，但 properties 里没有它 —— " +
                            "服务端会直接拒掉**每一次**请求",
                        properties!!.has(field),
                    )
                }
            }

            // 整个定义必须能序列化（含非法字符时 JSONObject 会抛）
            assertFalse("$where 序列化失败", JSONObject(p.toString()).length() == 0)
        }
    }

    /** 危险级别必须是三选一里的具体值，不能是默认值蒙混过关。 */
    @Test
    fun `危险级别分布正确`() {
        val byDanger = allTools().groupBy { it.danger }

        assertEquals(
            "读工具应当免确认（当前时间 + 空闲时间 + 条目 + 课表 + 详情 + 白板）",
            6,
            byDanger[ToolDanger.READ].orEmpty().size,
        )
        assertEquals(
            "写工具必须确认（日程/待办 3 + 白板 3）",
            6,
            byDanger[ToolDanger.WRITE].orEmpty().size,
        )
        assertEquals(
            "提问工具要等用户回答",
            1,
            byDanger[ToolDanger.ASK].orEmpty().size,
        )
    }

    /** 注册表要能按名字找到每一个工具，且给模型的定义按名字排序（保证确定性）。 */
    @Test
    fun `注册表能索引全部工具并按名字排序`() {
        val tools = allTools()
        val registry = ToolRegistry(tools.toSet())

        tools.forEach { tool ->
            assertEquals(
                "`${tool.name}` 在注册表里找不到（或找到了别的）",
                tool.name,
                registry.find(tool.name)?.name,
            )
        }

        val specs = registry.specs()
        assertEquals(tools.size, specs.size)
        assertEquals(
            "发给模型的定义必须按名字排序 —— 否则注册顺序一变，模型的行为就无端变了",
            specs.map { it.name }.sorted(),
            specs.map { it.name },
        )
    }

    /**
     * 自检：**至少要有工具声明了 `required`**。
     *
     * 否则上面那条「`required ⊆ properties`」就是空转的 —— 每条断言都跑在
     * 一个不存在的数组上，绿灯亮着但什么都没查。这类"检查本身失效"的问题
     * 只能靠这种反向断言发现。
     */
    @Test
    fun `自检 至少有工具声明了必填参数`() {
        val withRequired = allTools().filter { tool ->
            (tool.parameters.optJSONArray("required")?.length() ?: 0) > 0
        }

        assertTrue(
            "没有任何工具声明 required —— 那 `required ⊆ properties` 那条断言是空转的",
            withRequired.size >= 8,
        )
    }

    /** 每个工具都要能生成一份可序列化的定义 —— 这是真正发出去的东西。 */
    @Test
    fun `每份定义都能序列化且带上了名字`() {
        allTools().forEach { tool ->
            val spec = tool.spec()
            val json = JSONObject()
                .put("name", spec.name)
                .put("description", spec.description)
                .put("parameters", spec.parameters)

            assertEquals(tool.name, json.getString("name"))
            assertTrue(json.getJSONObject("parameters").has("properties"))
        }
    }
}
