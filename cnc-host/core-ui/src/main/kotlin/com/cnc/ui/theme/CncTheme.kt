package com.cnc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CncColors.Primary,
    onPrimary = CncColors.TextPrimary,
    secondary = CncColors.Info,
    background = CncColors.Background,
    onBackground = CncColors.TextPrimary,
    surface = CncColors.Surface,
    onSurface = CncColors.TextPrimary,
    surfaceVariant = CncColors.SurfaceVariant,
    onSurfaceVariant = CncColors.TextSecondary,
    error = CncColors.Danger,
    onError = CncColors.TextPrimary,
    outline = CncColors.Border
)

/**
 * 工业 HMI 主题。固定使用深色方案，不跟随系统浅色模式。
 */
@Composable
fun CncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = CncTypography,
        content = content
    )
}
