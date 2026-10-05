package com.phonlynn.oreplan.domain.ai.tool

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * 工具集的装配。
 *
 * ## 加一个工具就加一行
 *
 * 每个工具都是 `@Singleton class XxxTool @Inject constructor(…) : AiTool`，
 * 这里 `@Binds @IntoSet` 把它放进 [ToolRegistry] 的集合。
 *
 * 用 Set 多绑定而不是让注册表逐个 `new`：工具依赖的是仓储和 UseCase，
 * 让注册表去认识它们等于把整棵依赖树搬到注册表里，而注册表本该什么都
 * 不认识 —— 那是"工具能随意添加"的前提。
 *
 * ## 为什么不用 `@Inject constructor` + 构造函数注入 Set
 *
 * [ToolRegistry] 的构造参数就是 `Set<AiTool>`，Dagger 会拿本模块收集到的
 * 全部绑定去满足它。所以只要这里加一行，注册表、调度、界面**都不用改**。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiToolModule {

    // ---- 读（免确认）------------------------------------------------

    @Binds
    @IntoSet
    abstract fun bindGetCurrentTime(tool: GetCurrentTimeTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindFindFreeSlots(tool: FindFreeSlotsTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindGetItems(tool: GetItemsTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindGetTimetable(tool: GetTimetableTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindGetItemDetail(tool: GetItemDetailTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindReadBoard(tool: ReadBoardTool): AiTool

    // ---- 写（必须用户确认）------------------------------------------
    //
    // ⚠️ 日程与待办**共用**同一套增删改工具：它们是同一个实体（`Item`），
    // 只差 `ItemKind`。拆成两套会让模型在语义重叠的工具之间选，
    // 而选择质量正是工具集最脆弱的地方。

    @Binds
    @IntoSet
    abstract fun bindCreateItem(tool: CreateItemTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindUpdateItem(tool: UpdateItemTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindDeleteItems(tool: DeleteItemsTool): AiTool

    // ---- 写 · 白板 ---------------------------------------------------
    //
    // 白板是**独立实体**（`BoardCard` 不挂 `Item`），所以日程/待办的工具看不到它，
    // 它也必须有自己的一套。

    @Binds
    @IntoSet
    abstract fun bindCreateBoardCard(tool: CreateBoardCardTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindUpdateBoardCard(tool: UpdateBoardCardTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindDeleteBoardCards(tool: DeleteBoardCardsTool): AiTool

    // ---- 文件（附件）-------------------------------------------------
    //
    // 用户从对话里加进来的文件先以「未分配」落库，AI 只拿到**索引**
    //（不读内容），再按用户指令把它挂到目标对象上 —— 详见 `FileTools.kt`。

    @Binds
    @IntoSet
    abstract fun bindListFiles(tool: ListFilesTool): AiTool

    @Binds
    @IntoSet
    abstract fun bindAttachFile(tool: AttachFileTool): AiTool

    // ---- 交互 --------------------------------------------------------

    @Binds
    @IntoSet
    abstract fun bindAskUser(tool: AskUserTool): AiTool
}
