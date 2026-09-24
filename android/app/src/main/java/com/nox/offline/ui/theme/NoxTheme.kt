package com.nox.offline.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.settings.Appearance
import com.nox.offline.settings.GlassMode

/**
 * Постоянные цвета NOX, не зависящие от темы: текст и семантика.
 * Всё, что меняется вместе с темой, — в [NoxPalette] через [nox].
 */
object Nox {
    val Danger = Color(0xFFFF6B81)
    val Ok = Color(0xFF6EE7B7)
    val Warning = Color(0xFFFFC46B)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA7AFCB)
    val TextMuted = Color(0xFF6E7593)
    val TextFaint = Color(0xFF4A5170)

    val CardRadius = 22.dp
    val ChipRadius = 16.dp
    val FieldRadius = 18.dp
}

/**
 * Действующие параметры стекла. [live] — размывать настоящее содержимое
 * позади поверхности (API 31+); [refraction] — лёгкое преломление (API 33+).
 * В экономичном режиме и на старых устройствах стекло берёт заранее
 * размытую копию фона: то же оформление без покадровой нагрузки.
 */
@Immutable
data class GlassConfig(
    val mode: GlassMode,
    val live: Boolean,
    val refraction: Boolean,
    val softGlow: Boolean,
    val opacity: Float,
    val glow: Float,
    val intensity: Float,
    val reduceMotion: Boolean,
) {
    companion object {
        fun resolve(a: Appearance, sdk: Int, lowRam: Boolean): GlassConfig {
            val capable = sdk >= 31 && !lowRam
            val full = when (a.glassMode) {
                GlassMode.FULL -> sdk >= 31
                GlassMode.ECONOMY -> false
                GlassMode.AUTO -> capable
            }
            return GlassConfig(
                mode = a.glassMode,
                live = full,
                refraction = full && sdk >= 33 && a.effectIntensity > 0.05f,
                softGlow = full,
                opacity = a.glassOpacity,
                glow = if (a.glassMode == GlassMode.ECONOMY) a.glowStrength * 0.5f else a.glowStrength,
                intensity = a.effectIntensity,
                reduceMotion = a.reduceMotion,
            )
        }
    }
}

val LocalNoxPalette = staticCompositionLocalOf { NoxPalettes.classic }
val LocalGlassConfig = staticCompositionLocalOf {
    GlassConfig(GlassMode.ECONOMY, false, false, false, 0.55f, 0.6f, 0.7f, false)
}

@Composable
fun nox(): NoxPalette = LocalNoxPalette.current

@Composable
fun glassConfig(): GlassConfig = LocalGlassConfig.current

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(Nox.ChipRadius),
    medium = RoundedCornerShape(Nox.FieldRadius),
    large = RoundedCornerShape(Nox.CardRadius),
    extraLarge = RoundedCornerShape(28.dp),
)

private val typography = Typography().let { t ->
    Typography(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

fun isLowRamDevice(context: Context): Boolean =
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

@Composable
fun NoxTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lowRam = remember { isLowRamDevice(context) }
    val palette = remember(appearance.preset, appearance.customHue) {
        NoxPalettes.of(appearance.preset, appearance.customHue)
    }
    val glass = remember(appearance, lowRam) { GlassConfig.resolve(appearance, Build.VERSION.SDK_INT, lowRam) }
    // Material 3 — только каркас. Любой системный компонент, которому
    // всё же нужна схема, получает ночную палитру NOX, а не белую.
    val scheme = remember(palette) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Nox.TextPrimary,
            primaryContainer = palette.accentDeep,
            onPrimaryContainer = Nox.TextPrimary,
            secondary = palette.accentLight,
            onSecondary = palette.bgDeep,
            background = palette.bg,
            onBackground = Nox.TextPrimary,
            surface = palette.glassTint,
            onSurface = Nox.TextPrimary,
            surfaceVariant = palette.bgTop,
            onSurfaceVariant = Nox.TextSecondary,
            surfaceContainer = palette.glassTint,
            surfaceContainerHigh = palette.glassTint,
            surfaceContainerHighest = palette.bgTop,
            surfaceContainerLow = palette.bg,
            surfaceContainerLowest = palette.bgDeep,
            outline = palette.edge.copy(alpha = 0.35f),
            outlineVariant = palette.edge.copy(alpha = 0.18f),
            error = Nox.Danger,
            onError = Nox.TextPrimary,
            inverseSurface = palette.bgTop,
            inverseOnSurface = Nox.TextPrimary,
            scrim = Color.Black,
        )
    }
    CompositionLocalProvider(LocalNoxPalette provides palette, LocalGlassConfig provides glass) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
    }
}
