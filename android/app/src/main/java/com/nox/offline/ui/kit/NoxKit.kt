package com.nox.offline.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.R
import com.nox.offline.ui.glass.GlassStyle
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.glass.Lavender
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/**
 * Дизайн-система NOX 0.4.0 по пяти утверждённым макетам.
 *
 * Меры сняты с макетов (853 px по ширине ≈ 411 dp): поля секций 12 dp,
 * радиус секции 16 dp, плитки 10 dp, круглые кнопки 40 dp, панель 62 dp.
 * Всё — настоящие Compose-компоненты поверх стекла NOX, никакой картинки
 * экрана под ними нет.
 */
object Kit {
    val ScreenPad = 12.dp
    val SectionRadius = 16.dp
    val TileRadius = 10.dp
    val SectionGap = 8.dp
    val TitleSize = 23.sp
    val SectionTitle = 13.5.sp
}

/** Вторичный текст макетов — холодная лаванда. */
val LavenderText = Lavender

// ---------------------------------------------------------------------
//  Шапка
// ---------------------------------------------------------------------

/**
 * Логотип NOX (буквы из макета) и подпись под ним — настоящим текстом.
 * Справа — круглые кнопки поиска и настроек; [note] — тонкая подпись
 * справа, как на макете загрузчика.
 */
@Composable
fun BrandHeader(
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    searchLabel: String = "Поиск",
    note: String? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = 18.dp, end = Kit.ScreenPad, top = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Image(painterResource(R.drawable.nox_logo_amber), contentDescription = "NOX",
                modifier = Modifier.width(128.dp).height(42.dp))
            Text("офлайн-медиатека", color = Color(0xFFE4E7FF).copy(alpha = 0.92f), fontSize = 10.5.sp, letterSpacing = 2.3.sp,
                lineHeight = 13.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            Text("Твои видео. Всегда с тобой.", color = Color(0xFFE4E7FF).copy(alpha = 0.92f), fontSize = 10.5.sp, letterSpacing = 1.3.sp,
                lineHeight = 14.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onSearch != null) RoundButton(Icons.Rounded.Search, searchLabel, onSearch, size = 38.dp)
                if (onSettings != null) RoundButton(Icons.Rounded.Settings, "Настройки", onSettings, size = 38.dp)
            }
            if (note != null) {
                Text(note, color = Color(0xFFD8DCF5).copy(alpha = 0.85f), fontSize = 10.5.sp, lineHeight = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.padding(top = 18.dp).widthIn(max = 150.dp))
            }
        }
    }
}

/** Заголовок экрана: крупное название, лавандовая подпись, «назад» и действие справа. */
@Composable
fun ScreenHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = if (onBack != null) Kit.ScreenPad else 18.dp, end = Kit.ScreenPad),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack, size = 44.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Nox.TextPrimary, fontSize = Kit.TitleSize, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 27.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = LavenderText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            }
        }
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
    }
}

// ---------------------------------------------------------------------
//  Секции и плитки
// ---------------------------------------------------------------------

/**
 * Секция: тёмная полупрозрачная подложка, янтарная светящаяся рамка,
 * заголовок слева и «Все ›» / счётчик справа.
 */
@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier.fillMaxWidth().padding(horizontal = Kit.ScreenPad), shape = RoundedCornerShape(Kit.SectionRadius),
        style = GlassStyles.Section) {
        Column {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                        .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(icon, null, tint = Color(0xFFDCE0FF), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Nox.TextPrimary, fontSize = Kit.SectionTitle, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, color = LavenderText, fontSize = 11.5.sp, maxLines = 2)
                    }
                    if (trailing != null || onTrailing != null) {
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .then(if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier)
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (trailing != null) Text(trailing, color = LavenderText, fontSize = 11.5.sp)
                            if (onTrailing != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = LavenderText,
                                modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** Внутренняя плитка секции: тонкая лавандовая рамка. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    radius: Dp = Kit.TileRadius,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    GlassSurface(modifier, shape = RoundedCornerShape(radius), style = if (selected) GlassStyles.Chosen else GlassStyles.Tile,
        onClick = onClick, onLongClick = onLongClick, contentPadding = contentPadding, content = content)
}

// ---------------------------------------------------------------------
//  Кнопки
// ---------------------------------------------------------------------

@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    accent: Boolean = false,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val p = nox()
    GlassSurface(modifier.size(size).semantics { contentDescription = label; role = Role.Button }, shape = CircleShape,
        style = if (accent) GlassStyles.Chosen else GlassStyles.Round, onClick = onClick, enabled = enabled,
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint ?: if (accent) p.accentLight else Color(0xFFEDEFFF).copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(size * 0.5f))
    }
}

