package com.reduan.smartinfo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0B1938)
val NavyLight = Color(0xFF142D5F)
val Cyan = Color(0xFF00D4FF)
val Warning = Color(0xFFFFB300)
val Success = Color(0xFF2ECC71)

private val Dark = darkColorScheme(
    primary = Cyan, onPrimary = Navy,
    primaryContainer = NavyLight, onPrimaryContainer = Color.White,
    secondary = Color(0xFF7FE7FF), onSecondary = Navy,
    background = Color(0xFF070F24), onBackground = Color.White,
    surface = Color(0xFF0E1C3D), onSurface = Color.White,
    surfaceVariant = Color(0xFF162A55), onSurfaceVariant = Color(0xFFB8C7E6),
    surfaceContainer = Color(0xFF11224A), surfaceContainerHigh = Color(0xFF162A55),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = NavyLight, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F6FF), onPrimaryContainer = Navy,
    secondary = Color(0xFF0090B0), onSecondary = Color.White,
    background = Color(0xFFF4F7FC), onBackground = Navy,
    surface = Color.White, onSurface = Navy,
    surfaceVariant = Color(0xFFE6ECF7), onSurfaceVariant = Color(0xFF4A5B80),
)

@Composable
fun SmartInfoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
