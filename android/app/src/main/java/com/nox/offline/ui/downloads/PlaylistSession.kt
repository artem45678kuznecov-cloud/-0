package com.nox.offline.ui.downloads

import com.nox.offline.downloader.catalog.AnalyzeResult
import com.nox.offline.downloader.catalog.CatalogBuilder
import com.nox.offline.downloader.catalog.DeviceCaps
import com.nox.offline.downloader.catalog.PlaylistEntry
import com.nox.offline.downloader.catalog.PlaylistPage
import com.nox.offline.downloader.catalog.ResolveError
import com.nox.offline.downloader.catalog.SizeKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Плейлист по одной ссылке. Список приходит страницами в порядке
 * источника; качества каждого выбранного элемента смотрятся отдельным
 * анализом (не больше [VideoFinder.PARALLEL_ANALYSES] одновременно), и
 * пользователь видит результат по каждому. Уже скачанное и уже стоящее в
 * очереди не выбирается само. Каналы в фоне не отслеживаются.
 *
 * Сеть и база подставляются снаружи — класс проверяется JVM-тестом.
 */
class PlaylistSession(
    private val scope: CoroutineScope,
    private val loadPage: (url: String, start: Int, count: Int) -> Result<PlaylistPage>,
    private val analyze: (String) -> AnalyzeResult,
    private val presence: suspend (PlaylistEntry) -> String?,
    private val caps: DeviceCaps,
    private val prefs: () -> PickPrefs,
    private val gate: Semaphore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Item(
        val entry: PlaylistEntry,
        /** «уже в медиатеке» / «уже в очереди» / null. */
        val presence: String?,
        val selected: Boolean,
        val state: FinderState = FinderState.Idle,
    ) {
        val available: Boolean get() = entry.unavailable.isBlank() && entry.url.isNotBlank()
    }

    data class State(
        val url: String = "",
        val title: String = "",
        val uploader: String = "",
        val total: Int = 0,
        val items: List<Item> = emptyList(),
        val loading: Boolean = false,
        val hasMore: Boolean = false,
        val error: ResolveError? = null,
        val audioOnly: Boolean = false,
    ) {
        val selected: List<Item> get() = items.filter { it.selected }
        val analyzedSelected: List<Item> get() = selected.filter { it.state is FinderState.Ready }
        val pendingAnalysis: Int get() = selected.count { it.state is FinderState.Idle || it.state is FinderState.Searching }

        /** Оценка суммы: известные размеры и число неизвестных. */
        fun estimate(): Pair<Long, Int> {
            var sum = 0L
            var unknown = 0
            for (it in selected) {
                val r = it.state as? FinderState.Ready
                val (size, kind) = if (r == null) 0L to SizeKind.UNKNOWN else if (audioOnly) {
                    CatalogBuilder.audioVariants(r.catalog.analysis, r.catalog.language).firstOrNull { a -> a.support.ok }
                        ?.let { a -> a.sizeBytes to a.sizeKind } ?: (0L to SizeKind.UNKNOWN)
                } else r.selected?.let { v -> v.sizeBytes to v.sizeKind } ?: (0L to SizeKind.UNKNOWN)
                if (kind == SizeKind.UNKNOWN || size <= 0) unknown++ else sum += size
            }
            return sum to unknown
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var token = 0L
    private val lock = Any()
    private var jobs = ArrayList<Job>()

    fun open(url: String) {
        if (_state.value.url == url && _state.value.items.isNotEmpty()) return
        cancel()
        val my = synchronized(lock) { ++token }
        _state.value = State(url = url, loading = true)
        load(my, url, 1)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore) return
        val my = synchronized(lock) { token }
        _state.value = s.copy(loading = true)
        load(my, s.url, s.items.size + 1)
    }

    private fun load(my: Long, url: String, start: Int) {
        jobs += scope.launch(io) {
            val r = loadPage(url, start, PAGE)
            if (!current(my)) return@launch
            r.onFailure { e ->
                val err = (e as? PlaylistError)?.error ?: ResolveError("extract-failed", e.message ?: "Не удалось открыть список")
                update(my) { it.copy(loading = false, error = err) }
            }
            r.onSuccess { page ->
                val known = _state.value.items.map { it.entry.index }.toSet()
                val fresh = page.entries.filter { it.index !in known }.map { e ->
                    val p = if (e.unavailable.isBlank()) presence(e) else null
                    Item(e, p, selected = e.unavailable.isBlank() && e.url.isNotBlank() && p == null)
                }
                update(my) {
                    it.copy(title = page.title, uploader = page.uploader, total = page.count, loading = false, error = null,
                        hasMore = page.hasMore, items = it.items + fresh)
                }
            }
        }
    }

    fun toggle(index: Int) = update(synchronized(lock) { token }) { s ->
        s.copy(items = s.items.map { if (it.entry.index == index && it.available) it.copy(selected = !it.selected) else it })
    }

    fun selectAll(on: Boolean) = update(synchronized(lock) { token }) { s ->
        s.copy(items = s.items.map { if (it.available) it.copy(selected = on && it.presence == null || (on && it.selected)) else it })
    }

    /** Только ещё не скачанные и не стоящие в очереди. */
    fun selectMissing() = update(synchronized(lock) { token }) { s ->
        s.copy(items = s.items.map { it.copy(selected = it.available && it.presence == null) })
    }

    /** Диапазон по номерам источника (включительно). */
    fun selectRange(from: Int, to: Int) = update(synchronized(lock) { token }) { s ->
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        s.copy(items = s.items.map { it.copy(selected = it.available && it.entry.index in lo..hi) })
    }

    fun setAudioOnly(on: Boolean) = update(synchronized(lock) { token }) { it.copy(audioOnly = on) }

    /** Разобрать выбранные: каждый — своим анализом, с ограничением параллельности. */
    fun analyzeSelected() {
        val my = synchronized(lock) { token }
        val todo = _state.value.selected.filter { it.state is FinderState.Idle || it.state is FinderState.Failed }
        for (it in todo) setItem(my, it.entry.index) { i -> i.copy(state = FinderState.Searching(i.entry.url)) }
        for (item in todo) {
            jobs += scope.launch(io) {
                val result = gate.withPermit {
                    if (!current(my)) return@launch
                    analyze(item.entry.url)
                }
                val next = when (result) {
                    is AnalyzeResult.Ok -> {
                        val catalog = CatalogBuilder.build(result.analysis, caps)
                        val p = prefs()
                        FinderState.Ready(item.entry.url, catalog, catalog.preselect(p.preferredHeight, p.preferSingleFile)?.key)
                    }
                    is AnalyzeResult.Failed -> FinderState.Failed(item.entry.url, result.error)
                }
                setItem(my, item.entry.index) { it.copy(state = next) }
            }
        }
    }

    /** Свой вариант для одного элемента. */
    fun selectVariant(index: Int, key: String) = setItem(synchronized(lock) { token }, index) { i ->
        val s = i.state as? FinderState.Ready
        if (s != null && s.catalog.find(key)?.support?.ok == true) i.copy(state = s.copy(selectedKey = key)) else i
    }

    fun cancel() {
        synchronized(lock) { token++ }
        jobs.forEach { it.cancel() }
        jobs = ArrayList()
        _state.value = _state.value.copy(loading = false)
    }

    fun reset() {
        cancel()
        _state.value = State()
    }

    private fun current(my: Long) = synchronized(lock) { my == token }

    private fun update(my: Long, f: (State) -> State) {
        synchronized(lock) { if (my == token) _state.value = f(_state.value) }
    }

    private fun setItem(my: Long, index: Int, f: (Item) -> Item) = update(my) { s ->
        s.copy(items = s.items.map { if (it.entry.index == index) f(it) else it })
    }

    class PlaylistError(val error: ResolveError) : Exception(error.message)

    companion object {
        const val PAGE = 50
    }
}
