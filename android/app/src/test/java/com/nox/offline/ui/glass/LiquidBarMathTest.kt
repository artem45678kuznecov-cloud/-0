package com.nox.offline.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBarMathTest {
    private val m = LiquidBarMath(slot = 100f, count = 4)

    @Test fun centersAndIndexes() {
        assertEquals(50f, m.centerOf(0), 0f)
        assertEquals(350f, m.centerOf(3), 0f)
        assertEquals(0, m.indexAt(-30f))
        assertEquals(1, m.indexAt(199f))
        assertEquals(3, m.indexAt(999f))
    }

    @Test fun lensStaysInsideBar() {
        val half = m.lensWidth / 2f
        assertEquals(half, m.clampCenter(-500f), 0.001f)
        assertEquals(400f - half, m.clampCenter(5000f), 0.001f)
        assertEquals(222f, m.clampCenter(222f), 0f)
    }

    @Test fun previewHasHysteresisAtBoundary() {
        // Подсвечена вкладка 1; палец дрожит у границы 200 — подсветка держится.
        assertEquals(1, m.previewFor(205f, 1))
        assertEquals(1, m.previewFor(211f, 1))
        // Ушёл дальше запаса — переключается.
        assertEquals(2, m.previewFor(213f, 1))
        // Без текущей — просто ближайшая.
        assertEquals(2, m.previewFor(205f, -1))
    }

    @Test fun verticalExit() {
        assertFalse(m.isVerticalExit(-50f, 60f))
        assertTrue(m.isVerticalExit(-61f, 60f))
        assertFalse(m.isVerticalExit(110f, 60f))
        assertTrue(m.isVerticalExit(121f, 60f))
    }

    @Test fun stretchIsBoundedAndSigned() {
        assertEquals(0f, LiquidBarMath.stretchFor(0f, 100f), 0f)
        assertTrue(LiquidBarMath.stretchFor(450f, 100f) in 0.4f..0.6f)
        assertEquals(1f, LiquidBarMath.stretchFor(1e6f, 100f), 0f)
        assertEquals(-1f, LiquidBarMath.stretchFor(-1e6f, 100f), 0f)
    }

    @Test fun proximity() {
        assertEquals(1f, LiquidBarMath.proximity(150f, 150f, 100f), 0f)
        assertEquals(0.5f, LiquidBarMath.proximity(150f, 200f, 100f), 0.001f)
        assertEquals(0f, LiquidBarMath.proximity(150f, 400f, 100f), 0f)
    }
}
