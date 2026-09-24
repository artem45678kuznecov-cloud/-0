package com.nox.offline.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Согласованная палитра NOX. Всё, что зависит от выбранной темы —
 * кнопки, выделение, обводки, прогресс, навигация, блики, свечение фона —
 * берётся отсюда. Семантические цвета ([danger], [ok]) и текст от темы не
 * зависят: ошибка остаётся красной, успех — зелёным при любом акценте.
 */
@Immutable
data class NoxPalette(
    val id: String,
    val accent: Color,
    val accentLight: Color,
    val accentDeep: Color,
    val bg: Color,
    val bgTop: Color,
    val bgDeep: Color,
    /** Основа окраски стекла. */
    val glassTint: Color,
    /** Светящаяся кромка стекла. */
    val edge: Color,
    /** Два цвета свечения на фоне. */
    val glowA: Color,
    val glowB: Color,
) {
    val danger: Color get() = Nox.Danger
    val ok: Color get() = Nox.Ok
}

data class ThemePreset(val id: String, val label: String, val accent: Color)

object NoxPalettes {
    val presets: List<ThemePreset> = listOf(
        ThemePreset("classic", "Классический NOX", Color(0xFF7B7BF5)),
        ThemePreset("ice", "Ледяной синий", Color(0xFF5AA9FF)),
        ThemePreset("teal", "Бирюза", Color(0xFF2CC6C6)),
        ThemePreset("emerald", "Изумруд", Color(0xFF34D399)),
        ThemePreset("ruby", "Рубин", Color(0xFFF0506E)),
        ThemePreset("amber", "Янтарь", Color(0xFFF5A524)),
        ThemePreset("neon", "Розовый неон", Color(0xFFFF4FD8)),
        ThemePreset("graphite", "Графит", Color(0xFFA7AEBD)),
    )

    private val BASE_BG = Color(0xFF040611)
    private val BASE_TOP = Color(0xFF070A18)
    private val BASE_DEEP = Color(0xFF02040B)

    /** Классика — точные значения из nox_ui.py, без производных. */
    val classic = NoxPalette(
        id = "classic",
        accent = Color(0xFF7B7BF5),
        accentLight = Color(0xFFB3B0FF),
        accentDeep = Color(0xFF4F49D8),
        bg = BASE_BG, bgTop = BASE_TOP, bgDeep = BASE_DEEP,
        glassTint = Color(0xFF0B0F22),
        edge = Color(0xFF8D8BFF),
        glowA = Color(0xFF3A36C8),
        glowB = Color(0xFF1B2A8C),
    )

    fun of(presetId: String, customHue: Float): NoxPalette {
        if (presetId == "classic") return classic
        val accent = if (presetId == "custom") hsv(customHue, 0.55f, 0.96f)
        else presets.firstOrNull { it.id == presetId }?.accent ?: return classic
        return derive(presetId, accent)
    }

    /** Производная палитра из одного акцента. Фон остаётся почти чёрным. */
    fun derive(id: String, accent: Color): NoxPalette {
        val neutral = saturation(accent) < 0.2f
        val tintAmount = if (neutral) 0.0f else 0.05f
        return NoxPalette(
            id = id,
            accent = accent,
            accentLight = mix(accent, Color.White, 0.45f),
            accentDeep = mix(accent, Color.Black, 0.35f),
            bg = mix(BASE_BG, accent, tintAmount * 0.6f),
            bgTop = mix(BASE_TOP, accent, tintAmount),
            bgDeep = BASE_DEEP,
            glassTint = mix(Color(0xFF0B0F22), accent, if (neutral) 0.02f else 0.06f),
            edge = mix(accent, Color.White, 0.25f),
            glowA = mix(accent, Color.Black, 0.45f),
            glowB = mix(mix(accent, Color(0xFF1B2A8C), 0.5f), Color.Black, 0.35f),
        )
    }

    fun mix(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * k,
            green = a.green + (b.green - a.green) * k,
            blue = a.blue + (b.blue - a.blue) * k,
            alpha = a.alpha + (b.alpha - a.alpha) * k,
        )
    }

    fun saturation(c: Color): Float {
        val mx = max(c.red, max(c.green, c.blue))
        val mn = min(c.red, min(c.green, c.blue))
        return if (mx <= 0f) 0f else (mx - mn) / mx
    }

    /** HSV -> Color без android.graphics: работает и в JVM-тестах. */
    fun hsv(h: Float, s: Float, v: Float): Color {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v * s
        val x = c * (1 - abs(hh % 2 - 1))
        val m = v - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m)
    }

    /** Относительная яркость по WCAG — для проверок читаемости. */
    fun luminance(c: Color): Float {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    fun contrast(a: Color, b: Color): Float {
        val la = luminance(a) + 0.05f
        val lb = luminance(b) + 0.05f
        return max(la, lb) / min(la, lb)
    }
}
