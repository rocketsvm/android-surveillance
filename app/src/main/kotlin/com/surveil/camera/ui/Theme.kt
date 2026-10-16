package com.surveil.camera.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Rec = Color(0xFFFF4D5E)
    val Motion = Color(0xFFF5A524)
    val Ok = Color(0xFF2FBF71)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C2F55),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF8FA3C8),
    secondaryContainer = Color(0xFF1F2A40),
    onSecondaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF0D1016),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF0D1016),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF1D2331),
    onSurfaceVariant = Color(0xFF9AA3B5),
    surfaceContainerLowest = Color(0xFF0A0D12),
    surfaceContainerLow = Color(0xFF131824),
    surfaceContainer = Color(0xFF161B26),
    surfaceContainerHigh = Color(0xFF1D2331),
    surfaceContainerHighest = Color(0xFF252C3C),
    outline = Color(0xFF39425A),
    outlineVariant = Color(0xFF262D3D),
    error = Color(0xFFEF4458),
    errorContainer = Color(0xFF4A1820),
    onErrorContainer = Color(0xFFFFD9DC),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FE8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E5FF),
    onPrimaryContainer = Color(0xFF0B2A66),
    secondary = Color(0xFF55657F),
    secondaryContainer = Color(0xFFE1E8F7),
    onSecondaryContainer = Color(0xFF1A2B4C),
    background = Color(0xFFF3F5F9),
    onBackground = Color(0xFF141824),
    surface = Color(0xFFF3F5F9),
    onSurface = Color(0xFF141824),
    surfaceVariant = Color(0xFFE6EAF2),
    onSurfaceVariant = Color(0xFF4F586C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF1F7),
    surfaceContainerHighest = Color(0xFFE6EAF2),
    outline = Color(0xFFB9C1D1),
    outlineVariant = Color(0xFFDDE2EC),
    error = Color(0xFFD32F45),
)

@Composable
fun SurveilTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
