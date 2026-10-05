package com.phonlynn.oreplan.core.id

import java.util.UUID

/**
 * 统一 ID 生成。
 *
 * 全部实体都用 UUID 字符串主键，**不用自增整数**。这条是为了以后接入笔记功能：
 * 跨实体引用永远是 `(type, id)` 一对，笔记接入时不必回头改主键策略，
 * 也不会出现「日程 3 号」和「笔记 3 号」撞号的问题。
 */
object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
