@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nox.offline.NoxApp
import com.nox.offline.core.AppEvents
import com.nox.offline.data.db.BookmarkEntity
import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.CollectionEntity
import com.nox.offline.data.db.CollectionItemEntity
import com.nox.offline.data.db.CollectionSummary
import com.nox.offline.data.db.CollectionType
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.data.db.SeasonEntity
import com.nox.offline.data.db.SegmentProgressEntity
import com.nox.offline.data.db.SubtitleEntity
import com.nox.offline.library.LibraryRules
import com.nox.offline.library.Segments
import com.nox.offline.player.PlayContext
import com.nox.offline.player.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Фильтры главного экрана: только по локальной базе, без сети. */
data class CollectionFilter(
    val type: String = ALL,
    val pinnedOnly: Boolean = false,
    val sort: Sort = Sort.MANUAL,
) {
    enum class Sort(val label: String) { MANUAL("Мой порядок"), TITLE("По названию"), RECENT("Недавно изменённые"), SIZE("Больше видео") }

    val active: Boolean get() = type != ALL || pinnedOnly || sort != Sort.MANUAL

    companion object {
        const val ALL = "all"
    }
}

/** Коллекция для отображения: сводка + обложка. */
data class CollectionCard(val summary: CollectionSummary, val cover: String) {
    val id: Long get() = summary.id
    val title: String get() = summary.title
    val countLabel: String get() = videosLabel(summary.local) + if (summary.items > summary.local) " · ещё ${summary.items - summary.local} не скачано" else ""
}

/** «12 видео», «1 видео», «3 видео». */
fun videosLabel(n: Int): String = "$n видео"

/** Всё, что показывает главный экран. */
data class HomeData(
    val featured: CollectionCard?,
    val featuredReason: String,
    val categories: List<CollectionCard>,
    val pinned: List<CollectionCard>,
    val albums: List<CollectionCard>,
    val totalMedia: Int,
    /** Видео, подходящие под поиск (если поиск задан). */
    val matchedMedia: List<MediaEntity>,
)

