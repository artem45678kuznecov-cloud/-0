package com.nox.offline.ui.glass

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier.Node
import com.nox.offline.ui.theme.GlassConfig
import com.nox.offline.ui.theme.LocalGlassConfig
import com.nox.offline.ui.theme.LocalNoxPalette
import com.nox.offline.ui.theme.NoxPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Текущий кадр фона: из него стекло берёт размытый задний слой. */
val LocalWallpaperFrame = compositionLocalOf<WallpaperFrame?> { null }

/**
 * Живой задний слой: всё содержимое экрана, записанное в GraphicsLayer.
 * Есть только в режиме полного стекла на API 31+; им пользуются
 * плавающие поверхности (нижняя панель, листы), которые лежат НАД
 * прокручиваемым содержимым и должны размывать именно его.
 */
val LocalContentBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

/** Параметры конкретной поверхности. Общие значения — из [GlassConfig]. */
@Immutable
data class GlassStyle(
    /** Непрозрачность окраски; null — из настроек пользователя. */
    val tintAlpha: Float? = null,
    /** Размывать живое содержимое позади (а не только фон). */
    val live: Boolean = false,
    /** Сила внешнего свечения, 0 — без него. */
    val glow: Float = 0f,
    /** Доля акцентной заливки: кнопка «Скачать», выбранная плитка. */
    val accentFill: Float = 0f,
    val edge: Float = 1f,
    val highlight: Float = 1f,
    /** Лёгкое преломление у краёв (API 33+, только для live). */
    val refraction: Boolean = false,
)

object GlassStyles {
    val Card = GlassStyle()
    val Subtle = GlassStyle(tintAlpha = null, edge = 0.7f, highlight = 0.6f)
    val Chip = GlassStyle(edge = 0.8f, highlight = 0.8f)
    val Selected = GlassStyle(accentFill = 0.30f, glow = 0.55f, edge = 1.3f)
    val Primary = GlassStyle(accentFill = 0.82f, glow = 1f, edge = 1.2f, highlight = 1.2f)
    val Bar = GlassStyle(live = true, refraction = true, glow = 0.35f, edge = 1.1f)
    val Sheet = GlassStyle(live = true, tintAlpha = 0.78f, edge = 1f)
    val Field = GlassStyle(tintAlpha = 0.62f, edge = 0.6f, highlight = 0.4f)
}

