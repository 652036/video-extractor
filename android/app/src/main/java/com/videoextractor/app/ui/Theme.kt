package com.videoextractor.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0B1120)
val SurfaceDark = Color(0xFF151E32)
val SurfaceVariantDark = Color(0xFF1E293B)
val AccentSky = Color(0xFF38BDF8)
val AccentTeal = Color(0xFF22D3EE)
val TextPrimary = Color(0xFFF1F5F9)
val TextMuted = Color(0xFF94A3B8)
val SuccessGreen = Color(0xFF34D399)
val ErrorCoral = Color(0xFFF87171)
val WarningAmber = Color(0xFFFBBF24)

private val DarkColors = darkColorScheme(
    primary = AccentSky,
    onPrimary = Color(0xFF082F49),
    secondary = AccentTeal,
    onSecondary = Color(0xFF083344),
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextMuted,
    error = ErrorCoral,
    onError = Color(0xFF450A0A),
)

@Composable
fun VideoExtractorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
