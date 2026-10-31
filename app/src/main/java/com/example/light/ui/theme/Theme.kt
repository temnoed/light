package com.example.light.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFC24B)
val AmberDeep = Color(0xFFFF8A00)
val Cyan = Color(0xFF7FE7FF)
val Ink = Color(0xFF05070A)
val SurfaceInk = Color(0xFF0D131B)
val Muted = Color(0xFF93A0B1)

private val LightColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1200),
    secondary = Cyan,
    background = Ink,
    onBackground = Color(0xFFE9EDF3),
    surface = SurfaceInk,
    onSurface = Color(0xFFE9EDF3),
    surfaceVariant = Color(0xFF141C26),
    onSurfaceVariant = Muted,
    error = Color(0xFFFF6B6B)
)

@Composable
fun LightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(),
        content = content
    )
}