/**
 * Стеклянная поверхность как модификатор. Рисует задний слой, окраску,
 * блики и кромку ПОД содержимым, само содержимое не размывается.
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(22.dp),
    style: GlassStyle = GlassStyles.Card,
    pressed: Float = 0f,
): Modifier {
    val cfg = LocalGlassConfig.current
    val palette = LocalNoxPalette.current
    val frame = LocalWallpaperFrame.current
    val useLive = style.live && cfg.live && Build.VERSION.SDK_INT >= 31
    val backdrop = if (useLive) LocalContentBackdrop.current else null
    val blurLayer = if (backdrop != null) rememberGraphicsLayer() else null
    val glowLayer = if (style.glow > 0f && cfg.softGlow && Build.VERSION.SDK_INT >= 31) rememberGraphicsLayer() else null
    return this.then(GlassElement(shape, style, palette, cfg, frame, backdrop, blurLayer, glowLayer, pressed))
}

private data class GlassElement(
    val shape: Shape,
    val style: GlassStyle,
    val palette: NoxPalette,
    val cfg: GlassConfig,
    val frame: WallpaperFrame?,
    val backdrop: GraphicsLayer?,
    val blurLayer: GraphicsLayer?,
    val glowLayer: GraphicsLayer?,
    val pressed: Float,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(this)
    override fun update(node: GlassNode) = node.update(this)
    override fun InspectorInfo.inspectableProperties() { name = "glass" }
}

private class GlassNode(private var e: GlassElement) : Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var pos = Offset.Unspecified
    private var glowKey: Any? = null
    private var effectKey: Any? = null
    private var effect: RenderEffect? = null
    private val clip = Path()
    private var clipKey: Any? = null

    fun update(next: GlassElement) {
        e = next
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != pos) {
            pos = p
            // Стекло показывает «свой» кусок заднего слоя: при прокрутке он
            // обязан смещаться относительно карточки, иначе фон поедет вместе с ней.
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val s = e.style
        val cfg = e.cfg
        val pal = e.palette
        val outline = e.shape.createOutline(size, layoutDirection, this)
        val key = Triple(size, e.shape, layoutDirection)
        if (key != clipKey) {
            clip.reset()
            when (outline) {
                is Outline.Rectangle -> clip.addRect(outline.rect)
                is Outline.Rounded -> clip.addRoundRect(outline.roundRect)
                is Outline.Generic -> clip.addPath(outline.path)
            }
            clipKey = key
        }

        val glowAmt = (s.glow * cfg.glow).coerceIn(0f, 1f)
        if (glowAmt > 0.02f) drawGlow(outline, glowAmt)

        clipPath(clip) {
            val live = e.backdrop != null && e.blurLayer != null && pos.isSpecified
            if (live) drawLive(outline) else drawSample()

            // Окраска темой: сверху чуть светлее, снизу гуще — объём.
            val a = (s.tintAlpha ?: cfg.opacity).coerceIn(0.2f, 0.95f)
            drawRect(Brush.verticalGradient(listOf(pal.glassTint.copy(alpha = a * 0.82f), pal.glassTint.copy(alpha = min(1f, a * 1.12f)))))
            if (s.accentFill > 0f) {
                drawRect(Brush.linearGradient(
                    listOf(pal.accent.copy(alpha = s.accentFill), pal.accentDeep.copy(alpha = s.accentFill * 0.95f)),
                    start = Offset.Zero, end = Offset(size.width, size.height)))
            }
            val hl = cfg.intensity * s.highlight
            // Внутренний блик: неоднородное пятно у верхнего левого края.
            drawRect(Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.15f * hl), Color.White.copy(alpha = 0.04f * hl), Color.Transparent),
                center = Offset(size.width * 0.18f, -size.height * 0.15f),
                radius = max(size.width, size.height) * 0.85f))
            // Тонкая светлая полоса у верхней кромки.
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f * hl), 0.28f to Color.Transparent))
            // Глубина у нижней кромки.
            drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.20f)))
            if (e.pressed > 0f) drawRect(Color.White.copy(alpha = 0.09f * e.pressed))
        }

        // Световая кромка: ярче у верхнего левого угла и у нижнего правого,
        // почти гаснет по бокам — «неоднородный край».
        val ea = (s.edge * (0.35f + 0.65f * cfg.intensity) * (1f + 0.4f * e.pressed)).coerceIn(0f, 1.6f)
        drawOutline(outline, Brush.linearGradient(
            0f to pal.edge.copy(alpha = (0.80f * ea).coerceAtMost(1f)),
            0.30f to pal.edge.copy(alpha = 0.20f * ea),
            0.62f to pal.edge.copy(alpha = 0.10f * ea),
            1f to pal.accentLight.copy(alpha = (0.50f * ea).coerceAtMost(1f)),
            start = Offset.Zero, end = Offset(size.width, size.height)),
            style = Stroke(width = 1.dp.toPx()))
        drawContent()
    }

    /** Размытая копия фона, совмещённая с экраном по координатам карточки. */
    private fun DrawScope.drawSample() {
        val f = e.frame
        if (f == null || !pos.isSpecified) {
            drawRect(e.palette.glassTint.copy(alpha = 0.9f)); return
        }
        drawImage(
            image = f.sample,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(f.sample.width, f.sample.height),
            dstOffset = IntOffset((-pos.x).roundToInt(), (-pos.y).roundToInt()),
            dstSize = IntSize(f.width, f.height),
            filterQuality = FilterQuality.Low,
        )
    }

    /** Живое содержимое экрана позади поверхности, размытое GPU. */
    private fun DrawScope.drawLive(outline: Outline) {
        val src = e.backdrop!!
        val layer = e.blurLayer!!
        val radius = 22.dp.toPx() * (0.6f + 0.4f * e.cfg.intensity)
        val pad = (radius * 2f).roundToInt()
        val corner = cornerOf(outline)
        val refr = e.style.refraction && e.cfg.refraction && Build.VERSION.SDK_INT >= 33
        val strength = 9.dp.toPx() * e.cfg.intensity
        val key = listOf(size, radius, refr, corner, strength)
        if (key != effectKey) {
            effect = if (refr) Refraction.effect(size, pad.toFloat(), corner, strength, radius)
            else BlurEffect(radius, radius, TileMode.Clamp)
            effectKey = key
        }
        layer.renderEffect = effect
        layer.record(this, layoutDirection, IntSize(size.width.roundToInt() + 2 * pad, size.height.roundToInt() + 2 * pad)) {
            translate(-pos.x + pad, -pos.y + pad) { drawLayer(src) }
        }
        translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(layer) }
    }

    private fun DrawScope.drawGlow(outline: Outline, amount: Float) {
        val color = e.palette.accent
        val layer = e.glowLayer
        if (layer != null) {
            val r = 12.dp.toPx()
            val pad = (r * 3f).roundToInt()
            val key = listOf(size, color, amount, e.shape)
            if (key != glowKey) {
                layer.renderEffect = BlurEffect(r, r, TileMode.Decal)
                layer.record(this, layoutDirection, IntSize(size.width.roundToInt() + 2 * pad, size.height.roundToInt() + 2 * pad)) {
                    translate(pad.toFloat(), pad.toFloat() + r * 0.35f) {
                        drawOutline(outline, color.copy(alpha = 0.55f * amount))
                    }
                }
                glowKey = key
            }
            translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(layer) }
        } else {
            // Без RenderEffect: несколько мягких контуров той же формы —
            // ореол повторяет скругление, квадратных углов не появляется.
            for (i in 1..3) {
                val g = (i * 2.2f).dp.toPx()
                val grown = e.shape.createOutline(Size(size.width + 2 * g, size.height + 2 * g), layoutDirection, this)
                translate(-g, -g) {
                    drawOutline(grown, color.copy(alpha = 0.10f * amount / i), style = Stroke(width = 2.6.dp.toPx()))
                }
            }
        }
    }

    private fun cornerOf(outline: Outline): Float = when (outline) {
        is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
        else -> 0f
    }
}

