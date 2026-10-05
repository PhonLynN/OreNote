package com.phonlynn.oreplan.v2.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 拓记 V2 设计令牌 — 直接取自 pen.dev 设计文件的 variables。
 * 这里是全应用颜色/圆角/间距的唯一来源，改样式先改这里。
 */
object VColors {
    val bg = Color(0xFFF4F6F4)
    val surface = Color(0xFFFFFFFF)
    val surface2 = Color(0xFFE9EFEA)
    val ink = Color(0xFF101613)
    val ink2 = Color(0xFF5F6B64)
    val ink3 = Color(0xFF6F7A73)
    /**
     * 卡片**正文**文字色。
     *
     * 背景：标题用 ink（几乎纯黑）、正文原用 ink2（#5F6B64）——在浅色卡片上偏淡，
     * 用户 2026-09-19 反馈「首页与聚焦的字体颜色深度还不够深」。
     * 取更靠黑的一档：与标题只留一点点层次，但整体足够清楚。
     */
    val cardBody = Color(0xFF242C28)
    val accent = Color(0xFF1C6B58)
    val accent2 = Color(0xFF2E8C72)
    val accentSoft = Color(0xFFDCEBE4)
    val amber = Color(0xFF9C6516)
    val amberSoft = Color(0xFFF7EBD7)
    val rose = Color(0xFFBC5F63)
    val roseSoft = Color(0xFFF7E3E3)
    val roseDeep = Color(0xFFA5484E)
    val lilac = Color(0xFF6A61BE)
    val lilacSoft = Color(0xFFE8E5F8)
    val line = Color(0xFFE3E8E4)

    /**
     * 卡片内部分割线：近白线（用户 2026-09-18 定）。
     * 白色每个 RGB 值减 15 → `#F0F0F0`。
     * 目前主页/编辑页/聚焦页的卡片内部分割线统一用它。
     */
    val dividerWhite = Color(0xFFF0F0F0)

    /** 白色半透明（hero 卡上的胶囊等）。 */
    val white26 = Color(0x42FFFFFF)
    val white90 = Color(0xE6FFFFFF)

    /** Tab Bar 底部渐隐遮罩。 */
    val scrimTop = Color(0xFFDEE4DF)
    val scrimClear = Color(0x00DEE4DF)

    /** 弹窗遮罩。 */
    val dialogScrim = Color(0x59101613)
}

/** 间距。页面水平内边距 20，卡片间距 16。 */
object VSpace {
    val pageH: Dp = 20.dp
    val cardGap: Dp = 16.dp
    val rowH: Dp = 56.dp
    val sectionGap: Dp = 20.dp
}

/** 圆角。 */
object VRadius {
    val card: Dp = 16.dp
    val cardBig: Dp = 18.dp
    val hero: Dp = 20.dp
    val button: Dp = 15.dp
    val chip: Dp = 9.dp
    val filterChip: Dp = 11.dp
    val rowBadge: Dp = 10.dp
    val field: Dp = 14.dp
    val fab: Dp = 28.dp
    val dialog: Dp = 24.dp
}
