package com.phonlynn.oreplan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用级键值表。
 *
 * 名字叫「meta」，但它早就不只存元信息了。现在这里放着三类东西：
 *
 * | 前缀 | 内容 |
 * |---|---|
 * | `ai.*` | AI 设置（**含 API Key 明文**）与**全部对话** |
 * | `sync.*` | 云同步配置，**含 R2 凭据与 Keystore 包裹的主密钥** |
 * | 其它 | 外观、提醒开关等设置 |
 *
 * ## ⚠️ 这张表既不进本地备份、也不进云同步
 *
 * 是刻意的：里面混着**不能出设备**的东西（`sync.master_key` 绑定本机 Keystore）。
 * 代价是 **AI 配置与全部对话换机就丢** —— 要修的话得按 key 前缀**白名单**挑，
 * 不能整表带走。见 `docs/AI-待开发清单.md`。
 */
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "meta_key")
    val key: String,
    @ColumnInfo(name = "meta_value")
    val value: String,
)
