package com.nox.offline.ui.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Двойное нажатие на вкладку: пороги как у Android по умолчанию (300 / 40 / 400 мс). */
class TabDoubleTapTest {
    private fun taps() = TabDoubleTap(timeoutMs = 300, minGapMs = 40, longPressMs = 400)

    @Test fun `two quick taps on the same tab are a double tap`() {
        val t = taps()
        assertFalse(t.onTap(1, downMs = 1_000, upMs = 1_080))
        assertTrue(t.onTap(1, downMs = 1_250, upMs = 1_320))
    }

    @Test fun `single tap never fires, a slow second tap is a new first tap`() {
        val t = taps()
        assertFalse(t.onTap(2, 1_000, 1_080))
        assertFalse("промежуток 500 мс — уже не двойное", t.onTap(2, 1_580, 1_650))
        assertTrue("а вот третье быстрое — второе к предыдущему", t.onTap(2, 1_800, 1_860))
    }

    @Test fun `taps on two different tabs are not a double tap`() {
        val t = taps()
        assertFalse(t.onTap(0, 1_000, 1_080))
        assertFalse(t.onTap(1, 1_200, 1_260))
        assertTrue("вторая вкладка дважды — двойное", t.onTap(1, 1_400, 1_460))
    }

    @Test fun `third quick tap starts a new series instead of firing twice`() {
        val t = taps()
        assertFalse(t.onTap(3, 1_000, 1_060))
        assertTrue(t.onTap(3, 1_150, 1_210))
        assertFalse(t.onTap(3, 1_300, 1_360))
    }

    @Test fun `long press neither counts as a tap nor completes a pair`() {
        val t = taps()
        assertFalse(t.onTap(1, 1_000, 1_600))
        assertFalse("после долгого первое нажатие — снова первое", t.onTap(1, 1_700, 1_760))
        assertTrue(t.onTap(1, 1_900, 1_960))
        val u = taps()
        assertFalse(u.onTap(1, 1_000, 1_060))
        assertFalse("второе нажатие оказалось долгим", u.onTap(1, 1_200, 1_700))
    }

    @Test fun `drag, cancel or second finger between taps breaks the series`() {
        val t = taps()
        assertFalse(t.onTap(2, 1_000, 1_060))
        t.reset()                       // перетаскивание линзы, отмена или мультитач
        assertFalse(t.onTap(2, 1_150, 1_210))
    }

    @Test fun `bounce shorter than the minimal gap is not a second tap`() {
        val t = taps()
        assertFalse(t.onTap(1, 1_000, 1_060))
        assertFalse(t.onTap(1, 1_080, 1_100))
    }
}
