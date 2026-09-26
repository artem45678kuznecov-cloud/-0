package com.nox.offline.library

import androidx.room.withTransaction
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.BookmarkEntity
import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.NoxDatabase
import com.nox.offline.data.db.SegmentProgressEntity

/**
 * Разметка внутри файла: главы, виртуальные серии, закладки и их позиции.
 * Файл остаётся одним файлом: ничего не режется и не копируется. Удаление
 * разметки не трогает видео.
 */
class MarkupRepository(private val db: NoxDatabase) {
    private val dao get() = db.chapters()

    private suspend fun durationOf(mediaId: Long): Long =
        (db.media().get(mediaId)?.durationSec ?: 0L) * 1000L

    /** Начало главы или серии на текущей позиции. Конец — до следующей того же вида. */
    suspend fun addAt(mediaId: Long, positionMs: Long, title: String, kind: String, durationMs: Long = 0): Result<Long> {
        val dur = if (durationMs > 0) durationMs else durationOf(mediaId)
        Segments.validate(positionMs, -1, dur)?.let { return Result.failure(IllegalArgumentException(it)) }
        val existing = dao.forMedia(mediaId).filter { it.kind == kind }
        if (existing.any { kotlin.math.abs(it.startMs - positionMs) < Segments.MIN_LENGTH_MS }) {
            return Result.failure(IllegalArgumentException("Здесь уже есть начало"))
        }
        val n = existing.size + 1
        val t = title.trim().ifBlank { if (kind == ChapterKind.EPISODE) "Серия $n" else "Глава $n" }
        val id = dao.insert(ChapterEntity(mediaId = mediaId, title = t, startMs = positionMs, kind = kind,
            createdAt = System.currentTimeMillis()))
        NoxLog.event("chapter-added", "media" to mediaId, "kind" to kind)
        return Result.success(id)
    }

    /**
     * Исправление названия и границ. Позиция виртуальной серии сохраняет
     * своё абсолютное место в файле, если оно ещё внутри новых границ.
     */
    suspend fun edit(id: Long, title: String, startMs: Long, endMs: Long, durationMs: Long = 0): Result<Unit> {
        val c = dao.get(id) ?: return Result.failure(IllegalStateException("Глава не найдена"))
        val dur = if (durationMs > 0) durationMs else durationOf(c.mediaId)
        Segments.validate(startMs, endMs, dur)?.let { return Result.failure(IllegalArgumentException(it)) }
        db.withTransaction {
            val all = dao.forMedia(c.mediaId)
            val oldSeg = Segments.resolve(all, dur).firstOrNull { it.id == id }
            val updated = c.copy(title = title.trim().ifBlank { c.title }, startMs = startMs, endMs = endMs)
            dao.update(updated)
            val newSeg = Segments.resolve(all.map { if (it.id == id) updated else it }, dur).firstOrNull { it.id == id }
            val p = dao.progress(id)
            if (p != null && oldSeg != null && newSeg != null) {
                dao.upsertProgress(p.copy(positionMs = Segments.reconcile(p.positionMs, oldSeg, newSeg),
                    updatedAt = System.currentTimeMillis()))
            }
        }
        return Result.success(Unit)
    }

    /** Объединить главу со следующей того же вида: остаётся одна, файл не меняется. */
    suspend fun mergeWithNext(id: Long): Boolean = db.withTransaction {
        val c = dao.get(id) ?: return@withTransaction false
        val next = dao.forMedia(c.mediaId).filter { it.kind == c.kind && it.startMs > c.startMs }.minByOrNull { it.startMs }
            ?: return@withTransaction false
        dao.update(c.copy(endMs = next.endMs))
        dao.delete(next.id)
        dao.deleteProgress(next.id)
        db.collections().deleteItemsForChapter(next.id)
        true
    }

    /** Удалить разметку (главу/серию) — не файл. */
    suspend fun delete(id: Long) = db.withTransaction {
        dao.delete(id)
        dao.deleteProgress(id)
        db.collections().deleteItemsForChapter(id)
    }

    /** Превратить главы в виртуальные серии (или обратно). */
    suspend fun setKind(id: Long, kind: String) {
        val c = dao.get(id) ?: return
        dao.update(c.copy(kind = kind))
    }

    /** Главы, которые действительно передал источник (yt-dlp chapters). */
    suspend fun importSource(mediaId: Long, chapters: List<Triple<String, Long, Long>>): Int {
        if (chapters.isEmpty()) return 0
        val dur = durationOf(mediaId)
        val existing = dao.forMedia(mediaId).filter { it.origin == "source" }
        if (existing.isNotEmpty()) return 0
        var n = 0
        for ((title, start, end) in chapters) {
            if (Segments.validate(start, end, dur) != null) continue
            dao.insert(ChapterEntity(mediaId = mediaId, title = title.ifBlank { "Глава ${n + 1}" }, startMs = start, endMs = end,
                origin = "source", createdAt = System.currentTimeMillis()))
            n++
        }
        return n
    }

    // ---------------- позиции серий ----------------

    suspend fun progress(chapterId: Long) = dao.progress(chapterId)

    suspend fun saveProgress(chapterId: Long, relMs: Long, completed: Boolean) {
        val prev = dao.progress(chapterId)
        // Досмотренная серия остаётся досмотренной, пока пользователь сам не снимет отметку.
        dao.upsertProgress(SegmentProgressEntity(chapterId, relMs.coerceAtLeast(0), completed || prev?.completed == true,
            System.currentTimeMillis()))
    }

    suspend fun setWatched(chapterId: Long, watched: Boolean) {
        val p = dao.progress(chapterId)
        dao.upsertProgress(SegmentProgressEntity(chapterId, if (watched) p?.positionMs ?: 0 else 0, watched, System.currentTimeMillis()))
    }

    // ---------------- закладки ----------------

    suspend fun addBookmark(mediaId: Long, positionMs: Long, title: String, note: String): Long =
        dao.insertBookmark(BookmarkEntity(mediaId = mediaId, positionMs = positionMs.coerceAtLeast(0), title = title.trim(),
            note = note.trim(), createdAt = System.currentTimeMillis()))

    suspend fun updateBookmark(b: BookmarkEntity) = dao.updateBookmark(b)
    suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    /** Видео удалено: вся его разметка уходит вместе с ним. */
    suspend fun onMediaDeleted(mediaId: Long) = db.withTransaction {
        for (c in dao.forMedia(mediaId)) dao.deleteProgress(c.id)
        dao.deleteForMedia(mediaId)
        dao.deleteBookmarksFor(mediaId)
    }
}
