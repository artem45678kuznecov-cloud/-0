package com.nox.offline.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.theme.Nox

/** Стеклянная карточка NOX: приподнятая поверхность с мягкой лавандовой кромкой. */
@Composable
fun NoxCard(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Nox.CardRadius)
    val border = if (active) Nox.BorderActive.copy(alpha = 0.55f) else Nox.Border.copy(alpha = 0.18f)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    (if (strong) Nox.GlassStrong else Nox.Glass).copy(alpha = 0.98f),
                    (if (strong) Nox.Glass else Nox.GlassDeep).copy(alpha = 0.96f),
                )
            )
        )
        .border(BorderStroke(1.dp, border), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(modifier = m.padding(16.dp), content = content)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Nox.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) Text(trailing, color = Nox.TextMuted, fontSize = 12.sp)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 12, color: Color = Nox.TextMuted, maxLines: Int = Int.MAX_VALUE) {
    Text(text, color = color, fontSize = size.sp, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Кнопка качества: 360 / 480 / 720 / MAX. */
@Composable
fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Nox.ChipRadius)
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(shape)
            .background(if (selected) Nox.Accent.copy(alpha = 0.32f) else Nox.GlassDeep)
            .border(1.dp, if (selected) Nox.BorderActive.copy(alpha = 0.9f) else Nox.Border.copy(alpha = 0.22f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Nox.TextPrimary else Nox.TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
    }
}

/** Главная акцентная кнопка («Скачать»). */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(Nox.FieldRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = Nox.Accent, contentColor = Nox.TextPrimary,
            disabledContainerColor = Nox.Accent.copy(alpha = 0.35f), disabledContentColor = Nox.TextSecondary,
        ),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Небольшая вторичная кнопка (Пауза / Продолжить / Удалить). */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    val color = if (danger) Nox.Danger else Nox.AccentLight
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Полоса прогресса в стиле NOX: тонкая дорожка и лавандовый градиент. */
@Composable
fun NoxProgress(fraction: Float, modifier: Modifier = Modifier, indeterminate: Boolean = false) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier = modifier.fillMaxWidth().height(6.dp).clip(shape).background(Nox.GlassDeep)) {
        val f = if (indeterminate) 0.35f else fraction.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth(f)
                .height(6.dp)
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(Nox.AccentDeep, Nox.Accent, Nox.AccentLight)))
        )
    }
}

@Composable
fun Badge(text: String, color: Color = Nox.AccentLight) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))
