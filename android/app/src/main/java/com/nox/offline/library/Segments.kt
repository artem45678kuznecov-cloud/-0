package com.nox.offline.library

import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.ChapterKind

/**
 * Разметка файла: главы и виртуальные серии как интервалы внутри ОДНОГО
 * файла. Чистые функции — их проверяют JVM-тесты.
 *
 * Все времена — Long в миллисекундах: многосуточный файл не переполняется.
 */
object Segments {
    /** Интервал с вычисленным концом. */
    data class Segment(
        val id: Long,
        val mediaId: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val kind: String,
        val origin: String,
    ) {
        val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0)
        val isEpisode: Boolean get() = kind == ChapterKind.EPISODE

        fun contains(absMs: Long) = absMs in startMs until endMs

        /** Абсолютное время файла → время внутри серии (с ограничением границами). */
        fun toRelative(absMs: Long): Long = (absMs - startMs).coerceIn(0, lengthMs)

        /** Время внутри серии → абсолютное время файла. */
        fun toAbsolute(relMs: Long): Long = startMs + relMs.coerceIn(0, lengthMs)
    }

    /** Минимальная длина главы: короче — почти наверняка случайное касание. */
    const val MIN_LENGTH_MS = 1_000L

    /**
     * Главы файла по порядку с вычисленными концами. endMs = -1 означает
     * «до следующей главы или до конца файла». Неверные записи (начало за
     * концом файла, конец раньше начала) отбрасываются, а не «чинятся» молча.
     */
    fun resolve(chapters: List<ChapterEntity>, durationMs: Long): List<Segment> {
        val sorted = chapters.filter { it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
            .sortedWith(compareBy<ChapterEntity> { it.startMs }.thenBy { it.id })
        val out = ArrayList<Segment>(sorted.size)
        for ((i, c) in sorted.withIndex()) {
            // «До следующей» — следующая глава того же вида: серии не обрываются главами оглавления.
            val nextStart = sorted.drop(i + 1).firstOrNull { it.kind == c.kind && it.startMs > c.startMs }?.startMs
            val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
            val end = when {
                c.endMs > c.startMs -> minOf(c.endMs, limit)
                nextStart != null -> nextStart
                durationMs > 0 -> durationMs
                else -> continue
            }
            if (end - c.startMs < MIN_LENGTH_MS) continue
            out.add(Segment(c.id, c.mediaId, c.title, c.startMs, end, c.kind, c.origin))
        }
        return out
    }

    /** Понятная ошибка границ или null, если границы годятся. */
    fun validate(startMs: Long, endMs: Long, durationMs: Long): String? = when {
        startMs < 0 -> "Начало не может быть отрицательным"
        durationMs > 0 && startMs >= durationMs -> "Начало за пределами файла"
        endMs != -1L && endMs <= startMs -> "Конец должен быть позже начала"
        endMs != -1L && endMs - startMs < MIN_LENGTH_MS -> "Слишком короткий отрезок"
        durationMs > 0 && endMs > durationMs -> "Конец за пределами файла"
        else -> null
    }

    /** Глава, в которой сейчас позиция (для «после текущей главы» и подсветки). */
    fun at(segments: List<Segment>, absMs: Long, kind: String? = null): Segment? =
        segments.lastOrNull { (kind == null || it.kind == kind) && it.contains(absMs) }

    /**
     * Позиция внутри серии после исправления её границ: абсолютное место в
     * файле сохраняется, если оно всё ещё внутри; иначе — ближайшая граница.
     */
    fun reconcile(oldRelMs: Long, old: Segment, new: Segment): Long {
        val abs = old.toAbsolute(oldRelMs)
        return new.toRelative(abs)
    }

    /** Следующая серия того же вида после текущей. */
    fun next(segments: List<Segment>, current: Segment): Segment? =
        segments.filter { it.kind == current.kind && it.startMs > current.startMs }.minByOrNull { it.startMs }

    /** «1:02:03», «25:07», для суток и больше — «1 д 02:03:04». */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val d = total / 86_400
        val h = (total % 86_400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            d > 0 -> "%d д %02d:%02d:%02d".format(d, h, m, s)
            h > 0 -> "%d:%02d:%02d".format(h, m, s)
            else -> "%d:%02d".format(m, s)
        }
    }

    /** Разбор «1:02:03», «25:07», «90» (секунды) → мс; null — не разобрать. */
    fun parseClock(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':').map { it.trim().toLongOrNull() ?: return null }
        if (parts.any { it < 0 } || parts.size > 4) return null
        var sec = 0L
        for (p in parts) sec = sec * 60 + p
        if (parts.size == 4) {
            // д:ч:м:с
            sec = parts[0] * 86_400 + parts[1] * 3600 + parts[2] * 60 + parts[3]
        }
        return sec * 1000
    }
}