/** Экран коллекции / сериала. */
data class CollectionDetail(
    val collection: CollectionEntity,
    val items: List<Entry>,
    val seasons: List<SeasonEntity>,
) {
    /** Элемент: целый файл, виртуальная серия или ожидающий загрузки. */
    data class Entry(
        val item: CollectionItemEntity,
        val media: MediaEntity?,
        val chapter: Segments.Segment?,
        val playback: PlaybackEntity?,
        val progress: SegmentProgressEntity?,
    ) {
        val title: String get() = item.title.ifBlank { chapter?.title?.ifBlank { null } ?: media?.title ?: "Не скачано" }
        val watched: Boolean get() = if (chapter != null) progress?.completed == true else playback?.completed == true
        /** Доля просмотра 0..1. */
        val fraction: Float
            get() = when {
                watched -> 1f
                chapter != null -> progress?.let { if (chapter.lengthMs > 0) it.positionMs.toFloat() / chapter.lengthMs else 0f } ?: 0f
                playback != null && playback.durationMs > 0 -> playback.positionMs.toFloat() / playback.durationMs
                else -> 0f
            }
        val inProgress: Boolean get() = !watched && fraction > 0.01f
        val state: String
            get() = when {
                media == null && item.unavailableReason.isNotBlank() -> "Недоступно: ${item.unavailableReason}"
                media == null && item.sourceUrl.isNotBlank() -> "Не скачано"
                media == null -> "Файл удалён"
                watched -> "Просмотрено"
                inProgress -> "Остановились на ${Segments.clock(positionMs)}"
                else -> ""
            }
        val positionMs: Long get() = if (chapter != null) progress?.positionMs ?: 0 else playback?.positionMs ?: 0
        val durationMs: Long get() = chapter?.lengthMs ?: (media?.durationSec?.times(1000) ?: playback?.durationMs ?: 0)
        val playable: Playable? get() = media?.let { Playable(it.id, chapter?.id ?: 0) }
    }

    val isSeries: Boolean get() = collection.isSeries

    /** Название для показа: в сериале — без названия сериала и метки номера (своё название серии не трогается). */
    fun shownTitle(e: Entry): String =
        if (isSeries && e.item.title.isBlank()) com.nox.offline.library.SeriesNumbering.episodeName(e.title, collection.title) else e.title
    val local: List<Entry> get() = items.filter { it.media != null }
    val missing: List<Entry> get() = items.filter { it.media == null && it.item.sourceUrl.isNotBlank() && it.item.unavailableReason.isBlank() }

    /** «Продолжить»: серия, на которой остановились, иначе первая непросмотренная. */
    val continueEntry: Entry?
        get() = local.filter { it.inProgress }.maxByOrNull { maxOf(it.playback?.updatedAt ?: 0, it.progress?.updatedAt ?: 0) }
            ?: local.firstOrNull { !it.watched }

    fun seasonTitle(n: Int): String {
        val t = seasons.firstOrNull { it.number == n }?.title.orEmpty()
        return when {
            n <= 0 -> if (t.isNotBlank()) t else "Без сезона"
            t.isNotBlank() -> "Сезон $n — $t"
            else -> "Сезон $n"
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val nox = NoxApp.get(app)
    private val repo = nox.library
    private val db = nox.db

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(CollectionFilter())

    private val summaries = repo.summaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val media = db.media().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val playback = db.playback().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val allItems = db.collections().observeAllItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Прямо из Room (без пустых начальных значений): пока база не ответила, экран не показывает «пусто».
    val home: StateFlow<HomeData?> = combine(repo.summaries, db.media().observeAll(), db.playback().observeAll(),
        db.collections().observeAllItems(), combine(query, filter) { q, f -> q to f }) {
            sums, med, pb, items, (q, f) ->
        val byId = med.associateBy { it.id }
        fun card(s: CollectionSummary) = CollectionCard(s, coverOf(s, byId))
        val pbMap = pb.associateBy { it.mediaId }
        val needle = q.trim().lowercase()
        val visible = sums.filter { s ->
            (needle.isBlank() || s.title.lowercase().contains(needle) || s.tags.lowercase().contains(needle) ||
                s.description.lowercase().contains(needle)) &&
                (!f.pinnedOnly || s.pinned) &&
                (f.type == CollectionFilter.ALL || s.type == f.type ||
                    (f.type == CollectionType.CATEGORY && s.type in CollectionType.system))
        }.let { list ->
            when (f.sort) {
                CollectionFilter.Sort.MANUAL -> list
                CollectionFilter.Sort.TITLE -> list.sortedBy { it.title.lowercase() }
                CollectionFilter.Sort.RECENT -> list.sortedByDescending { it.updatedAt }
                CollectionFilter.Sort.SIZE -> list.sortedByDescending { it.local }
            }
        }
        val featured = LibraryRules.featured(sums, items, pbMap)
        HomeData(
            featured = featured?.let { fx -> sums.firstOrNull { it.id == fx.collectionId }?.let(::card) },
            featuredReason = featured?.reason.orEmpty(),
            categories = visible.filter { it.type == CollectionType.CATEGORY || it.type in CollectionType.system }
                .sortedBy { if (it.type in CollectionType.system) 1 else 0 }.map(::card),
            pinned = visible.filter { it.pinned && it.type != CollectionType.CATEGORY && it.type !in CollectionType.system }.map(::card),
            albums = visible.filter { !it.pinned && (it.type == CollectionType.ALBUM || it.type == CollectionType.SERIES) }.map(::card),
            totalMedia = med.size,
            matchedMedia = if (needle.isBlank()) emptyList() else med.filter { it.title.lowercase().contains(needle) }.take(30),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun coverOf(s: CollectionSummary, byId: Map<Long, MediaEntity>): String = when {
        s.coverPath.isNotBlank() -> s.coverPath
        s.coverMediaId > 0 -> byId[s.coverMediaId]?.coverPath.orEmpty()
        s.firstMediaId > 0 -> byId[s.firstMediaId]?.coverPath.orEmpty()
        else -> ""
    }

    /** Все коллекции (для «Добавить в коллекцию»). */
    val collections: StateFlow<List<CollectionSummary>> = summaries

    /** Подробности коллекции — живой поток по базе. */
    fun detail(id: Long): Flow<CollectionDetail?> = db.collections().observe(id).flatMapLatest { c ->
        if (c == null) flowOf(null) else combine(db.collections().observeItems(id), db.collections().observeSeasons(id), media, playback,
            db.chapters().observeProgress()) { items, seasons, med, pb, prog ->
            val byId = med.associateBy { it.id }
            val pbMap = pb.associateBy { it.mediaId }
            val progMap = prog.associateBy { it.chapterId }
            val segCache = HashMap<Long, List<Segments.Segment>>()
            val entries = items.map { it ->
                val m = byId[it.mediaId]
                val seg = if (it.chapterId > 0 && m != null) {
                    segCache.getOrPut(m.id) {
                        Segments.resolve(db.chapters().forMedia(m.id), maxOf(m.durationSec * 1000, pbMap[m.id]?.durationMs ?: 0))
                    }.firstOrNull { s -> s.id == it.chapterId }
                } else null
                CollectionDetail.Entry(it, m, seg, pbMap[it.mediaId], if (seg != null) progMap[seg.id] else null)
            }
            CollectionDetail(c, entries, seasons)
        }
    }.flowOn(Dispatchers.IO)

    // ---------------- действия ----------------

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { block() }.onFailure { AppEvents.notice("Не получилось: ${it.message ?: it.javaClass.simpleName}") }
    }

    fun create(title: String, type: String, description: String = "", tags: String = "", onCreated: (Long) -> Unit = {}) = io {
        val id = repo.create(title, type, description, tags)
        withContext(Dispatchers.Main) { onCreated(id) }
    }

    fun save(c: CollectionEntity) = io { repo.update(c) }
    fun delete(id: Long) = io { if (!repo.delete(id)) AppEvents.notice("Встроенный список удалить нельзя — его можно очистить") }
    fun setPinned(id: Long, pinned: Boolean) = io { repo.setPinned(id, pinned) }
    fun move(id: Long, delta: Int) = io { repo.move(id, delta) }
    fun setCoverMedia(id: Long, mediaId: Long) = io { repo.setCoverMedia(id, mediaId) }
    fun setCoverImage(id: Long, uri: Uri) = io {
        repo.setCoverImage(id, uri, getApplication<Application>().contentResolver)
            .onFailure { AppEvents.notice("Обложка не установлена: ${it.message}") }
    }

    fun addMedia(collectionId: Long, ids: List<Long>, season: Int = 0) = io {
        val n = repo.addMedia(collectionId, ids, season)
        AppEvents.notice(if (n > 0) "Добавлено: $n" else "Уже в коллекции")
    }

    fun removeItem(itemId: Long) = io { repo.removeItem(itemId) }
    fun moveItem(itemId: Long, delta: Int) = io { repo.moveItem(itemId, delta) }
    fun setNumbers(itemId: Long, season: Int, episode: Int, title: String) = io { repo.setItemNumbers(itemId, season, episode, title) }
    fun setSeasonTitle(collectionId: Long, number: Int, title: String) = io { repo.setSeasonTitle(collectionId, number, title) }

    fun setWatched(e: CollectionDetail.Entry, watched: Boolean) = io {
        val seg = e.chapter
        val m = e.media ?: return@io
        if (seg != null) nox.markup.setWatched(seg.id, watched)
        else db.playback().upsert(PlaybackEntity(m.id, 0, e.playback?.durationMs ?: (m.durationSec * 1000).coerceAtLeast(1),
            System.currentTimeMillis(), watched))
    }

    fun toggleSystem(key: String, mediaId: Long, onDone: (Boolean) -> Unit = {}) = io {
        val on = repo.toggleSystem(key, mediaId)
        withContext(Dispatchers.Main) { onDone(on) }
    }

    suspend fun isIn(key: String, mediaId: Long): Boolean = withContext(Dispatchers.IO) { repo.isIn(key, mediaId) }

    /** Открыть элемент коллекции в плеере с очередью этой коллекции (сезона). */
    fun play(detail: CollectionDetail, e: CollectionDetail.Entry, fromStart: Boolean = false) {
        val p = e.playable ?: return
        val ctx = PlayContext.ofCollection(detail.collection.id, detail.collection.title, detail.items.map { it.item }, p, detail.isSeries)
        nox.playback.open(p.mediaId, p.chapterId, ctx, fromStart = fromStart)
    }

    /** Коллекции, где есть это видео. */
    suspend fun collectionsOf(mediaId: Long): Set<Long> = withContext(Dispatchers.IO) {
        db.collections().itemsForMedia(mediaId).map { it.collectionId }.toSet()
    }

    // ---------------- разметка файла ----------------

    fun chapters(mediaId: Long): Flow<List<ChapterEntity>> = db.chapters().observeFor(mediaId)
    fun bookmarks(mediaId: Long): Flow<List<BookmarkEntity>> = db.chapters().observeBookmarks(mediaId)
    fun subtitles(mediaId: Long): Flow<List<SubtitleEntity>> = db.subtitles().observeFor(mediaId)

    fun addChapterAt(mediaId: Long, positionMs: Long, title: String, kind: String, durationMs: Long) = io {
        nox.markup.addAt(mediaId, positionMs, title, kind, durationMs).onFailure { AppEvents.notice(it.message ?: "Не удалось") }
            .onSuccess { AppEvents.notice("Отметка добавлена на ${Segments.clock(positionMs)}") }
    }

    fun editChapter(id: Long, title: String, startMs: Long, endMs: Long, durationMs: Long, onResult: (String?) -> Unit) = io {
        val r = nox.markup.edit(id, title, startMs, endMs, durationMs)
        withContext(Dispatchers.Main) { onResult(r.exceptionOrNull()?.message) }
    }

    fun mergeChapter(id: Long) = io { if (!nox.markup.mergeWithNext(id)) AppEvents.notice("Следующей отметки того же вида нет") }
    fun deleteChapter(id: Long) = io { nox.markup.delete(id) }
    fun setChapterKind(id: Long, kind: String) = io { nox.markup.setKind(id, kind) }

    /** Главы, которые передал источник при анализе (если он их передал). */
    fun importSourceChapters(mediaId: Long, chapters: List<Triple<String, Long, Long>>) = io {
        val n = nox.markup.importSource(mediaId, chapters)
        AppEvents.notice(if (n > 0) "Добавлено глав источника: $n" else "Главы источника уже добавлены или их нет")
    }

    fun addBookmark(mediaId: Long, positionMs: Long, title: String, note: String) = io {
        nox.markup.addBookmark(mediaId, positionMs, title, note)
        AppEvents.notice("Закладка на ${Segments.clock(positionMs)}")
    }

    fun updateBookmark(b: BookmarkEntity) = io { nox.markup.updateBookmark(b) }
    fun deleteBookmark(id: Long) = io { nox.markup.deleteBookmark(id) }

    /** Свой SRT/VTT через системный выбор файла. */
    fun importSubtitle(mediaId: Long, uri: Uri, onSaved: (Long) -> Unit) = io {
        nox.subtitles.importUri(mediaId, uri, getApplication<Application>().contentResolver)
            .onSuccess { s -> withContext(Dispatchers.Main) { onSaved(s.id) }; AppEvents.notice("Субтитры добавлены: ${s.label}") }
            .onFailure { AppEvents.notice("Субтитры не добавлены: ${it.message}") }
    }

    fun deleteSubtitle(id: Long) = io { nox.subtitles.delete(id) }
}
