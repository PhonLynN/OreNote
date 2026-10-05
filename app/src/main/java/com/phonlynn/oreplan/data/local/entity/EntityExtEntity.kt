package com.phonlynn.oreplan.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 扩展抽屉 —— **全库共用的「新字段收纳柜」**。
 *
 * ## 它解决什么问题
 *
 * 用户的硬性标准是「加新功能不用改老表、不用迁移、不动老代码」。
 * 传统做法下，「给卡片加一个天气字段」必须给 `board_cards` 加一列 ——
 * 那就是一次有风险的数据库迁移（本项目有过同版本改表导致设备端崩溃的真实事故）。
 *
 * 本表把这件事从「改结构」降级成「写一行数据」：任何实体的任何新附属字段，
 * 都往这里放。**老表一根手指都不用碰。**
 *
 * ## 为什么是独立一张表，而不是给 19 张老表各加一列 `ext`
 *
 * 两种做法都能满足「可扩展」，但风险差别很大：
 *
 * | | 给每张表加 `ext` 列 | 本表（独立收纳柜） |
 * | --- | --- | --- |
 * | 是否动老表 | 要（19 张全部改结构） | **完全不动** |
 * | 静默清空风险 | **有**。写入路径有「整行覆盖」（如 `replaceCardWithRelations`），
 * 领域模型没带上 `ext` 时保存就会把抽屉清空 —— 与历史事故「拖动排序清空所有卡片标签」同类 | **结构上不可能**：老表的读写路径根本不碰这张表 |
 * | 存储成本 | 每行都带一个 NULL 列 | 只有真放了东西的行才占空间 |
 *
 * 代价是**没有外键**（归属是多态的 `(ownerTable, ownerId)`）。因此删除业务行后
 * 可能留下「孤儿抽屉行」：它不进 UI、不被读到（读取永远按精确的 owner 取），
 * 失败模式是良性的；清理随第一个真正使用抽屉的功能一起接入删除路径。
 */
@Entity(
    tableName = "entity_ext",
    primaryKeys = ["ownerTable", "ownerId"],
    indices = [Index(value = ["ownerTable"])],
)
data class EntityExtEntity(
    /** 归属的表名。取值来自 [com.phonlynn.oreplan.domain.model.ExtOwner]，不手写字符串。 */
    val ownerTable: String,
    /** 归属行的主键。 */
    val ownerId: String,
    /** 扁平 JSON 对象，如 `{"weather.sky":"晴","mood.score":3}`。 */
    val ext: String,
    val updatedAt: Long,
)
