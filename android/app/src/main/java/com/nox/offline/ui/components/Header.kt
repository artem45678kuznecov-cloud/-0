package com.nox.offline.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.LocalGlassConfig
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/** Что показывает капсула статуса справа от «NOX». */
data class StatusInfo(val top: String, val bottom: String, val busy: Boolean)

/**
 * Шапка по эталону: «N O X» с разрядкой, подпись «офлайн-медиатека» и
 * стеклянная капсула статуса. В капсуле — реальное состояние: идёт ли
 * загрузка и сколько, а не декоративный текст.
 */
@Composable
fun NoxHeader(status: StatusInfo, modifier: Modifier = Modifier, onStatusClick: (() -> Unit)? = null) {
    val p = nox()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("NOX", color = Nox.TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 12.sp,
                style = TextStyle(shadow = Shadow(p.accent.copy(alpha = 0.55f), blurRadius = 24f)))
            Text("офлайн-медиатека", color = Nox.TextSecondary, fontSize = 12.sp, letterSpacing = 2.sp, maxLines = 1, softWrap = false)
        }
        GlassSurface(
            modifier = Modifier.padding(start = 10.dp).height(62.dp).widthIn(max = 190.dp),
            shape = RoundedCornerShape(31.dp),
            style = GlassStyles.Card.copy(glow = 0.25f),
            onClick = onStatusClick,
        ) {
            Row(Modifier.padding(horizontal = 14.dp).height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.busy)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(status.top, color = Nox.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status.bottom, color = p.accentLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(busy: Boolean) {
    val p = nox()
    val reduce = LocalGlassConfig.current.reduceMotion
    val pulse = if (busy && !reduce) {
        val t = rememberInfiniteTransition(label = "dot")
        val v by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
        v
    } else 1f
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(p.accentLight.copy(alpha = 0.95f * pulse), p.accent.copy(alpha = 0.8f), p.accentDeep.copy(alpha = 0.3f))))
            .border(1.dp, p.accentLight.copy(alpha = 0.5f), CircleShape)
    )
}

/** Поиск по медиатеке и круглая кнопка фильтра. */
@Composable
fun SearchRow(
    query: String,
    onQuery: (String) -> Unit,
    onFilter: () -> Unit,
    filterActive: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск в медиатеке…",
) {
    val p = nox()
    val focus = LocalFocusManager.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassSurface(Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(29.dp), style = GlassStyles.Field) {
            Row(Modifier.padding(horizontal = 18.dp).height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Nox.TextSecondary, modifier = Modifier.size(26.dp))
                HSpace(12)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, color = Nox.TextMuted, fontSize = 17.sp, maxLines = 1)
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = TextStyle(color = Nox.TextPrimary, fontSize = 17.sp),
                        cursorBrush = SolidColor(p.accentLight),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Rounded.Close, "Очистить", tint = Nox.TextSecondary,
                        modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onQuery("") }.padding(4.dp))
                }
            }
        }
        HSpace(12)
        GlassIconButton(Icons.Rounded.Tune, "Фильтр и сортировка", onFilter, size = 58.dp, iconSize = 26.dp,
            tint = if (filterActive) p.accentLight else Nox.TextPrimary, accent = filterActive)
    }
}
