package com.nox.offline.ui.glass

import kotlin.math.abs
import kotlin.math.floor

/**
 * Геометрия нижней панели и её стеклянной линзы. Чистые функции без
 * Compose: их проверяет JVM-тест, а панель только подставляет размеры.
 *
 * Все координаты — в пикселях внутри области вкладок (без внутренних
 * отступов панели): 0 — левый край первой вкладки, [count] × [slot] —
 * правый край последней.
 */
internal class LiquidBarMath(val slot: Float, val count: Int) {
    /** Ширина линзы в покое: чуть уже вкладки, чтобы у краёв панели оставался воздух. */
    val lensWidth: Float get() = slot * LENS_WIDTH

    fun centerOf(index: Int): Float = (index.coerceIn(0, count - 1) + 0.5f) * slot

    /** Центр линзы не выходит за панель: линза целиком остаётся на ней. */
    fun clampCenter(x: Float): Float {
        if (count <= 0 || slot <= 0f) return 0f
        val half = lensWidth / 2f
        return x.coerceIn(half, count * slot - half)
    }

    /** Вкладка, в чьих границах находится точка. */
    fun indexAt(x: Float): Int {
        if (count <= 0 || slot <= 0f) return 0
        return floor(x / slot).toInt().coerceIn(0, count - 1)
    }

    /**
     * Предварительный выбор под пальцем. Уже подсвеченная вкладка держится,
     * пока центр линзы не уйдёт за её границу дальше, чем на [HYSTERESIS]
     * ширины вкладки: дрожание пальца у границы не дёргает подсветку и
     * тактильный отклик.
     */
    fun previewFor(center: Float, current: Int): Int {
        val nearest = indexAt(center)
        if (current !in 0 until count || nearest == current) return nearest
        val lo = current * slot - HYSTERESIS * slot
        val hi = (current + 1) * slot + HYSTERESIS * slot
        return if (center in lo..hi) current else nearest
    }

    /**
     * Палец ушёл по вертикали далеко от панели: выбор отменяется.
     * [y] — от верхнего края панели, [height] — высота панели.
     */
    fun isVerticalExit(y: Float, height: Float): Boolean =
        y < -height * EXIT_ABOVE || y > height * EXIT_BELOW

    companion object {
        const val LENS_WIDTH = 0.94f
        const val HYSTERESIS = 0.12f
        /** Выше панели — на её высоту: над ней уже содержимое экрана. */
        const val EXIT_ABOVE = 1.0f
        /** Ниже — с запасом: там системная область жестов. */
        const val EXIT_BELOW = 2.0f

        /**
         * Растяжение линзы по скорости: 0 — покой, до ±1 — быстрое движение.
         * Знак — направление, используется для блика и ведущего края.
         */
        fun stretchFor(velocityPxPerSec: Float, slot: Float): Float {
            if (slot <= 0f) return 0f
            return (velocityPxPerSec / (slot * 9f)).coerceIn(-1f, 1f)
        }

        /** Сглаживание, чтобы форма не дрожала от неравномерных событий касания. */
        fun smooth(previous: Float, next: Float, k: Float = 0.35f): Float =
            previous + (next - previous) * k

        /** Близость вкладки к линзе: 1 — под центром линзы, 0 — дальше ширины вкладки. */
        fun proximity(itemCenter: Float, lensCenter: Float, slot: Float): Float {
            if (slot <= 0f) return 0f
            return (1f - abs(itemCenter - lensCenter) / slot).coerceIn(0f, 1f)
        }
    }
}
