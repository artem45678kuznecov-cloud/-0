package com.nox.offline.ui.glass

import android.os.Build
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.theme.LocalGlassConfig
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

data class BarItem(val label: String, val icon: ImageVector)

/**
 * Плавающая стеклянная панель навигации с «жидкой» линзой.
 *
 * - Короткое нажатие выбирает вкладку, линза плавно переезжает.
 * - Касание панели и ведение пальцем: линза непрерывно идёт за пальцем,
 *   вкладка под ней подсвечивается, реальный экран переключается один
 *   раз — при отпускании.
 * - Каждая вкладка остаётся отдельным доступным элементом (TalkBack,
 *   клавиатура): ведение пальцем — дополнительный способ, а не замена.
 *
 * Жест живёт только внутри панели и не мешает прокрутке экранов.
 */
@Composable
fun GlassBottomBar(
    items: List<BarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LiquidBarState = rememberLiquidBarState(),
) {
    val palette = nox()
    val cfg = LocalGlassConfig.current
    val backdrop = LocalContentBackdrop.current
    val frame = LocalWallpaperFrame.current
    val shape = RoundedCornerShape(34.dp)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val lensLayer = if (cfg.live && cfg.refraction && Build.VERSION.SDK_INT >= 33) rememberGraphicsLayer() else null
    val painter = remember { LiquidLensPainter() }
    var rootOffset by remember { mutableStateOf(Offset.Unspecified) }

    Box(modifier = modifier.fillMaxWidth().height(72.dp).glass(shape, GlassStyles.Bar)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
            val density = LocalDensity.current
            val slotPx = with(density) { (maxWidth / items.size).toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val math = remember(slotPx, items.size) { LiquidBarMath(slotPx, items.size) }

            // Текущие значения для жеста без перезапуска самого жеста.
            val currentMath by rememberUpdatedState(math)
            val currentSelected by rememberUpdatedState(selected)
            val currentOnSelect by rememberUpdatedState(onSelect)
            val currentHeight by rememberUpdatedState(heightPx)
            val currentCfg by rememberUpdatedState(cfg)
            val select: (Int) -> Unit = { i -> if (i != currentSelected) currentOnSelect(i) }

            // Выбор, пришедший извне (уведомление, «Назад», нажатие): линза едет к нему.
            LaunchedEffect(selected, slotPx) {
                if (state.dragging) return@LaunchedEffect
                val target = math.centerOf(selected)
                if (state.lensX.value.isNaN() || cfg.reduceMotion) state.lensX.snapTo(target)
                else state.lensX.animateTo(target, settleSpring(false))
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { rootOffset = it.positionInRoot() }
                    .drawBehind {
                        with(painter) {
                            drawLens(state, math, palette, cfg, backdrop, lensLayer, frame, rootOffset)
                        }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val m = currentMath
                            val reduce = currentCfg.reduceMotion
                            val pointerId = down.id
                            val start = state.lensCenter.takeUnless { it.isNaN() } ?: m.centerOf(currentSelected)
                            val onLens = abs(down.position.x - start) <= m.lensWidth / 2f
                            // Захват за линзу — без скачка: запоминаем смещение пальца от её центра.
                            var grab = start - down.position.x
                            val slop = viewConfiguration.touchSlop
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            if (onLens) scope.launch { state.lift.animateTo(if (reduce) 0.3f else 0.45f, spring(stiffness = 900f)) }

                            var dragging = false
                            var abandoned = false
                            var exited = false
                            var cancelled = false
                            var upX = down.position.x
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == pointerId } ?: continue
                                if (!c.pressed) {
                                    if (isSystemCancel(c)) cancelled = true
                                    upX = c.position.x
                                    if (dragging) c.consume()
                                    break
                                }
                                if (!dragging && !abandoned) {
                                    val dx = c.position.x - down.position.x
                                    val dy = c.position.y - down.position.y
                                    if (abs(dx) > slop && abs(dx) >= abs(dy) * 0.8f) {
                                        dragging = true
                                        state.previewChanges = 0
                                        state.dragStretch = 0f
                                        state.dragCenter = start
                                        state.preview = m.previewFor(start, -1)
                                        state.dragging = true
                                        scope.launch { state.lift.animateTo(if (reduce) 0.45f else 1f, spring(dampingRatio = if (reduce) 1f else 0.62f, stiffness = 520f)) }
                                    } else if (abs(dy) > slop * 2f && abs(dy) > abs(dx)) {
                                        abandoned = true
                                    }
                                }
                                if (!dragging) continue
                                c.consume()
                                if (exited) continue
                                if (m.isVerticalExit(c.position.y, currentHeight)) {
                                    // Палец ушёл далеко вверх или вниз: выбор отменяется предсказуемо.
                                    exited = true
                                    state.preview = -1
                                    settle(scope, state, m.centerOf(currentSelected), reduce)
                                    continue
                                }
                                tracker.addPosition(c.uptimeMillis, c.position)
                                // Начали не на линзе — линза за несколько событий догоняет палец.
                                if (!onLens) grab *= 0.55f
                                val center = m.clampCenter(c.position.x + grab)
                                val v = tracker.calculateVelocity().x
                                state.dragStretch = if (reduce) 0f
                                else LiquidBarMath.smooth(state.dragStretch, LiquidBarMath.stretchFor(v, m.slot))
                                state.dragCenter = center
                                val p = m.previewFor(center, state.preview)
                                if (p != state.preview) {
                                    state.preview = p
                                    state.previewChanges++
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }

                            when {
                                cancelled -> {
                                    state.preview = -1
                                    settle(scope, state, m.centerOf(currentSelected), reduce)
                                }
                                dragging && !exited -> {
                                    val target = state.preview.takeIf { it >= 0 } ?: m.indexAt(state.dragCenter)
                                    state.preview = -1
                                    settle(scope, state, m.centerOf(target), reduce)
                                    select(target)
                                }
                                !dragging && !abandoned -> select(m.indexAt(upX))
                            }
                            if (!state.dragging) scope.launch { state.lift.animateTo(0f, spring(stiffness = 600f)) }
                        }
                    }
            )

            val highlight = if (state.preview >= 0) state.preview else selected
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, item ->
                    val active = i == selected
                    val lit = i == highlight
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // Доступность: вкладка, признак выбора, действие «выбрать».
                            // Касания обрабатывает панель целиком, поэтому здесь нет clickable.
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                this.selected = active
                                onClick(label = item.label) { select(i); true }
                            }
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .onKeyEvent { e ->
                                val confirm = e.key == Key.Enter || e.key == Key.NumPadEnter ||
                                    e.key == Key.DirectionCenter || e.key == Key.Spacebar
                                if (confirm && e.type == KeyEventType.KeyUp) { select(i); true } else false
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val itemCenter = math.centerOf(i)
                        Icon(item.icon, contentDescription = null,
                            tint = if (lit) palette.accentLight else Nox.TextSecondary,
                            modifier = Modifier
                                .size(24.dp)
                                // Иконка под линзой чуть увеличивается — как под толстым стеклом.
                                .graphicsLayer {
                                    val lens = state.lensCenter
                                    val near = if (lens.isNaN()) 0f else LiquidBarMath.proximity(itemCenter, lens, math.slot)
                                    val k = (if (cfg.reduceMotion) 0.06f else 0.16f) * near * (0.35f + 0.65f * state.lift.value.coerceIn(0f, 1f))
                                    scaleX = 1f + k; scaleY = 1f + k
                                })
                        Text(item.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (lit || focused) Nox.TextPrimary else Nox.TextSecondary,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

/**
 * Отмена касания системой (ACTION_CANCEL). Compose передаёт её как
 * синтетическое «отпускание» с тем же временем и той же точкой, что у
 * предыдущего события; настоящее отпускание всегда несёт новое время.
 */
internal fun isSystemCancel(c: PointerInputChange): Boolean =
    !c.pressed && c.previousPressed && c.uptimeMillis == c.previousUptimeMillis && c.position == c.previousPosition

private fun settleSpring(reduce: Boolean) =
    if (reduce) spring<Float>(dampingRatio = 1f, stiffness = 900f)
    else spring(dampingRatio = 0.72f, stiffness = 420f)

/**
 * Линза мягко встаёт в центр вкладки. Сначала мгновенно переносим
 * пружину в текущую точку пальца, затем снимаем флаг ведения — без
 * кадра, где линза прыгнула бы к старому месту.
 */
private fun settle(scope: kotlinx.coroutines.CoroutineScope, state: LiquidBarState, target: Float, reduce: Boolean) {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (state.dragging) {
            state.lensX.snapTo(state.dragCenter)
            state.stretch.snapTo(state.dragStretch)
            state.dragging = false
        }
        launch { state.stretch.animateTo(0f, spring(dampingRatio = if (reduce) 1f else 0.5f, stiffness = 300f)) }
        launch { state.lift.animateTo(0f, spring(stiffness = 600f)) }
        state.lensX.animateTo(target, settleSpring(reduce))
    }
}
