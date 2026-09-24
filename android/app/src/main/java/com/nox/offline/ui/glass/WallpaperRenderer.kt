package com.nox.offline.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.nox.offline.settings.Appearance
import com.nox.offline.settings.WallpaperKind
import com.nox.offline.ui.theme.NoxPalette
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Рисует фон NOX в Bitmap — один раз на смену темы, обоев или размера
 * окна, вне главного потока. Возвращает две картинки:
 *
 *  - [Result.display] — сам фон во весь экран;
 *  - [Result.sample]  — сильно уменьшенная и размытая копия того же фона.
 *    Именно её «видно сквозь» стекло карточек: каждая поверхность рисует
 *    свой кусок этой картинки, поэтому размыт только задний слой, а текст
 *    и обложки поверх остаются резкими.
 */
object WallpaperRenderer {
    data class Result(
        val display: Bitmap,
        val sample: Bitmap,
        val luminance: Float,
        val extraDim: Float,
    )

    /** Предел средней яркости фона: выше текст на стекле читается хуже. */
    const val MAX_LUMINANCE = 0.20f
    private const val SAMPLE_SCALE = 6

    fun render(
        appearance: Appearance,
        palette: NoxPalette,
        width: Int,
        height: Int,
        customImage: File?,
    ): Result {
        val w = width.coerceIn(64, 2160)
        val h = height.coerceIn(64, 3840)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val kind = if (appearance.wallpaper == WallpaperKind.CUSTOM && customImage?.exists() == true)
            WallpaperKind.CUSTOM else if (appearance.wallpaper == WallpaperKind.CUSTOM) WallpaperKind.DEFAULT
        else appearance.wallpaper

        if (kind == WallpaperKind.CUSTOM) {
            drawCustom(canvas, w, h, customImage!!, appearance, palette)
        } else {
            drawBuiltIn(canvas, w, h, kind, palette)
        }

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Пользовательское размытие самого фона (не стекла).
        if (appearance.blur > 0.01f) {
            val r = (appearance.blur * 18).roundToInt().coerceAtLeast(1)
            val small = downscale(pixels, w, h, 3)
            BoxBlur.blur(small.first, small.second, small.third, r)
            upscaleInto(small.first, small.second, small.third, pixels, w, h)
        }

        // Страховка читаемости: слишком светлый фон затемняется до предела.
        val lum = BoxBlur.averageLuminance(pixels, w, h)
        var extra = 0f
        if (lum > MAX_LUMINANCE) {
            extra = 1f - MAX_LUMINANCE / lum
            val keep = 1f - extra
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (((p shr 16) and 0xFF) * keep).toInt()
                val g = (((p shr 8) and 0xFF) * keep).toInt()
                val b = ((p and 0xFF) * keep).toInt()
                pixels[i] = (p and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }
        BoxBlur.dither(pixels, amplitude = 2, seed = w * 31 + h)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        // Копия для стекла: в SAMPLE_SCALE раз меньше и сильно размыта.
        val small = downscale(pixels, w, h, SAMPLE_SCALE)
        val radius = (4 + appearance.effectIntensity * 5).roundToInt()
        BoxBlur.blur(small.first, small.second, small.third, radius)
        BoxBlur.dither(small.first, amplitude = 1, seed = 3)
        val sample = Bitmap.createBitmap(small.first, small.second, small.third, Bitmap.Config.ARGB_8888)
        return Result(bmp, sample, lum, extra)
    }

    // ------------------------------------------------------------------

    private fun drawBuiltIn(c: Canvas, w: Int, h: Int, kind: WallpaperKind, p: NoxPalette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(p.bgTop.toArgb(), p.bg.toArgb(), p.bgDeep.toArgb()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val W = w.toFloat(); val H = h.toFloat()
        when (kind) {
            WallpaperKind.AURORA -> {
                glow(c, W * 0.2f, H * 0.18f, W * 0.9f, p.glowA, 0.55f)
                glow(c, W * 0.9f, H * 0.42f, W * 0.8f, NoxColors.mixTeal(p.glowB), 0.45f)
                glow(c, W * 0.5f, H * 0.95f, W * 0.9f, p.accentDeep, 0.25f)
                sheen(c, W, H, p.accentLight, 0.05f, -0.35f)
            }
            WallpaperKind.MIDNIGHT -> {
                glow(c, W * 0.5f, H * 1.05f, W * 1.1f, p.glowA, 0.40f)
                glow(c, W * 0.95f, H * 0.0f, W * 0.5f, p.glowB, 0.20f)
            }
            WallpaperKind.NEBULA -> {
                glow(c, W * 0.8f, H * 0.12f, W * 0.7f, p.glowA, 0.55f)
                glow(c, W * 0.15f, H * 0.35f, W * 0.55f, NoxColors.mixPink(p.accentDeep), 0.35f)
                glow(c, W * 0.6f, H * 0.7f, W * 0.65f, p.glowB, 0.35f)
                stars(c, W, H, p.accentLight)
            }
            WallpaperKind.DEEP -> {
                glow(c, W * 0.5f, H * 0.42f, W * 0.95f, p.glowB, 0.55f)
                glow(c, W * 0.5f, H * 0.42f, W * 0.45f, p.glowA, 0.30f)
            }
            else -> {
                // Как на эталонах: холодное свечение справа сверху, мягкое
                // слева по центру и едва заметный диагональный блик.
                glow(c, W * 0.88f, H * 0.04f, W * 0.85f, p.glowA, 0.50f)
                glow(c, W * 0.05f, H * 0.42f, W * 0.65f, p.glowB, 0.30f)
                glow(c, W * 0.65f, H * 1.0f, W * 0.9f, p.glowB, 0.22f)
                sheen(c, W, H, p.accentLight, 0.035f, 0.45f)
            }
        }
    }

    private fun glow(c: Canvas, cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
        val argb = color.copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
        val mid = color.copy(alpha = alpha * 0.35f).toArgb()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            shader = RadialGradient(cx, cy, max(1f, r), intArrayOf(argb, mid, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, r, paint)
    }

    private fun sheen(c: Canvas, w: Float, h: Float, color: Color, alpha: Float, slope: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            shader = LinearGradient(0f, h * 0.12f, w, h * (0.12f + slope * 0.3f),
                intArrayOf(0, color.copy(alpha = alpha).toArgb(), 0), floatArrayOf(0.35f, 0.62f, 0.85f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h * 0.6f, paint)
    }

    private fun stars(c: Canvas, w: Float, h: Float, color: Color) {
        val rnd = Random(42)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(90) {
            val a = 0.15f + rnd.nextFloat() * 0.45f
            paint.color = color.copy(alpha = a).toArgb()
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.6f + rnd.nextFloat() * 1.4f, paint)
        }
    }

    private fun drawCustom(c: Canvas, w: Int, h: Int, file: File, a: Appearance, p: NoxPalette) {
        val src = BitmapFactory.decodeFile(file.absolutePath)
        if (src == null) {
            drawBuiltIn(c, w, h, WallpaperKind.DEFAULT, p); return
        }
        // Центральное кадрирование с учётом масштаба и точки фокуса.
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height) * a.zoom
        val dw = src.width * scale
        val dh = src.height * scale
        val left = -(dw - w) * a.focusX
        val top = -(dh - h) * a.focusY
        val m = Matrix().apply { setScale(scale, scale); postTranslate(left, top) }
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        src.recycle()
        // Затемнение и лёгкий оттенок темы сверху и снизу.
        c.drawColor(Color.Black.copy(alpha = a.dim).toArgb())
        val tint = Paint().apply {
            isDither = true
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(p.bgTop.copy(alpha = 0.55f).toArgb(), 0, p.bgDeep.copy(alpha = 0.65f).toArgb()),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tint)
    }

    private fun downscale(px: IntArray, w: Int, h: Int, factor: Int): Triple<IntArray, Int, Int> {
        val sw = max(1, w / factor)
        val sh = max(1, h / factor)
        val src = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val dst = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(dst).drawBitmap(src, Rect(0, 0, w, h), RectF(0f, 0f, sw.toFloat(), sh.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        val out = IntArray(sw * sh)
        dst.getPixels(out, 0, sw, 0, 0, sw, sh)
        dst.recycle()
        return Triple(out, sw, sh)
    }

    private fun upscaleInto(small: IntArray, sw: Int, sh: Int, out: IntArray, w: Int, h: Int) {
        val src = Bitmap.createBitmap(small, sw, sh, Bitmap.Config.ARGB_8888)
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(dst).drawBitmap(src, Rect(0, 0, sw, sh), RectF(0f, 0f, w.toFloat(), h.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        dst.getPixels(out, 0, w, 0, 0, w, h)
        dst.recycle()
    }
}

/** Пара вспомогательных оттенков для встроенных фонов. */
internal object NoxColors {
    fun mixTeal(c: Color) = com.nox.offline.ui.theme.NoxPalettes.mix(c, Color(0xFF0E6E7A), 0.55f)
    fun mixPink(c: Color) = com.nox.offline.ui.theme.NoxPalettes.mix(c, Color(0xFF7A1E6E), 0.5f)
}
