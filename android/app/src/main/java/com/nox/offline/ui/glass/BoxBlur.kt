package com.nox.offline.ui.glass

import kotlin.random.Random

/**
 * Программное размытие ARGB-пикселей: три прохода скользящего среднего
 * по строкам и столбцам ≈ гауссово размытие. Не зависит от Android —
 * работает на любом API (включая 26) и проверяется JVM-тестом.
 *
 * Используется один раз на картинку фона, а не на каждый кадр.
 */
object BoxBlur {
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3) {
        if (radius < 1 || width <= 1 || height <= 1) return
        val tmp = IntArray(pixels.size)
        repeat(passes) {
            horizontal(pixels, tmp, width, height, radius)
            vertical(tmp, pixels, width, height, radius)
        }
    }

    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /**
     * Лёгкий шум ±[amplitude] уровня на канал. Убирает полосы в тёмных
     * градиентах (8 бит на канал на почти чёрном дают ступени), не меняя
     * картинку на глаз. Детерминирован по [seed].
     */
    fun dither(pixels: IntArray, amplitude: Int = 2, seed: Int = 7) {
        val rnd = Random(seed)
        for (i in pixels.indices) {
            val p = pixels[i]
            val n = rnd.nextInt(-amplitude, amplitude + 1)
            val r = (((p shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((p and 0xFF) + n).coerceIn(0, 255)
            pixels[i] = (p and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Средняя яркость 0..1 по сетке выборки. */
    fun averageLuminance(pixels: IntArray, width: Int, height: Int, step: Int = 8): Float {
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = pixels[y * width + x]
                val r = ((p shr 16) and 0xFF) / 255.0
                val g = ((p shr 8) and 0xFF) / 255.0
                val b = (p and 0xFF) / 255.0
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }
}
