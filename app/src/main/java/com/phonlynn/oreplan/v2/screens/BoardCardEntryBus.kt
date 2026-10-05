package com.phonlynn.oreplan.v2.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 从白板卡片墙进入卡片编辑页时携带的一次性「预填数据」。
 *
 * 为什么需要它：编辑页的草稿本要在 ViewModel `init` 里异步查 Room。
 * 查完之前页面拿不到数据，只能先空着——表现为「编辑页进场动画比新建卡」。
 * 而白板自己**已经有这张卡片的数据**（`state.cards`），完全可以直接带过去，
 * 让编辑页第一帧就是完整内容，和新建（数据同步就绪）体验一致。
 *
 * 带上的是卡片自身的字段（标题/正文/颜色…）；标签、附件、关联、提醒这些
 * 关联数据仍需查库，由 ViewModel 在后台补上（它们不影响首帧观感）。
 *
 * 与 `CalendarEntryBus` 同属「一次性意图」：写入 → 目标页读取并消费。
 */
object BoardCardEntryBus {
    private val _prefill = MutableStateFlow<CardPrefill?>(null)
    val prefill: StateFlow<CardPrefill?> = _prefill

    // 新建卡片时预设的标签：主页已筛选某标签时，新建直接落在它下面。
    private val _newCardTagId = MutableStateFlow<String?>(null)
    val newCardTagId: StateFlow<String?> = _newCardTagId

    fun push(prefill: CardPrefill) {
        _prefill.value = prefill
    }

    fun consume() {
        _prefill.value = null
    }

    fun pushNewCardTag(tagId: String?) {
        _newCardTagId.value = tagId
    }

    fun consumeNewCardTag() {
        _newCardTagId.value = null
    }
}

/** 卡片编辑页的首帧预填数据（对应 [com.phonlynn.oreplan.domain.model.BoardCard] 的字段）。 */
data class CardPrefill(
    val cardId: String,
    val typeKey: String,
    val title: String,
    val body: String,
    val color: String?,
    val pinned: Boolean,
    val secret: Boolean,
    val secretHint: String,
    val widthMode: String?,
    val imageLayout: String?,
    val showDate: Boolean,
    /** 动态置顶规则（预填带上，避免进编辑页闪一下「未启用」）。 */
    val autoPin: com.phonlynn.oreplan.domain.model.AutoPinRule? = null,
)
