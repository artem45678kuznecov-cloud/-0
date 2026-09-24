package com.nox.offline.ui

import androidx.compose.ui.graphics.Color
import com.nox.offline.settings.Appearance
import com.nox.offline.ui.glass.BoxBlur
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.NoxPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun eightPresetsWithRussianNames() {
        assertEquals(
            listOf("Классический NOX", "Ледяной синий", "Бирюза", "Изумруд", "Рубин", "Янтарь", "Розовый неон", "Графит"),
            NoxPalettes.presets.map { it.label },
        )
        assertEquals(NoxPalettes.classic, NoxPalettes.of("classic", 0f))
        assertEquals(NoxPalettes.classic, NoxPalettes.of("unknown", 0f))
    }

    @Test fun everyAccentStaysReadableOnDarkBackground() {
        val ids = NoxPalettes.presets.map { it.id }
        val palettes = ids.map { NoxPalettes.of(it, 0f) } + (0 until 360 step 15).map { NoxPalettes.of("custom", it.toFloat()) }
        for (p in palettes) {
            assertTrue("${p.id}: accent on bg", NoxPalettes.contrast(p.accent, p.bg) >= 3f)
            assertTrue("${p.id}: light accent on glass", NoxPalettes.contrast(p.accentLight, p.glassTint) >= 4.5f)
            assertTrue("${p.id}: primary text on glass", NoxPalettes.contrast(Nox.TextPrimary, p.glassTint) >= 7f)
            assertTrue("${p.id}: background stays dark", NoxPalettes.luminance(p.bg) < 0.02f)
        }
    }

    @Test fun sanitizedClampsToReadableRange() {
        val a = Appearance(glassOpacity = 0.01f, dim = 0.0f, zoom = 9f, focusX = -1f, glowStrength = 3f).sanitized()
        assertEquals(Appearance.MIN_GLASS_OPACITY, a.glassOpacity, 0f)
        assertEquals(Appearance.MIN_DIM, a.dim, 0f)
        assertEquals(3f, a.zoom, 0f)
        assertEquals(0f, a.focusX, 0f)
        assertEquals(1f, a.glowStrength, 0f)
        val b = Appearance(glassOpacity = 2f, dim = 2f).sanitized()
        assertEquals(Appearance.MAX_GLASS_OPACITY, b.glassOpacity, 0f)
        assertEquals(Appearance.MAX_DIM, b.dim, 0f)
    }

    @Test fun hsvRoundTripsPrimaries() {
        assertEquals(Color.Red, NoxPalettes.hsv(0f, 1f, 1f))
        assertEquals(Color.Green, NoxPalettes.hsv(120f, 1f, 1f))
        assertEquals(Color.Blue, NoxPalettes.hsv(240f, 1f, 1f))
    }

    @Test fun boxBlurSpreadsAndPreservesAverage() {
        val w = 16; val h = 16
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        px[8 * w + 8] = 0xFFFFFFFF.toInt()
        val before = BoxBlur.averageLuminance(px, w, h, 1)
        BoxBlur.blur(px, w, h, radius = 2)
        val after = BoxBlur.averageLuminance(px, w, h, 1)
        assertTrue("neighbour lit", (px[8 * w + 10] and 0xFF) > 0)
        assertTrue("peak spread", (px[8 * w + 8] and 0xFF) < 255)
        assertEquals(before, after, 0.01f)
        // Прозрачность не страдает.
        assertTrue(px.all { (it ushr 24) == 0xFF })
    }

    @Test fun ditherIsBoundedAndDeterministic() {
        val a = IntArray(64) { 0xFF101010.toInt() }
        val b = a.copyOf()
        BoxBlur.dither(a); BoxBlur.dither(b)
        assertTrue(a.contentEquals(b))
        assertTrue(a.all { val v = it and 0xFF; v in 14..18 })
    }
}
