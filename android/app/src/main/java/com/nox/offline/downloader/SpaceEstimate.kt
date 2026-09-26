package com.nox.offline.downloader

/**
 * Оценка места на пике загрузки — до старта, а не после ошибки.
 *
 * Пик внутри NOX (папка загрузок) для раздельных дорожек: обе дорожки
 * (.part) + собранный файл во время объединения — почти вдвое больше
 * итога. Если готовые видео сохраняются в другую папку (другой том),
 * там тоже нужно место под итоговый файл. Уже стоящие в очереди задания
 * тоже займут своё. Размер «примерно» (filesize_approx) — не гарантия,
 * поэтому такая оценка помечается как неточная и получает запас.
 */
object SpaceEstimate {
    const val MARGIN = 64L * 1024 * 1024

    data class Need(
        /** Сколько нужно свободно во внутренней папке NOX. */
        val internal: Long,
        /** Сколько нужно в выбранной папке для готовых видео (0 — она же внутренняя). */
        val external: Long,
        /** Размер известен только приблизительно или неизвестен. */
        val uncertain: Boolean,
    )

    data class Check(val ok: Boolean, val message: String)

    /**
     * [sizeBytes] — размер итога (0 — неизвестен), [alreadyOnDisk] — уже
     * скачанные части этого задания, [queuedRemaining] — сколько ещё докачают
     * задания, стоящие раньше.
     */
    fun need(sizeBytes: Long, approx: Boolean, needsMerge: Boolean, alreadyOnDisk: Long, queuedRemaining: Long,
             externalTarget: Boolean): Need {
        val unknown = sizeBytes <= 0
        val size = if (unknown) 0L else if (approx) sizeBytes + sizeBytes / 10 else sizeBytes
        val tracks = (size - alreadyOnDisk).coerceAtLeast(0)
        val peakInternal = tracks + (if (needsMerge) size else 0L) + queuedRemaining.coerceAtLeast(0) + MARGIN
        return Need(internal = peakInternal, external = if (externalTarget && size > 0) size + MARGIN else 0L,
            uncertain = unknown || approx)
    }

    fun check(n: Need, freeInternal: Long, freeExternal: Long, fmt: (Long) -> String): Check {
        if (freeInternal in 0 until n.internal) {
            return Check(false, "Недостаточно места в памяти телефона: нужно ≈ ${fmt(n.internal)}, свободно ${fmt(freeInternal)}")
        }
        if (n.external > 0 && freeExternal in 0 until n.external) {
            return Check(false, "Недостаточно места в папке для видео: нужно ≈ ${fmt(n.external)}, свободно ${fmt(freeExternal)}")
        }
        return Check(true, if (n.uncertain) "Размер известен приблизительно — место проверено с запасом" else "")
    }
}
