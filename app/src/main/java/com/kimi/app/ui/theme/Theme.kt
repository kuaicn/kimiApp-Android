package com.kimi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.kimi.app.core.ThemeMode

// Kimi 品牌蓝（与 kimi-web 的 --color-accent 对齐）
val KimiBlue = Color(0xFF1783FF)
val KimiBlueDark = Color(0xFF58A6FF)

private val LightColors = lightColorScheme(
    primary = KimiBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E7FF),
    onPrimaryContainer = Color(0xFF0B3D78),
    secondary = Color(0xFF54606E),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurface = Color(0xFF1A1C1E),
    onSurfaceVariant = Color(0xFF5A6068),
    outline = Color(0xFFE0E2E6),
    outlineVariant = Color(0xFFEDEEF0),
    error = Color(0xFFD93025),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8F9),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFEDEEF0),
)

private val DarkColors = darkColorScheme(
    primary = KimiBlueDark,
    onPrimary = Color(0xFF06213F),
    primaryContainer = Color(0xFF123A63),
    onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = Color(0xFF9BA6B2),
    background = Color(0xFF0D1117),
    surface = Color(0xFF0D1117),
    surfaceVariant = Color(0xFF161C24),
    onSurface = Color(0xFFE2E6EB),
    onSurfaceVariant = Color(0xFF9BA6B2),
    outline = Color(0xFF2A323D),
    outlineVariant = Color(0xFF1D242E),
    error = Color(0xFFF28B82),
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceContainerLow = Color(0xFF11161D),
    surfaceContainer = Color(0xFF161C24),
    surfaceContainerHigh = Color(0xFF1D242E),
)

@Composable
fun KimiTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
