package com.nox.offline.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nox.offline.ui.theme.GlassConfig
import com.nox.offline.ui.theme.NoxPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Состояние стеклянной линзы нижней панели.
 *
 * Три разные вещи держатся раздельно:
 * - реально выбранная вкладка — у вызывающего кода (`selected`);
 * - [preview] — вкладка под пальцем во время ведения, только подсветка;
 * - положение линзы — непрерывная координата: во время ведения это
 *   [dragCenter] прямо из пальца, в покое — пружина [lensX].
 */
@Stable
class LiquidBarState {
    internal val lensX = Animatable(Float.NaN)
    internal val lift = Animatable(0f)
    internal val stretch = Animatable(0f)

    internal var dragCenter by mutableFloatStateOf(Float.NaN)
    internal var dragStretch by mutableFloatStateOf(0f)

    /** Вкладка под пальцем во время ведения, −1 — ведения нет. */
    var preview by mutableIntStateOf(-1)
        internal set

    /** Палец ведёт линзу. */
    var dragging by mutableStateOf(false)
        internal set

    /** Где линза сейчас — в пикселях области вкладок (для отрисовки и тестов). */
    val lensCenter: Float get() = if (dragging) dragCenter else lensX.value

    /** Сколько раз с начала жеста менялась подсветка — для тестов тактильного отклика. */
    var previewChanges by mutableIntStateOf(0)
        internal set

    internal val currentStretch: Float get() = if (dragging) dragStretch else stretch.value
}

@Composable
fun rememberLiquidBarState(): LiquidBarState = remember { LiquidBarState() }

/**
 * Отрисовка линзы. Под линзой — настоящее содержимое экрана (слой
 * [backdrop]), увеличенное; в полном режиме на API 33+ — ещё и
 * преломление у округлого края со слабой цветной каймой. Без слоя
 * (старые Android) линза увеличивает размытую копию фона.
 */
internal class LiquidLensPainter {
    private val path = Path()
    private var shader: Any? = null
    private var effect: RenderEffect? = null
    private var effectKey: Any? = null

