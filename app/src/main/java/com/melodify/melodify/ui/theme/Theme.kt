package com.melodify.melodify.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple80 = Color(0xFFB69CFF)
private val PurpleGlow = Color(0xFF8C66FF)
private val CyanGlow = Color(0xFF66D9E8)
private val PinkGlow = Color(0xFFFF6FB5)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    onPrimary = Color(0xFF1A1033),
    primaryContainer = Color(0xFF3D2C6E),
    onPrimaryContainer = Purple80,
    secondary = CyanGlow,
    onSecondary = Color(0xFF00363D),
    tertiary = PinkGlow,
    background = Color(0xFF0E0A1A),
    onBackground = Color(0xFFEDE7FF),
    surface = Color(0xFF1A1530),
    onSurface = Color(0xFFEDE7FF),
    surfaceVariant = Color(0xFF2A2444),
    onSurfaceVariant = Color(0xFFCDC6E3),
    outline = Color(0xFF857FA0),
    outlineVariant = Color(0xFF40395C),
    scrim = Color(0x99000000),
    inverseSurface = Color(0xFFEDE7FF),
    inverseOnSurface = Color(0xFF1A1530)
)

private val LightColorScheme = lightColorScheme(
    primary = PurpleGlow,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF006874),
    onSecondary = Color.White,
    tertiary = Color(0xFF7A4770),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0F0),
    onSurfaceVariant = Color(0xFF4A4458),
    outline = Color(0xFF7B748C),
    outlineVariant = Color(0xFFCBC4DB)
)

@Composable
fun MelodifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
