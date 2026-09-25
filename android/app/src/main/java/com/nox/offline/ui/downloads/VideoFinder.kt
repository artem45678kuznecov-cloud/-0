package com.nox.offline.ui.downloads

import com.nox.offline.downloader.catalog.AnalyzeResult
import com.nox.offline.downloader.catalog.CatalogBuilder
import com.nox.offline.downloader.catalog.DeviceCaps
import com.nox.offline.downloader.catalog.FormatCatalog
import com.nox.offline.downloader.catalog.ResolveError
import com.nox.offline.downloader.catalog.Variant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Настройки предварительного выбора. */
data class PickPrefs(val preferredHeight: Int, val preferSingleFile: Boolean)

/** Состояние поиска одного видео. */
sealed class FinderState {
    /** Ссылка, к которой относится состояние (null — поиска нет). */
    abstract val url: String?

    object Idle : FinderState() {
        override val url: String? = null
    }
    data class Searching(override val url: String) : FinderState()
    data class Ready(
        override val url: String,
        val catalog: FormatCatalog,
        val selectedKey: String?,
        /** Группа, у которой раскрыто «Подробнее». */
        val expanded: String? = null,
    ) : FinderState() {
        val selected: Variant? get() = selectedKey?.let(catalog::find)?.takeIf { it.support.ok }
    }
    data class Failed(override val url: String, val error: ResolveError) : FinderState()
}

/**
 * «Найти видео»: один анализ на нажатие, каталог и выбор варианта.
 *
 *  - смена качества и языка работает по уже полученному каталогу, без
 *    нового разбора страницы;
 *  - смена ссылки или отмена делают прежний каталог недействительным, а
 *    запоздавший ответ предыдущего поиска не перезаписывает новый;
 *  - анализ не занимает слот передачи и идёт параллельно загрузкам
 *    (одновременно не больше [PARALLEL_ANALYSES]).
 *
 * [analyze] — блокирующий вызов резолвера; класс не зависит от Android и
 * проверяется JVM-тестами.
 */
class VideoFinder(
    private val scope: CoroutineScope,
    private val analyze: (String) -> AnalyzeResult,
    private val caps: DeviceCaps,
    private val prefs: () -> PickPrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val gate: Semaphore = Semaphore(PARALLEL_ANALYSES),
) {
    companion object {
        const val PARALLEL_ANALYSES = 2
    }

    private val _state = MutableStateFlow<FinderState>(FinderState.Idle)
    val state: StateFlow<FinderState> = _state

    private val lock = Any()
    private var token = 0L

    /** Задание, для которого выбирается качество заново (вариант пропал). */
    @Volatile
    var replanId: Long? = null

    fun search(url: String) {
        val my = synchronized(lock) { ++token }
        _state.value = FinderState.Searching(url)
        scope.launch(io) {
            val result = gate.withPermit {
                // Пока ждали очереди, поиск могли отменить — сеть не трогаем.
                if (!isCurrent(my)) return@launch
                analyze(url)
            }
            val next = when (result) {
                is AnalyzeResult.Ok -> {
                    val catalog = CatalogBuilder.build(result.analysis, caps)
                    val p = prefs()
                    FinderState.Ready(url, catalog, catalog.preselect(p.preferredHeight, p.preferSingleFile)?.key)
                }
                is AnalyzeResult.Failed -> FinderState.Failed(url, result.error)
            }
            synchronized(lock) { if (my == token) _state.value = next }
        }
    }

    private fun isCurrent(my: Long) = synchronized(lock) { my == token }

    /** Отмена поиска или сброс карточки. Ответ уже идущего анализа будет проигнорирован. */
    fun cancel() {
        synchronized(lock) { token++ }
        _state.value = FinderState.Idle
        replanId = null
    }

    /** Ссылка в поле изменилась: прежние варианты к ней больше не относятся. */
    fun onUrlChanged(url: String) {
        val current = _state.value
        if (current !is FinderState.Idle && current.url != url.trim()) {
            synchronized(lock) { token++ }
            _state.value = FinderState.Idle
        }
    }

    fun retry() {
        val url = (_state.value as? FinderState.Failed)?.url ?: return
        search(url)
    }

    fun select(key: String) {
        val s = _state.value as? FinderState.Ready ?: return
        val v = s.catalog.find(key) ?: return
        if (!v.support.ok) return
        _state.value = s.copy(selectedKey = key)
    }

    fun toggleDetails(groupKey: String) {
        val s = _state.value as? FinderState.Ready ?: return
        _state.value = s.copy(expanded = if (s.expanded == groupKey) null else groupKey)
    }

    /** Другой язык звука: каталог пересобирается из того же анализа, без сети. */
    fun setLanguage(code: String) {
        val s = _state.value as? FinderState.Ready ?: return
        val rebuilt = CatalogBuilder.build(s.catalog.analysis, caps, code)
        val oldGroup = s.selected?.groupKey
        val keep = rebuilt.main.firstOrNull { it.groupKey == oldGroup && it.support.ok }
        val p = prefs()
        _state.value = s.copy(catalog = rebuilt, selectedKey = (keep ?: rebuilt.preselect(p.preferredHeight, p.preferSingleFile))?.key)
    }
}