    fun DrawScope.drawLens(
        state: LiquidBarState,
        math: LiquidBarMath,
        palette: NoxPalette,
        cfg: GlassConfig,
        backdrop: GraphicsLayer?,
        lensLayer: GraphicsLayer?,
        frame: WallpaperFrame?,
        rootOffset: Offset,
    ) {
        val center = state.lensCenter
        if (center.isNaN() || math.slot <= 0f) return
        val lift = state.lift.value.coerceIn(0f, 1.2f)
        val s = if (cfg.reduceMotion) 0f else state.currentStretch.coerceIn(-1f, 1f)
        val grow = 1f + 0.10f * lift
        val lensW = math.lensWidth * grow * (1f + 0.16f * abs(s))
        val lensH = size.height * grow * (1f - 0.07f * abs(s))
        val left = center - lensW / 2f
        val top = (size.height - lensH) / 2f
        val radius = lensH / 2f
        val rr = RoundRect(left, top, left + lensW, top + lensH, CornerRadius(radius, radius))
        path.reset(); path.addRoundRect(rr)

        // Мягкое свечение акцентом под линзой — снаружи обрезки.
        val glowA = (0.10f + 0.10f * lift) * cfg.glow.coerceIn(0f, 1f)
        for (i in 1..3) {
            val g = (i * 2.4f).dp.toPx()
            drawRoundRect(palette.accent.copy(alpha = glowA / i), Offset(left - g, top - g), Size(lensW + 2 * g, lensH + 2 * g),
                CornerRadius(radius + g, radius + g), style = Stroke(width = 2.4.dp.toPx()))
        }

        val mag = 1.06f + 0.10f * lift
        clipPath(path) {
            if (backdrop != null && rootOffset.isSpecified) {
                val refract = lensLayer != null && cfg.live && cfg.refraction && Build.VERSION.SDK_INT >= 33
                if (refract) drawRefracted(backdrop, lensLayer!!, rootOffset, left, top, lensW, lensH, radius, mag, s, cfg)
                else drawMagnified(backdrop, rootOffset, center, size.height / 2f, mag)
            } else {
                drawSampleMagnified(frame, rootOffset, center, size.height / 2f, mag, palette)
            }

            // Тонкое тёмное стекло с акцентом темы: содержимое видно, текст на нём — нет.
            drawRect(palette.glassTint.copy(alpha = 0.26f), Offset(left, top), Size(lensW, lensH))
            drawRect(Brush.linearGradient(
                listOf(palette.accent.copy(alpha = 0.26f), palette.accentDeep.copy(alpha = 0.20f)),
                start = Offset(left, top), end = Offset(left + lensW, top + lensH)), Offset(left, top), Size(lensW, lensH))
            // Объём: к краю темнее, в середине светлее.
            drawRect(Brush.radialGradient(
                0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f),
                center = Offset(center, top + lensH / 2f), radius = max(lensW, lensH) * 0.62f), Offset(left, top), Size(lensW, lensH))
            // Блик сверху смещается против движения — «жидкий» отклик.
            val hl = 0.55f + 0.45f * cfg.intensity
            val hx = center - s * lensW * 0.22f
            drawRect(Brush.radialGradient(
                listOf(Color.White.copy(alpha = (0.26f + 0.10f * lift) * hl), Color.White.copy(alpha = 0.05f * hl), Color.Transparent),
                center = Offset(hx, top + lensH * 0.16f), radius = lensW * 0.46f), Offset(left, top), Size(lensW, lensH))
            // Отражённый свет у нижней кромки.
            drawRect(Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.09f * hl), Color.Transparent),
                center = Offset(center + s * lensW * 0.15f, top + lensH * 0.98f), radius = lensW * 0.34f), Offset(left, top), Size(lensW, lensH))
        }

        // Световая кромка: ведущий по движению край ярче.
        val lead = abs(s)
        val la = 0.55f + (if (s < 0) 0.35f * lead else 0f)
        val ra = 0.55f + (if (s > 0) 0.35f * lead else 0f)
        drawRoundRect(Brush.horizontalGradient(
            0f to palette.edge.copy(alpha = la.coerceAtMost(1f)),
            0.5f to Color.White.copy(alpha = 0.16f),
            1f to palette.accentLight.copy(alpha = ra.coerceAtMost(1f)),
            startX = left, endX = left + lensW),
            Offset(left, top), Size(lensW, lensH), CornerRadius(radius, radius), style = Stroke(width = 1.2.dp.toPx()))
        // Очень слабая цветная кайма — только в движении.
        if (lead > 0.05f && !cfg.reduceMotion) {
            val o = 0.9.dp.toPx() * lead
            drawRoundRect(Color(1f, 0.35f, 0.3f, 0.10f * lead), Offset(left - o, top), Size(lensW, lensH),
                CornerRadius(radius, radius), style = Stroke(width = 1.dp.toPx()))
            drawRoundRect(Color(0.3f, 0.55f, 1f, 0.10f * lead), Offset(left + o, top), Size(lensW, lensH),
                CornerRadius(radius, radius), style = Stroke(width = 1.dp.toPx()))
        }
    }

    /** Экономичный путь и API 31–32: настоящее содержимое с увеличением, без эффектов. */
    private fun DrawScope.drawMagnified(backdrop: GraphicsLayer, root: Offset, cx: Float, cy: Float, mag: Float) {
        withTransform({
            scale(mag, mag, pivot = Offset(cx, cy))
            translate(-root.x, -root.y)
        }) { drawLayer(backdrop) }
    }

    private fun DrawScope.drawSampleMagnified(f: WallpaperFrame?, root: Offset, cx: Float, cy: Float, mag: Float, palette: NoxPalette) {
        if (f == null || !root.isSpecified) {
            drawRect(palette.glassTint.copy(alpha = 0.6f)); return
        }
        withTransform({ scale(mag, mag, pivot = Offset(cx, cy)) }) {
            drawImage(f.sample, IntOffset.Zero, IntSize(f.sample.width, f.sample.height),
                IntOffset((-root.x).roundToInt(), (-root.y).roundToInt()), IntSize(f.width, f.height), filterQuality = FilterQuality.Low)
        }
    }

    /** Полный режим API 33+: слой размером с линзу, шейдер преломления и кайма. */
    private fun DrawScope.drawRefracted(
        backdrop: GraphicsLayer, layer: GraphicsLayer, root: Offset,
        left: Float, top: Float, w: Float, h: Float, radius: Float, mag: Float, s: Float, cfg: GlassConfig,
    ) {
        if (Build.VERSION.SDK_INT < 33) return
        val pad = 10.dp.toPx().roundToInt()
        val strength = 7.dp.toPx() * (0.5f + 0.5f * cfg.intensity)
        // Кайма заметна только в движении и сильно ограничена.
        val chroma = ((0.35f + 1.4f * abs(s)) * cfg.intensity).coerceAtMost(1.6f)
        val key = listOf(w.roundToInt(), h.roundToInt(), (strength * 4).roundToInt(), (chroma * 8).roundToInt())
        if (key != effectKey) {
            effect = runCatching { lensEffect(pad.toFloat(), w, h, radius, strength, chroma, 1.1.dp.toPx()) }.getOrNull()
            effectKey = key
        }
        layer.renderEffect = effect
        layer.record(this, layoutDirection, IntSize(w.roundToInt() + 2 * pad, h.roundToInt() + 2 * pad)) {
            withTransform({
                scale(mag, mag, pivot = Offset(pad + w / 2f, pad + h / 2f))
                translate(-(root.x + left) + pad, -(root.y + top) + pad)
            }) { drawLayer(backdrop) }
        }
        translate(left - pad, top - pad) { drawLayer(layer) }
    }

    @RequiresApi(33)
    private fun lensEffect(pad: Float, w: Float, h: Float, radius: Float, strength: Float, chroma: Float, blur: Float): RenderEffect {
        val sh = (shader as? RuntimeShader) ?: RuntimeShader(LENS_AGSL).also { shader = it }
        sh.setFloatUniform("rect", pad, pad, w, h)
        sh.setFloatUniform("radius", radius)
        sh.setFloatUniform("strength", strength)
        sh.setFloatUniform("chroma", chroma)
        val refract = android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content")
        val soft = android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)
        return android.graphics.RenderEffect.createChainEffect(refract, soft).asComposeRenderEffect()
    }

    companion object {
        /**
         * Линза-капсула: у края выборка стягивается к центру (толстое
         * округлое стекло), каналы R и B чуть расходятся только у самой
         * кромки — едва заметная дисперсия.
         */
        private const val LENS_AGSL = """
            uniform shader content;
            uniform float4 rect;
            uniform float radius;
            uniform float strength;
            uniform float chroma;

            half4 main(float2 p) {
                float2 h = rect.zw * 0.5;
                float2 c = rect.xy + h;
                float r = min(radius, min(h.x, h.y));
                float2 q = abs(p - c) - (h - float2(r));
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
                float band = max(r * 1.1, 6.0);
                float t = clamp(1.0 + d / band, 0.0, 1.0);
                float k = t * t;
                float2 dir = p - c;
                float len = max(length(dir), 1.0);
                float2 n = dir / len;
                float2 sp = p - n * strength * k;
                half4 g = content.eval(sp);
                if (chroma < 0.05) { return g; }
                float2 ch = n * chroma * k * t;
                half rr = content.eval(sp + ch).r;
                half bb = content.eval(sp - ch).b;
                return half4(rr, g.g, bb, g.a);
            }
        """
    }
}
