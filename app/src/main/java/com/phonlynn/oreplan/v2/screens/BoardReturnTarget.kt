package com.phonlynn.oreplan.v2.screens

/**
 * 「从卡片更多设置返回白板时，应当恢复哪一层」。
 *
 * 用户 2026-09-30 定的规则（原话）：
 * > 卡片更多设置的上一级是也只能是全屏展示页/编辑页，
 * > 如果在更多设置内点保存更改就回到展示页，如果按退出就回到编辑页。
 *
 * ## 为什么需要这个类型
 *
 * 「全屏展示页」与「全屏编辑页」**都不是导航目的地** —— 它们是白板页
 * (`BoardScreenV2`) 内部的浮层，状态活在白板页自己的 ViewModel 里
 *（`focusedCardId` / `fsEditActive`）。
 *
 * 而「更多设置」(`BoardCardScreenV2`) 是一个**独立路由**，压在白板页之上。
 * 所以它退场时只能回到白板页本身；"该露出哪一层"必须由白板页自己决定。
 * 这个枚举就是那件事的**交接契约**（配合 `BoardCardScreenV2` 里的意图信箱）。
 *
 * **不存在 `WALL`（回卡片墙）这个选项**：按用户的规则，更多设置的上一级
 * 只能是展示页或编辑页。新建卡片是唯一例外（它没有"上一级"可回，
 * 落库后直接回白板 —— 那条路走 [BoardCardScreenV2.save] 自己的分支，不入此枚举）。
 */
enum class BoardReturnTarget {
    /** 露出**全屏展示页**（"保存更改"的落点）。 */
    DISPLAY,

    /** 露出**全屏编辑页**（"退出"的落点）。 */
    EDITOR,
}

/**
 * 「更多设置」退场后要恢复哪一层、以及是哪张卡 —— 交给白板页的一次性信箱。
 *
 * ## 为什么需要它
 *
 * 「全屏展示页」与「全屏编辑页」都**不是导航目的地**：它们是白板页
 * (`BoardScreenV2`) 内部的浮层，状态活在白板页自己的 ViewModel 里
 *（`focusedCardId` / `fsEditActive`）。而「更多设置」(`BoardCardScreenV2`)
 * 是一个**独立路由**，压在白板页之上 —— 它退场时只能回到白板页本身，
 * "该露出哪一层"必须由白板页自己决定。
 *
 * ## 为什么不做成 ViewModel 的伴生对象
 *
 * `BoardCardScreenV2` 这个名字同时是 **ViewModel 类**和 **@Composable 函数**。
 * 刚开始把信箱放进 `BoardCardScreenV2ViewModel.companion`，结果
 * `BoardCardScreenV2.consumeReturnRequest()` 一律被解析成那个 **@Composable**，
 * 报「Unresolved reference / @Composable invocations can only happen…」。
 * 提成一个独立顶层对象名，歧义就没了。
 *
 * ⚠️ 全局对象而非 Hilt 单例：与相邻的 `BoardCardEntryBus`（卡片预填）同一形态。
 */
object BoardReturnBus {

    private val pending = java.util.concurrent.atomic.AtomicReference<Request?>(null)

    /** 一次返回请求：要去哪一层 + 是哪张卡（卡片 id 由发起的白板页填）。 */
    data class Request(val target: BoardReturnTarget, val cardId: String)

    fun request(target: BoardReturnTarget, cardId: String) {
        pending.set(Request(target, cardId))
    }

    /** 读走并清空（一次性消费，避免下次返回误触发）。 */
    fun consume(): Request? = pending.getAndSet(null)
}

