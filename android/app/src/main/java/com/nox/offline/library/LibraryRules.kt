package com.nox.offline.library

import com.nox.offline.data.db.CollectionItemEntity
import com.nox.offline.data.db.CollectionSummary
import com.nox.offline.data.db.CollectionType
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity

/** Чистые правила медиатеки — проверяются JVM-тестами. */
object LibraryRules {
    /** «Рекомендуемая коллекция» — из библиотеки самого пользователя, с простым объяснением. */
    data class Featured(val collectionId: Long, val reason: String)

    fun featured(
        summaries: List<CollectionSummary>,
        items: List<CollectionItemEntity>,
        playback: Map<Long, PlaybackEntity>,
    ): Featured? {
        val candidates = summaries.filter { it.local > 0 && it.type != CollectionType.CATEGORY }
        if (candidates.isEmpty()) return null
        val byCollection = items.groupBy { it.collectionId }
        fun lastInProgress(id: Long): Long = byCollection[id].orEmpty()
            .mapNotNull { playback[it.mediaId] }
            .filter { it.inProgress }
            .maxOfOrNull { it.updatedAt } ?: 0L
        candidates.filter { it.pinned }.maxByOrNull { lastInProgress(it.id) }
            ?.takeIf { lastInProgress(it.id) > 0 }
            ?.let { return Featured(it.id, "Закреплена, и вы её не досмотрели") }
        candidates.filter { !it.entity.isSystem }.maxByOrNull { lastInProgress(it.id) }
            ?.takeIf { lastInProgress(it.id) > 0 }
            ?.let { return Featured(it.id, "Вы остановились на ней в последний раз") }
        candidates.firstOrNull { it.pinned && !it.entity.isSystem }?.let { return Featured(it.id, "Закреплённая коллекция") }
        candidates.filter { !it.entity.isSystem }.maxByOrNull { it.updatedAt }
            ?.let { return Featured(it.id, "Последняя изменённая коллекция") }
        return null
    }

    /** Кандидат на очистку и его объём. */
    data class CleanupCandidate(val media: MediaEntity, val sizeBytes: Long)

    /**
     * Просмотренные видео, которые можно предложить удалить. Никогда не
     * предлагаются: защищённые, открытые сейчас в плеере, недосмотренные.
     * Удаляет только пользователь после подтверждения.
     */
    fun cleanup(media: List<MediaEntity>, playback: Map<Long, PlaybackEntity>, playingMediaId: Long): List<CleanupCandidate> =
        media.asSequence()
            .filter { !it.protectedFromCleanup && it.id != playingMediaId }
            .filter { playback[it.id]?.completed == true }
            .map { CleanupCandidate(it, it.sizeBytes) }
            .sortedByDescending { it.sizeBytes }
            .toList()

    /** Название категории по умолчанию. */
    val defaultCategories = listOf("anime" to "Аниме", "movies" to "Фильмы", "series" to "Сериалы")
}
