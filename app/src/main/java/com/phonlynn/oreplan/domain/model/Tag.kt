package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable

/** 标签。与条目是多对多关系 —— 这张关系表也是以后接入笔记时的接入点。 */
@Immutable
data class Tag(
    val id: String,
    val name: String,
    val colorHex: String? = null,
)
