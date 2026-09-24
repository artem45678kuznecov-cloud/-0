package com.nox.offline.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.theme.LocalGlassConfig
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class BarItem(val label: String, val icon: ImageVector)

/**
 * Плавающая стеклянная панель навигации. Сама панель — «живое» стекло
 * (размывает прокручиваемое содержимое под собой), активная вкладка —
 * светящаяся капсула, которая перетекает к выбранному пункту.
 */
@Composable
fun GlassBottomBar(
    items: List<BarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = nox()
    val reduce = LocalGlassConfig.current.reduceMotion
    val shape = RoundedCornerShape(34.dp)
    Box(modifier = modifier.fillMaxWidth().height(72.dp).glass(shape, GlassStyles.Bar)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
            val density = LocalDensity.current
            val slot = maxWidth / items.size
            val slotPx = with(density) { slot.toPx() }
            val left = remember { Animatable(selected * slotPx) }
            val right = remember { Animatable((selected + 1) * slotPx) }
            LaunchedEffect(selected, slotPx, reduce) {
                val targetL = selected * slotPx
                val targetR = (selected + 1) * slotPx
                if (reduce) {
                    left.snapTo(targetL); right.snapTo(targetR); return@LaunchedEffect
                }
                // Передний край быстрее заднего: капсула тянется, как капля.
                val movingRight = targetL > left.value
                val fast = spring<Float>(dampingRatio = 0.72f, stiffness = 420f)
                val slow = spring<Float>(dampingRatio = 0.85f, stiffness = 180f)
                coroutineScope {
                    launch { left.animateTo(targetL, if (movingRight) slow else fast) }
                    launch { right.animateTo(targetR, if (movingRight) fast else slow) }
                }
            }
            val capW = with(density) { (right.value - left.value).coerceAtLeast(slotPx * 0.6f).toDp() }
            Box(
                Modifier
                    .offset { IntOffset(left.value.roundToInt(), 0) }
                    .width(capW)
                    .fillMaxHeight()
                    .glass(RoundedCornerShape(28.dp), GlassStyles.Selected.copy(accentFill = 0.34f, glow = 0.8f))
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, item ->
                    val active = i == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(i) }
                            .semantics { this.selected = active; contentDescription = item.label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(item.icon, contentDescription = null,
                            tint = if (active) palette.accentLight else Nox.TextSecondary,
                            modifier = Modifier.size(24.dp))
                        Text(item.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) Nox.TextPrimary else Nox.TextSecondary,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}
