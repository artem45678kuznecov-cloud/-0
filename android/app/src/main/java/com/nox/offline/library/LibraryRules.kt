package com.nox.offline.library

import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity

/** Чистые правила медиатеки — проверяются JVM-тестами. */
object LibraryRules {
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