/** Пакет ссылок: у каждой свой анализ, свой каталог и свой выбор. */
class BatchFinder(
    private val scope: CoroutineScope,
    private val analyze: (String) -> AnalyzeResult,
    private val caps: DeviceCaps,
    private val prefs: () -> PickPrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val gate: Semaphore = Semaphore(VideoFinder.PARALLEL_ANALYSES),
) {
    data class Entry(val url: String, val state: FinderState)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val lock = Any()
    private var token = 0L

    fun start(urls: List<String>) {
        val my = synchronized(lock) { ++token }
        _entries.value = urls.map { Entry(it, FinderState.Searching(it)) }
        urls.forEachIndexed { index, url ->
            scope.launch(io) {
                val result = gate.withPermit {
                    if (synchronized(lock) { my != token }) return@launch
                    analyze(url)
                }
                val next = when (result) {
                    is AnalyzeResult.Ok -> {
                        val catalog = CatalogBuilder.build(result.analysis, caps)
                        val p = prefs()
                        FinderState.Ready(url, catalog, catalog.preselect(p.preferredHeight, p.preferSingleFile)?.key)
                    }
                    is AnalyzeResult.Failed -> FinderState.Failed(url, result.error)
                }
                update(my, index) { next }
            }
        }
    }

    private fun update(my: Long, index: Int, f: (FinderState) -> FinderState) {
        synchronized(lock) {
            if (my != token) return
            val list = _entries.value.toMutableList()
            if (index !in list.indices) return
            list[index] = list[index].copy(state = f(list[index].state))
            _entries.value = list
        }
    }

    private fun current() = synchronized(lock) { token }

    fun select(index: Int, key: String) = update(current(), index) { s ->
        if (s is FinderState.Ready && s.catalog.find(key)?.support?.ok == true) s.copy(selectedKey = key) else s
    }

    fun toggleDetails(index: Int, groupKey: String) = update(current(), index) { s ->
        if (s is FinderState.Ready) s.copy(expanded = if (s.expanded == groupKey) null else groupKey) else s
    }

    fun setLanguage(index: Int, code: String) = update(current(), index) { s ->
        if (s !is FinderState.Ready) s else {
            val rebuilt = CatalogBuilder.build(s.catalog.analysis, caps, code)
            val keep = rebuilt.main.firstOrNull { it.groupKey == s.selected?.groupKey && it.support.ok }
            val p = prefs()
            s.copy(catalog = rebuilt, selectedKey = (keep ?: rebuilt.preselect(p.preferredHeight, p.preferSingleFile))?.key)
        }
    }

    /** Убрать ссылку из пакета (выбор «не добавлять»). */
    fun skip(index: Int) = update(current(), index) { s ->
        if (s is FinderState.Ready) s.copy(selectedKey = null) else s
    }

    fun cancel() {
        synchronized(lock) { token++ }
        _entries.value = emptyList()
    }
}
