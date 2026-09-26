package com.nox.offline.player

import com.nox.offline.data.db.CollectionItemEntity
import com.nox.offline.library.Segments

/** Что играть: целый файл или интервал (виртуальная серия/глава) внутри него. */
data class Playable(val mediaId: Long, val chapterId: Long = 0) {
    val isSegment: Boolean get() = chapterId > 0
}

/**
 * Очередь автоперехода: выбранная коллекция, сезон или серии одного файла.
 * Следующим может быть только уже скачанный элемент — сеть здесь никогда
 * не используется и ничего не скачивается само.
 */
data class PlayContext(
    val title: String,
    val items: List<Playable>,
    val index: Int,
    /** Откуда очередь: коллекция (id > 0) или серии одного файла (0). */
    val collectionId: Long = 0,
    val season: Int = -1,
) {
    val current: Playable? get() = items.getOrNull(index)
    val next: Playable? get() = items.getOrNull(index + 1)
    val previous: Playable? get() = items.getOrNull(index - 1)
    fun moveTo(p: Playable): PlayContext = items.indexOf(p).let { if (it >= 0) copy(index = it) else this }

    companion object {
        /**
         * Очередь коллекции. У сериала — только серии того же сезона, что и
         * выбранная; ожидающие загрузки и недоступные элементы пропускаются.
         */
        fun ofCollection(collectionId: Long, title: String, items: List<CollectionItemEntity>, start: Playable,
                         series: Boolean): PlayContext? {
            val startItem = items.firstOrNull { it.mediaId == start.mediaId && it.chapterId == start.chapterId }
            val pool = items.filter { it.isLocal }
                .filter { !series || startItem == null || it.season == startItem.season }
                .sortedWith(compareBy<CollectionItemEntity> { it.season }.thenBy { it.position }.thenBy { it.id })
            val list = pool.map { Playable(it.mediaId, it.chapterId) }.distinct()
            val idx = list.indexOf(start)
            if (idx < 0) return null
            return PlayContext(title, list, idx, collectionId, if (series) startItem?.season ?: -1 else -1)
        }

        /** Марафон одного файла: его виртуальные серии по порядку. */
        fun ofSegments(title: String, mediaId: Long, segments: List<Segments.Segment>, start: Playable): PlayContext? {
            val eps = segments.filter { it.kind == com.nox.offline.data.db.ChapterKind.EPISODE }.sortedBy { it.startMs }
            if (eps.isEmpty()) return null
            val list = eps.map { Playable(mediaId, it.id) }
            val idx = list.indexOf(start)
            return if (idx < 0) null else PlayContext(title, list, idx)
        }
    }
}

/** Решение после окончания элемента — чистая функция для JVM-тестов. */
object EndRule {
    sealed class Action {
        /** Таймер сна «после серии/файла» сработал: пауза, без перехода. */
        object SleepStop : Action()
        data class Offer(val next: Playable) : Action()
        data class Play(val next: Playable) : Action()
        /** Следующего нет (или автопереход выключен): показать действия, сеть не трогать. */
        object Nothing : Action()
    }

    fun decide(context: PlayContext?, finished: Playable, sleep: SleepTimer.Mode?, autoNext: com.nox.offline.settings.AutoNext): Action {
        val next = context?.takeIf { it.current == finished }?.next
        when (sleep) {
            is SleepTimer.Mode.AfterSegment -> return Action.SleepStop
            is SleepTimer.Mode.AfterFile -> if (next == null || next.mediaId != finished.mediaId) return Action.SleepStop
            else -> Unit
        }
        if (next == null) return Action.Nothing
        // Следующая виртуальная серия того же файла при «после файла» — продолжаем без вопроса,
        // как и при обычном автопереходе.
        return when (autoNext) {
            com.nox.offline.settings.AutoNext.OFF -> Action.Nothing
            com.nox.offline.settings.AutoNext.AUTO -> Action.Play(next)
            com.nox.offline.settings.AutoNext.ASK -> Action.Offer(next)
        }
    }
}
