package com.phonlynn.oreplan.v2.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 拓记 V2 主题：单浅色方案，颜色全部来自 [VColors]。 */
@Composable
fun VTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = VColors.accent,
            onPrimary = Color.White,
            primaryContainer = VColors.accentSoft,
            onPrimaryContainer = VColors.accent,
            secondary = VColors.accent2,
            background = VColors.bg,
            onBackground = VColors.ink,
            surface = VColors.surface,
            onSurface = VColors.ink,
            surfaceVariant = VColors.surface2,
            onSurfaceVariant = VColors.ink2,
            outline = VColors.line,
            outlineVariant = VColors.line,
            error = VColors.rose,
            onError = Color.White,
        ),
        content = content,
    )
}
