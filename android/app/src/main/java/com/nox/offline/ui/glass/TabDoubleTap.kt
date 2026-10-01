package com.nox.offline.ui.glass

/**
 * Двойное нажатие на одну и ту же вкладку нижней панели.
 *
 * Первое нажатие работает как обычно и сразу (выбирает вкладку), второе —
 * дополнительное действие «В начало раздела». Ожидания перед обычным
 * переключением нет: распознаётся только второе нажатие.
 *
 * Двойным не считается: нажатия на разные вкладки, перетаскивание линзы,
 * долгое нажатие, отмена касания системой и касание несколькими пальцами —
 * всё это обрывает серию через [reset]. Класс не знает об Android: времена
 * и пороги приходят снаружи (из ViewConfiguration), проверяется JVM-тестами.
 */
class TabDoubleTap(
    /** Наибольший промежуток между отпусканием первого и касанием второго (doubleTapTimeout). */
    private val timeoutMs: Long,
    /** Меньший промежуток — дребезг, а не второе нажатие (doubleTapMinTime). */
    private val minGapMs: Long,
    /** Дольше этого палец лежал — долгое нажатие, а не нажатие (longPressTimeout). */
    private val longPressMs: Long,
) {
    private var lastIndex = -1
    private var lastUpMs = 0L

    /**
     * Короткое нажатие на вкладку [index] закончилось. Возвращает true,
     * если это второе нажатие двойного; серия при этом обнуляется, так что
     * третье нажатие подряд снова считается первым.
     */
    fun onTap(index: Int, downMs: Long, upMs: Long): Boolean {
        if (index < 0 || upMs - downMs > longPressMs) {
            reset()
            return false
        }
        val gap = downMs - lastUpMs
        val double = index == lastIndex && gap in minGapMs..timeoutMs
        if (double) {
            reset()
        } else {
            lastIndex = index
            lastUpMs = upMs
        }
        return double
    }

    /** Не нажатие (перетаскивание, отмена, мультитач, уход пальца): серия обрывается. */
    fun reset() {
        lastIndex = -1
        lastUpMs = 0L
    }
}
