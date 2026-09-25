package com.nox.offline.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nox.offline.settings.GlassMode
import com.nox.offline.ui.theme.GlassConfig
import com.nox.offline.ui.theme.LocalGlassConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Нижняя панель: нажатия, ведение пальцем, отмена, границы, второй палец,
 * перерисовки во время жеста, внешняя смена вкладки, режимы стекла,
 * размеры экрана, доступность. Жесты — настоящие события касания.
 */
@RunWith(AndroidJUnit4::class)
class GlassBottomBarGestureTest {
    @get:Rule val rule = createComposeRule()

    private val items = listOf(
        BarItem("Главная", Icons.Rounded.Home),
        BarItem("Загрузки", Icons.Rounded.Download),
        BarItem("Плеер", Icons.Rounded.PlayCircle),
        BarItem("Настройки", Icons.Rounded.Settings),
    )
    private val economy = GlassConfig(GlassMode.ECONOMY, false, false, false, 0.55f, 0.6f, 0.7f, false)

    private val selected = mutableIntStateOf(0)
    private val calls = mutableListOf<Int>()
    private val state = LiquidBarState()
    private val progress = mutableIntStateOf(0)
    private var barWidthPx = 0f
    private var padPx = 0f
    private var slotPx = 0f

    private fun setBar(
        width: Dp = 411.dp,
        fontScale: Float = 1f,
        cfg: GlassConfig = economy,
        withBackdrop: Boolean = false,
        list: LazyListState? = null,
        listFactory: Boolean = false,
    ) {
        rule.setContent {
            val d = LocalDensity.current
            padPx = with(d) { 6.dp.toPx() }
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale), LocalGlassConfig provides cfg) {
                val layer: GraphicsLayer? = if (withBackdrop) rememberGraphicsLayer() else null
                Box(Modifier.width(width)) {
                    Column(Modifier.backdropSource(layer)) {
                        if (listFactory) {
                            val st = list ?: rememberLazyListState()
                            LazyColumn(Modifier.fillMaxWidth().height(300.dp).testTag("list"), state = st) {
                                items(60) { Text("Строка $it", Modifier.fillMaxWidth().height(40.dp)) }
                            }
                        } else {
                            // Контрастная подложка — чтобы линзе было что показывать.
                            Box(Modifier.fillMaxWidth().height(40.dp).background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Blue))))
                        }
                    }
                    CompositionLocalProvider(LocalContentBackdrop provides layer) {
                        // Прогресс «загрузок» заставляет панель перерисовываться: новый список каждый раз.
                        val tick = progress.intValue
                        val fresh = items.map { it.copy() }.also { check(tick >= 0) }
                        GlassBottomBar(fresh, selected.intValue, { calls += it; selected.intValue = it },
                            Modifier.testTag("bar").measured(), state)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Фактический размер панели: экран стенда может быть уже запрошенной ширины. */
    private fun Modifier.measured() = onSizeChanged {
        barWidthPx = it.width.toFloat()
        slotPx = (barWidthPx - 2 * padPx) / items.size
    }

    // Координаты внутри панели (с её внутренним отступом) и внутри области вкладок.
    private fun x(slots: Float) = padPx + slots * slotPx
    private fun centerX(i: Int) = x(i + 0.5f)
    private fun lensInner() = state.lensCenter
    private val bar get() = rule.onNodeWithTag("bar")

    private fun TouchInjectionScope.slowMove(from: Float, to: Float, steps: Int = 24) {
        for (k in 1..steps) {
            moveTo(Offset(from + (to - from) * k / steps, centerY))
            advanceEventTime(16)
        }
    }

    @Test fun tapSelectsEachTabOnce() {
        setBar()
        for (i in listOf(1, 2, 3, 0)) {
            bar.performTouchInput { click(Offset(centerX(i), centerY)) }
            rule.waitForIdle()
            assertEquals(i, selected.intValue)
        }
        assertEquals(listOf(1, 2, 3, 0), calls)
        assertEquals(0.5f * slotPx, lensInner(), 1.5f)
    }

    @Test fun tapOnSelectedTabDoesNothing() {
        setBar()
        selected.intValue = 2
        rule.waitForIdle()
        bar.performTouchInput { click(Offset(centerX(2), centerY)) }
        rule.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test fun slowDragThroughAllTabsSelectsOnceOnRelease() {
        setBar()
        val seen = mutableSetOf<Int>()
        bar.performTouchInput { down(Offset(centerX(0), centerY)) }
        for (k in 1..30) {
            val tx = centerX(0) + (centerX(3) - centerX(0)) * k / 30f
            bar.performTouchInput { moveTo(Offset(tx, centerY)); advanceEventTime(16) }
            rule.waitForIdle()
            // Линза под пальцем, без отставания; реальные экраны не переключаются.
            assertEquals(tx - padPx, lensInner(), 1.5f)
            assertTrue("onSelect during drag: $calls", calls.isEmpty())
            if (state.preview >= 0) seen += state.preview
        }
        assertEquals(setOf(0, 1, 2, 3), seen)
        bar.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(3), calls)
        assertEquals(-1, state.preview)
        assertEquals(3.5f * slotPx, lensInner(), 1.5f)
    }

    @Test fun reverseMidwayAndReleaseBetweenCenters() {
        setBar()
        bar.performTouchInput {
            down(Offset(centerX(0), centerY))
            slowMove(centerX(0), x(2.7f))
            slowMove(x(2.7f), x(1.62f))      // разворот, отпускание между центрами 1 и 2
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(1), calls)
    }

    @Test fun grabbingLensOffCenterDoesNotJump() {
        setBar()
        val off = slotPx * 0.3f
        bar.performTouchInput { down(Offset(centerX(0) + off, centerY)); moveBy(Offset(slotPx * 0.2f, 0f)) }
        rule.waitForIdle()
        // Линза сдвинулась ровно на движение пальца, а не прыгнула под палец.
        assertEquals(0.5f * slotPx + slotPx * 0.2f, lensInner(), 2f)
        bar.performTouchInput { up() }
    }

    @Test fun systemCancelReturnsLensWithoutSelecting() {
        setBar()
        bar.performTouchInput {
            down(Offset(centerX(0), centerY))
            slowMove(centerX(0), centerX(2))
            cancel()
        }
        rule.waitForIdle()
        assertTrue(calls.isEmpty())
        assertEquals(-1, state.preview)
        assertEquals(0.5f * slotPx, lensInner(), 1.5f)
    }

    @Test fun horizontalOverrunIsClampedToBar() {
        setBar()
        bar.performTouchInput {
            down(Offset(centerX(1), centerY))
            slowMove(centerX(1), barWidthPx + 300f)
        }
        rule.waitForIdle()
        val half = LiquidBarMath(slotPx, 4).lensWidth / 2f
        assertTrue(lensInner() <= 4 * slotPx - half + 0.5f)
        bar.performTouchInput { slowMove(barWidthPx + 300f, -400f) }
        rule.waitForIdle()
        assertTrue(lensInner() >= half - 0.5f)
        bar.performTouchInput { up() }
        rule.waitForIdle()
        // Отпустили у левого края — это уже выбранная «Главная»: переключения нет.
        assertTrue(calls.isEmpty())
        assertEquals(0, selected.intValue)
    }

    @Test fun leavingFarVerticallyCancelsPreview() {
        setBar()
        bar.performTouchInput {
            down(Offset(centerX(0), centerY))
            slowMove(centerX(0), centerX(2))
            moveTo(Offset(centerX(2), -height * 3f))
        }
        rule.waitForIdle()
        assertEquals(-1, state.preview)
        bar.performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(calls.isEmpty())
        assertEquals(0.5f * slotPx, lensInner(), 1.5f)
    }

    @Test fun secondFingerIsIgnored() {
        setBar()
        bar.performTouchInput {
            down(0, Offset(centerX(0), centerY))
            moveTo(0, Offset(centerX(1), centerY))
            down(1, Offset(centerX(3), centerY))
            moveTo(0, Offset(centerX(2), centerY))
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertEquals(listOf(2), calls)
    }

    @Test fun recompositionsDuringDragDoNotResetGesture() {
        setBar()
        bar.performTouchInput { down(Offset(centerX(0), centerY)) }
        for (k in 1..12) {
            val tx = centerX(0) + (centerX(2) - centerX(0)) * k / 12f
            bar.performTouchInput { moveTo(Offset(tx, centerY)); advanceEventTime(16) }
            rule.runOnIdle { progress.intValue++ }   // «прогресс загрузки» во время жеста
            rule.waitForIdle()
            assertEquals(tx - padPx, lensInner(), 1.5f)
            assertTrue(state.dragging)
        }
        bar.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(2), calls)
    }

    @Test fun externalSelectionMovesLens() {
        setBar()
        rule.runOnIdle { selected.intValue = 1 }   // например, «Загрузки» из уведомления
        rule.waitForIdle()
        assertEquals(1.5f * slotPx, lensInner(), 1.5f)
        assertTrue(calls.isEmpty())
    }

    @Test fun jitterAtBoundaryDoesNotFlickerPreview() {
        setBar()
        bar.performTouchInput {
            down(Offset(centerX(0), centerY))
            slowMove(centerX(0), x(1.05f), 12)   // за границу 0|1 чуть-чуть
            for (k in 0 until 10) { moveTo(Offset(x(if (k % 2 == 0) 0.97f else 1.05f), centerY)); advanceEventTime(16) }
        }
        rule.waitForIdle()
        assertTrue("preview flickered ${state.previewChanges} times", state.previewChanges <= 1)
        bar.performTouchInput { up() }
    }

    @Test fun economyAndReducedMotionKeepDirectFollow() {
        setBar(cfg = economy.copy(reduceMotion = true))
        bar.performTouchInput { down(Offset(centerX(0), centerY)); slowMove(centerX(0), centerX(2)) }
        rule.waitForIdle()
        assertEquals(centerX(2) - padPx, lensInner(), 1.5f)
        assertEquals(0f, state.currentStretch, 0f)
        bar.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(2), calls)
    }

    @Test fun fullGlassWithLiveBackdropWorks() {
        setBar(cfg = GlassConfig(GlassMode.FULL, true, true, true, 0.55f, 0.6f, 0.7f, false), withBackdrop = true)
        bar.performTouchInput {
            down(Offset(centerX(0), centerY))
            slowMove(centerX(0), centerX(3))
            slowMove(centerX(3), centerX(1))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(1), calls)
    }

    @Test fun verticalScrollAboveBarStillWorks() {
        val listState = LazyListState()
        setBar(list = listState, listFactory = true)
        rule.onNodeWithTag("list").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0)
        assertTrue(calls.isEmpty())
    }

    @Test fun tabsAreAccessibleTabsWithSelection() {
        setBar()
        val tabs = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        tabs.assertCountEquals(4)
        tabs[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        tabs[2].performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf(2), calls)
        tabs[2].assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test fun layoutAt360And411And600AndLargeFont() {
        for ((w, fs) in listOf(360.dp to 1f, 411.dp to 1f, 600.dp to 1f, 360.dp to 1.3f, 411.dp to 1.3f)) {
            calls.clear()
            rule.runOnIdle { selected.intValue = 0 }
            setBarOnce(w, fs)
            val barBounds = bar.getBoundsInRoot()
            assertEquals("bar width at $w", w.value, (barBounds.right - barBounds.left).value, 0.5f)
            for (it in items) {
                val node = rule.onAllNodesWithText(it.label, useUnmergedTree = true)[0]
                val b = node.getBoundsInRoot()
                assertTrue("${it.label} outside bar at $w/$fs", b.left >= barBounds.left && b.right <= barBounds.right)
                val results = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { f -> f(results) }
                assertFalse("${it.label} truncated at $w/$fs", results.first().isLineEllipsized(0))
            }
            // Линза у крайних вкладок остаётся в пределах панели.
            val m = LiquidBarMath(slotPx, 4)
            for (edge in listOf(3, 0)) {
                rule.runOnIdle { selected.intValue = edge }
                rule.waitForIdle()
                assertTrue(lensInner() - m.lensWidth / 2f >= -0.5f)
                assertTrue(lensInner() + m.lensWidth / 2f <= 4 * slotPx + 0.5f)
            }
        }
    }

    // Один compose-rule допускает один setContent: размеры меняем через состояние.
    private val size = mutableStateOf(411.dp to 1f)
    private var sizedSet = false
    private fun setBarOnce(w: Dp, fs: Float) {
        size.value = w to fs
        if (!sizedSet) {
            sizedSet = true
            rule.setContent {
                val d = LocalDensity.current
                val (width, scale) = size.value
                padPx = with(d) { 6.dp.toPx() }
                CompositionLocalProvider(LocalDensity provides Density(d.density, scale), LocalGlassConfig provides economy) {
                    // Ширина раскладки ровно заданная, даже если экран стенда уже.
                    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(width)) {
                        GlassBottomBar(items, selected.intValue, { calls += it; selected.intValue = it },
                            Modifier.testTag("bar").measured(), state)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

}