/** Запись всего содержимого экрана в слой — источник живого стекла. */
fun Modifier.backdropSource(layer: GraphicsLayer?): Modifier =
    if (layer == null) this else this.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

/**
 * Стеклянный контейнер с реакцией на нажатие: лёгкое сжатие и
 * вспышка блика. Анимация выключается настройкой «Уменьшить движение».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    style: GlassStyle = GlassStyles.Card,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val reduce = LocalGlassConfig.current.reduceMotion
    val press by animateFloatAsState(if (isPressed) 1f else 0f, spring(stiffness = 700f), label = "press")
    val scale = if (reduce) 1f else 1f - 0.025f * press
    var m = modifier
    val clickable = onClick != null || onLongClick != null
    if (clickable) {
        m = m.graphicsLayer { scaleX = scale; scaleY = scale }
    }
    m = m.glass(shape, style, press)
    if (clickable) {
        m = m.combinedClickable(interactionSource = interaction, indication = null, enabled = enabled,
            onLongClick = onLongClick, onClick = onClick ?: {})
    }
    Box(modifier = m.padding(contentPadding), contentAlignment = contentAlignment, content = content)
}

internal fun roundRectOf(size: Size, radius: Float) =
    RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius, radius))

internal fun Dp.coerceMin(min: Dp): Dp = if (this < min) min else this
