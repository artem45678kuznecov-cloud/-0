package com.nox.offline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палитра NOX — та же, что в nox_ui.py: почти чёрно-синий фон, стекло с
 * лавандовой кромкой, фиолетовые акценты. Material 3 здесь только каркас;
 * цвета и формы свои, тема всегда тёмная.
 */
object Nox {
    val Bg = Color(0xFF040611)
    val BgTop = Color(0xFF070A18)
    val BgDeep = Color(0xFF02040B)
    val Glow = Color(0xFF141A4A)
    val Glass = Color(0xFF0B0F20)
    val GlassStrong = Color(0xFF0E1224)
    val GlassDeep = Color(0xFF04060E)
    val Border = Color(0xFF7878F5)
    val BorderActive = Color(0xFF8D8BFF)
    val Accent = Color(0xFF7B7BF5)
    val AccentLight = Color(0xFFB3B0FF)
    val AccentDeep = Color(0xFF4F49D8)
    val AccentSoft = Color(0xFF8D8BFF)
    val Danger = Color(0xFFFF6B81)
    val Ok = Color(0xFF6EE7B7)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9AA2BD)
    val TextMuted = Color(0xFF646B87)
    val TextFaint = Color(0xFF474E68)

    val CardRadius = 18.dp
    val ChipRadius = 12.dp
    val FieldRadius = 14.dp
}

private val scheme = darkColorScheme(
    primary = Nox.Accent,
    onPrimary = Nox.TextPrimary,
    primaryContainer = Nox.AccentDeep,
    onPrimaryContainer = Nox.TextPrimary,
    secondary = Nox.AccentLight,
    onSecondary = Nox.BgDeep,
    background = Nox.Bg,
    onBackground = Nox.TextPrimary,
    surface = Nox.Glass,
    onSurface = Nox.TextPrimary,
    surfaceVariant = Nox.GlassStrong,
    onSurfaceVariant = Nox.TextSecondary,
    outline = Nox.Border.copy(alpha = 0.35f),
    error = Nox.Danger,
    onError = Nox.TextPrimary,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(Nox.ChipRadius),
    medium = RoundedCornerShape(Nox.FieldRadius),
    large = RoundedCornerShape(Nox.CardRadius),
    extraLarge = RoundedCornerShape(24.dp),
)

private val typography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 12.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun NoxTheme(content: @Composable () -> Unit) {
    // Светлой темы у NOX нет: фон всегда ночной, как в оригинале.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
}
