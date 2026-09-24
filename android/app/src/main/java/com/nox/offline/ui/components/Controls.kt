package com.nox.offline.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/** Приглушённый текст — второстепенные подписи. */
@Composable
fun Muted(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 13.sp,
    color: Color = Nox.TextMuted,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(text, color = color, fontSize = size, modifier = modifier, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, textAlign = align, fontWeight = weight, lineHeight = (size.value * 1.3f).sp)
}

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))

/** Большой заголовок экрана: «Загрузчик», «Плеер», «Настройки». */
@Composable
fun ScreenTitle(title: String, subtitle: String?, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Nox.TextPrimary, fontSize = 38.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, color = nox().accentLight.copy(alpha = 0.85f), fontSize = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

/** Заголовок секции «Медиатека  ·  Все >». */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Nox.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(enabled = onAction != null, role = Role.Button) { onAction?.invoke() }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                Text(action, color = Nox.TextSecondary, fontSize = 17.sp)
                if (onAction != null) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Nox.TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** Главная капсула действия: «Скачать», «Обновить». */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 60.dp,
    textSize: TextUnit = 20.sp,
) {
    GlassSurface(
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        style = if (enabled) GlassStyles.Primary else GlassStyles.Chip,
        onClick = onClick,
        enabled = enabled,
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp)) {
            if (icon != null) {
                Icon(icon, null, tint = Nox.TextPrimary.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(26.dp))
                HSpace(12)
            }
            Text(text, color = Nox.TextPrimary.copy(alpha = if (enabled) 1f else 0.55f), fontSize = textSize,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Небольшая стеклянная пилюля: «Вставить», «Продолжить», «Офлайн». */
@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    danger: Boolean = false,
    height: Dp = 44.dp,
    textSize: TextUnit = 15.sp,
    tint: Color? = null,
) {
    val p = nox()
    val color = tint ?: when {
        danger -> Nox.Danger
        accent -> p.accentLight
        else -> Nox.TextPrimary
    }
    GlassSurface(
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        style = if (accent) GlassStyles.Selected.copy(glow = 0.3f, accentFill = 0.18f) else GlassStyles.Chip,
        onClick = onClick,
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size((textSize.value + 5).dp))
                HSpace(8)
            }
            Text(text, color = color, fontSize = textSize, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Круглая стеклянная кнопка-иконка: фильтр, настройки, ⏸/▶, ×. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    iconSize: Dp = 24.dp,
    tint: Color = Nox.TextPrimary,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    GlassSurface(
        modifier = modifier.size(size),
        shape = CircleShape,
        style = if (accent) GlassStyles.Selected.copy(glow = 0.35f) else GlassStyles.Chip,
        onClick = onClick,
        enabled = enabled,
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(iconSize))
    }
}

/** Строка настройки с переключателем. */
@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = nox()
    Row(
        modifier.fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Nox.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Muted(subtitle, size = 13.sp, color = Nox.TextSecondary)
        }
        HSpace(12)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = p.accent,
                checkedBorderColor = p.accentLight.copy(alpha = 0.6f),
                uncheckedThumbColor = Nox.TextSecondary,
                uncheckedTrackColor = p.bgDeep,
                uncheckedBorderColor = p.edge.copy(alpha = 0.35f),
            ),
        )
    }
}

/** Регулятор с подписью и значением. */
@Composable
fun SettingSlider(
    title: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    valueLabel: String? = null,
    onChangeFinished: (() -> Unit)? = null,
) {
    val p = nox()
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Nox.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (valueLabel != null) Muted(valueLabel, size = 13.sp, color = Nox.TextSecondary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = p.accentLight,
                activeTrackColor = p.accent,
                inactiveTrackColor = p.bgDeep,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** Пункт списка настроек: иконка, текст, стрелка. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    danger: Boolean = false,
) {
    val p = nox()
    Row(
        modifier.fillMaxWidth()
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (danger) Nox.Danger else p.accentLight, modifier = Modifier.size(24.dp))
            }
            HSpace(12)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) Nox.Danger else Nox.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Muted(subtitle, size = 13.sp, color = Nox.TextSecondary, maxLines = 3)
        }
        if (trailing != null) {
            HSpace(8)
            Muted(trailing, size = 13.sp, color = Nox.TextSecondary, maxLines = 1)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Nox.TextMuted)
    }
}

/** Стеклянная карточка-группа с внутренними отступами. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    GlassSurface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(Nox.CardRadius),
        style = GlassStyles.Card, onClick = onClick, contentPadding = padding) {
        Column { content() }
    }
}

val LabelStyle = TextStyle(fontSize = 12.sp, letterSpacing = 0.4.sp)
