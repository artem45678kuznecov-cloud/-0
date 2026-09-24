package com.nox.offline.downloader

/**
 * Правило слотов NOX: не больше [DownloadCoordinator.MAX_CONCURRENT]
 * передач сразу, остальные ждут в порядке добавления. Чистая функция —
 * её проверяет JVM-тест без Android и без базы.
 */
object QueuePolicy {
    /**
     * Какие задания из очереди запускать прямо сейчас.
     *
     * @param queuedInOrder  идентификаторы в состоянии QUEUED, по времени добавления
     * @param active         идентификаторы уже идущих передач
     * @param blocked        то, что трогать нельзя (запрошена пауза/отмена)
     */
    fun pick(
        queuedInOrder: List<Long>,
        active: Set<Long>,
        blocked: Set<Long> = emptySet(),
        max: Int = DownloadCoordinator.MAX_CONCURRENT,
    ): List<Long> {
        val free = (max - active.size).coerceAtLeast(0)
        if (free == 0) return emptyList()
        val out = ArrayList<Long>(free)
        for (id in queuedInOrder) {
            if (out.size >= free) break
            if (id in active || id in blocked) continue
            out.add(id)
        }
        return out
    }
}
