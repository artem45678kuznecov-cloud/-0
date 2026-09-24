package com.nox.offline.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nox.offline.ui.theme.LocalGlassConfig
import com.nox.offline.ui.theme.nox

enum class ProgressKind {
    /** Размер известен: реальная доля. */
    DETERMINATE,
    /** Размер неизвестен, байты идут: бегущий блик. */
    INDETERMINATE,
    /** Пауза / ожидание: полоса стоит и не «качает» сама. */
    STILL,
}

/**
 * Полоса прогресса NOX: тонкая дорожка и лавандовый градиент. Бегущий
 * блик есть только у неопределённого состояния и только пока экран виден:
 * Compose сам останавливает анимации в фоне.
 */
@Composable
fun NoxProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    kind: ProgressKind = ProgressKind.DETERMINATE,
    height: Dp = 6.dp,
    dim: Boolean = false,
) {
    val p = nox()
    val reduce = LocalGlassConfig.current.reduceMotion
    val animate = kind == ProgressKind.INDETERMINATE && !reduce
    val phase = if (animate) {
        val t = rememberInfiniteTransition(label = "indeterminate")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        v
    } else 0f
    val f = fraction.coerceIn(0f, 1f)
    Canvas(
        modifier.fillMaxWidth().height(height).semantics {
            progressBarRangeInfo = if (kind == ProgressKind.INDETERMINATE) ProgressBarRangeInfo.Indeterminate
            else ProgressBarRangeInfo(f, 0f..1f)
        }
    ) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(p.bgDeep.copy(alpha = 0.85f), cornerRadius = r)
        drawRoundRect(p.edge.copy(alpha = 0.10f), cornerRadius = r)
        val colors = if (dim) listOf(p.accentDeep.copy(alpha = 0.6f), p.accent.copy(alpha = 0.6f))
        else listOf(p.accentDeep, p.accent, p.accentLight)
        when (kind) {
            ProgressKind.DETERMINATE, ProgressKind.STILL -> {
                if (f > 0f) {
                    val w = (size.width * f).coerceAtLeast(size.height)
                    drawRoundRect(Brush.horizontalGradient(colors, 0f, w), size = Size(w, size.height), cornerRadius = r)
                }
            }
            ProgressKind.INDETERMINATE -> {
                val seg = size.width * 0.32f
                val x = if (animate) (size.width + seg) * phase - seg else size.width * 0.34f
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, p.accent, p.accentLight, Color.Transparent), x, x + seg),
                    topLeft = Offset(x.coerceAtLeast(0f), 0f),
                    size = Size((seg + x.coerceAtMost(0f)).coerceIn(0f, size.width - x.coerceAtLeast(0f)), size.height),
                    cornerRadius = r,
                )
            }
        }
    }
}
