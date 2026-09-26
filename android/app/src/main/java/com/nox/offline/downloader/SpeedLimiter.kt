package com.nox.offline.downloader

import kotlinx.coroutines.delay

/**
 * Общий предел скорости ВСЕХ загрузок NOX (а не каждой дорожки отдельно).
 *
 * Алгоритм «теоретического времени прибытия» (GCRA): каждый принятый
 * кусок сдвигает общую отметку времени на bytes / rate; кто пришёл раньше
 * отметки — ждёт разницу. Ожидание — обычный delay корутины, поэтому
 * пауза и отмена срабатывают сразу, а интерфейс не блокируется.
 * Предел 0 — без ограничения: никаких ожиданий и никаких накладных расходов.
 * Обновление APK этим пределом не ограничивается — оно качается не здесь.
 */
class SpeedLimiter(private val bytesPerSecond: () -> Long) {
    private val lock = Any()
    private var tat = 0L          // «время прибытия», мс
    private var lastRate = 0L

    /** Сколько миллисекунд подождать перед приёмом следующих [bytes]. Чистая функция времени. */
    fun reserve(bytes: Int, now: Long): Long {
        val rate = bytesPerSecond()
        if (rate <= 0 || bytes <= 0) {
            synchronized(lock) { lastRate = 0 }
            return 0
        }
        synchronized(lock) {
            if (rate != lastRate) { tat = now; lastRate = rate }
            // Небольшой запас (BURST_MS), чтобы короткие паузы сети не превращались в «долги».
            val start = maxOf(tat, now - BURST_MS)
            tat = start + bytes * 1000L / rate
            return (tat - now - BURST_MS).coerceAtLeast(0)
        }
    }

    suspend fun acquire(bytes: Int) {
        val wait = reserve(bytes, System.currentTimeMillis())
        if (wait > 0) delay(wait.coerceAtMost(MAX_WAIT_MS))
    }

    companion object {
        const val BURST_MS = 250L
        const val MAX_WAIT_MS = 5_000L
    }
}