/** Большая янтарная кнопка («Найти видео», «Скачать | ≈ 2.8 ГБ»). */
@Composable
fun AmberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    height: Dp = 54.dp,
    textSize: TextUnit = 18.sp,
) {
    val p = nox()
    GlassSurface(modifier.fillMaxWidth().height(height).semantics { role = Role.Button }, shape = RoundedCornerShape(height / 2.6f),
        style = if (enabled) GlassStyles.Amber else GlassStyles.Tile, onClick = onClick, enabled = enabled,
        contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size((textSize.value * 1.45f).dp))
                Spacer(Modifier.width((textSize.value * 0.9f).dp))
            }
            Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.45f), fontSize = textSize, fontWeight = FontWeight.SemiBold)
            if (trailing != null) {
                Spacer(Modifier.width((textSize.value * 1.2f).dp))
                Box(Modifier.width(1.dp).height((textSize.value * 1.45f).dp).background(p.accentLight.copy(alpha = 0.45f)))
                Spacer(Modifier.width((textSize.value * 1.2f).dp))
                Text(trailing, color = Color.White.copy(alpha = 0.9f), fontSize = textSize * 0.84f)
            }
        }
    }
}

/** Вторичная широкая кнопка в плитке («Разобрать ссылки ›», «Проверить ещё раз»). */
@Composable
fun TileButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    chevron: Boolean = false,
    height: Dp = 46.dp,
    enabled: Boolean = true,
    textSize: TextUnit = 13.5.sp,
) {
    Tile(modifier.height(height), onClick = if (enabled) onClick else null, radius = 14.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, tint = Color(0xFFE6E9FF), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, color = Nox.TextPrimary.copy(alpha = if (enabled) 1f else 0.45f), fontSize = textSize, maxLines = 1,
                modifier = if (chevron) Modifier.weight(1f, fill = false) else Modifier)
            if (chevron) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFE6E9FF))
            }
        }
    }
}

/** Круглая кнопка действия в карточке загрузки (пауза / играть / ✓). */
@Composable
fun ActionCircle(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, amber: Boolean = true,
                 size: Dp = 40.dp) {
    val p = nox()
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (amber) p.accentDeep.copy(alpha = 0.35f) else Color(0xFF151A33).copy(alpha = 0.85f))
            .border(BorderStroke(1.4.dp, if (amber) p.accent else Lavender.copy(alpha = 0.35f)), CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (amber) p.accent else Color(0xFFE8EBFF), modifier = Modifier.size(size * 0.5f))
    }
}

// ---------------------------------------------------------------------
//  Чипы, переключатели, прогресс
// ---------------------------------------------------------------------

/** Метка-чип с лавандовой рамкой («Приключения»); [amber] — выделенная («1080p», «Скачано»). */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    amber: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    textSize: TextUnit = 12.5.sp,
    height: Dp = 28.dp,
    sidePadding: Dp = 11.dp,
) {
    val p = nox()
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2))
            .background(if (amber) p.accentDeep.copy(alpha = 0.28f) else Color(0xFF12162C).copy(alpha = 0.72f))
            .border(1.dp, if (amber) p.accent.copy(alpha = 0.9f) else Lavender.copy(alpha = 0.38f), RoundedCornerShape(height / 2))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(horizontal = sidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (amber) p.accentLight else Color(0xFFDDE1FF), modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (amber) p.accentLight else Color(0xFFDDE1FF), fontSize = textSize, maxLines = 1,
            lineHeight = textSize, style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

/** Выбор из нескольких («30 мин / 1 час / После серии», «1 / 2 / 3»). */
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    textSize: TextUnit = 13.5.sp,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Tile(Modifier.weight(1f).height(height).semantics { stateDescription = if (on) "выбрано" else "" },
                onClick = { onSelect(value) }, selected = on, radius = height / 2) {
                Text(label, color = if (on) nox().accentLight else Nox.TextPrimary, fontSize = textSize, maxLines = 1,
                    modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Янтарный переключатель макета. Доступен как Switch. */
@Composable
fun AmberSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String = "") {
    val p = nox()
    val t by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    Box(
        modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
            .background(if (checked) Brush.horizontalGradient(listOf(p.accentDeep, p.accent))
            else SolidColor(Color(0xFF1A1F38)))
            .border(1.dp, if (checked) p.accentLight.copy(alpha = 0.9f) else Lavender.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { contentDescription = label; stateDescription = if (checked) "включено" else "выключено" },
    ) {
        Box(Modifier.padding(start = (3 + 20 * t).dp, top = 3.dp).size(20.dp).clip(CircleShape).background(Color(0xFFF7F4EF)))
    }
}

/** Полоса прогресса: янтарная — идёт передача; лавандовая — ожидание/пауза. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, lavender: Boolean = false, height: Dp = 6.dp) {
    val p = nox()
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(height).drawBehind {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Color(0xFF232944), cornerRadius = r)
        if (f > 0f) {
            val w = size.width * f
            drawRoundRect(
                if (lavender) Brush.horizontalGradient(listOf(Color(0xFF6F7BFF), Color(0xFFA3ACFF)), endX = w)
                else Brush.horizontalGradient(listOf(p.accentDeep, p.accent, p.accentLight), endX = w),
                size = Size(w, size.height), cornerRadius = r)
        }
    })
}

// ---------------------------------------------------------------------
//  Строки настроек и поля
// ---------------------------------------------------------------------

/** Строка внутри секции настроек: значок, название, лавандовое пояснение, справа — управление. */
@Composable
fun SettingLine(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Tile(modifier.fillMaxWidth().heightIn(min = 44.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = Color(0xFFBFC6FF), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Nox.TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = LavenderText, fontSize = 11.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, lineHeight = 13.5.sp)
            }
            if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
            if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFD5D9FF))
        }
    }
}

/** Поле ввода макета: тёмное, со скруглением и лавандовой рамкой. */
@Composable
fun KitField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    minHeight: Dp = 48.dp,
    imeAction: ImeAction = ImeAction.Done,
    onIme: (() -> Unit)? = null,
    label: String = placeholder,
    textSize: TextUnit = 14.5.sp,
) {
    val p = nox()
    val radius = if (singleLine) minHeight / 2.8f else 12.dp
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(radius))
            .background(Color(0xFF0B0F20).copy(alpha = 0.78f))
            .border(1.dp, Lavender.copy(alpha = 0.34f), RoundedCornerShape(radius))
            .padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 8.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) { Icon(leading, null, tint = Color(0xFFBFC6FF), modifier = Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)) }
        val line = textSize * 1.38f
        Box(Modifier.weight(1f).padding(vertical = if (singleLine) 6.dp else 0.dp)) {
            if (value.isEmpty()) Text(placeholder, color = Color(0xFF8A90B8), fontSize = textSize, lineHeight = line)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = singleLine,
                textStyle = TextStyle(color = Nox.TextPrimary, fontSize = textSize, lineHeight = line),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = { onIme?.invoke() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

/** Пустое состояние в стиле макетов: значок, текст и действие. */
@Composable
fun EmptyBlock(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(nox().accentDeep.copy(alpha = 0.25f))
            .border(1.dp, nox().accent.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = nox().accentLight, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = Nox.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(text, color = LavenderText, fontSize = 13.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 18.sp)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            AmberButton(action, onAction, Modifier.widthIn(max = 280.dp), height = 46.dp, textSize = 15.sp)
        }
    }
}

/** Небольшой отступ между секциями. */
@Composable
fun SectionGap() = Spacer(Modifier.height(Kit.SectionGap))

/** Подсказка-ошибка внутри секции. */
@Composable
fun InlineNote(text: String, modifier: Modifier = Modifier, danger: Boolean = false) {
    Text(text, color = if (danger) Nox.Danger else LavenderText, fontSize = 11.5.sp, lineHeight = 15.sp,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}
