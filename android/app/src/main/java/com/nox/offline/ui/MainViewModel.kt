package com.nox.offline.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nox.offline.BuildConfig
import com.nox.offline.NoxApp
import com.nox.offline.core.FileNames
import com.nox.offline.core.Format
import com.nox.offline.core.LinkParser
import com.nox.offline.core.MediaTypes
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
import com.nox.offline.core.Storage
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.downloader.DownloadCoordinator
import com.nox.offline.downloader.TransferScheduler
import com.nox.offline.downloader.catalog.AnalyzeResult
import com.nox.offline.ui.downloads.BatchFinder
import com.nox.offline.ui.downloads.FinderState
import com.nox.offline.ui.downloads.PickPrefs
import com.nox.offline.ui.downloads.VideoFinder
import com.nox.offline.settings.Appearance
import com.nox.offline.storage.FileOps
import com.nox.offline.storage.MediaLocator
import com.nox.offline.storage.PlaybackRegistry
import com.nox.offline.ui.components.LibraryItem
import com.nox.offline.ui.components.StatusInfo
import com.nox.offline.ui.library.LibraryFilter
import com.nox.offline.ui.library.LibraryQuery
import com.nox.offline.updates.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File

/** Строка предварительного списка массового добавления. */
data class BatchLine(val url: String, val note: String?, val addable: Boolean)

/**
 * Состояние экранов. Всё, что показывается, читается из Room потоками;
 * тяжёлые операции уходят в соответствующие подсистемы NoxApp.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val nox = NoxApp.get(app)
    private val coordinator = nox.coordinator
    val settings = nox.settings

    // ---------------- данные ----------------

    val downloads: StateFlow<List<DownloadEntity>> = nox.db.downloads().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val items: StateFlow<List<LibraryItem>> = combine(nox.db.media().observeAll(), nox.db.playback().observeAll()) { media, playback ->
        val byId = playback.associateBy { it.mediaId }
        media.map { LibraryItem(it, byId[it.id]) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LibraryFilter())

    val library: StateFlow<List<LibraryItem>> = combine(items, query, filter) { list, q, f -> LibraryQuery.apply(list, q, f) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allItems: StateFlow<List<LibraryItem>> = items

    val continueItem: StateFlow<LibraryItem?> = items.map { LibraryQuery.continueWatching(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Реальное состояние для капсулы в шапке. */
    val status: StateFlow<StatusInfo> = combine(downloads, items) { list, media ->
        val running = list.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.RESOLVING }
        val processing = list.count { it.status == DownloadStatus.PROCESSING }
        val queued = list.count { it.status == DownloadStatus.QUEUED }
        val paused = list.count { it.status == DownloadStatus.PAUSED }
        val failed = list.count { it.status == DownloadStatus.ERROR }
        when {
            running > 0 -> {
                val total = list.filter { it.status == DownloadStatus.DOWNLOADING && it.totalBytes > 0 }
                val pct = if (total.isEmpty()) null else (total.sumOf { it.downloadedBytes } * 100 / total.sumOf { it.totalBytes }).toInt()
                StatusInfo("Скачивается: $running", pct?.let { "$it%" } ?: "идёт", true)
            }
            processing > 0 -> StatusInfo("Завершение", "обработка", true)
            queued > 0 -> StatusInfo("Загрузки", "в очереди: $queued", false)
            failed > 0 -> StatusInfo("Загрузки", "ошибка: $failed", false)
            paused > 0 -> StatusInfo("Загрузки", "на паузе: $paused", false)
            media.isNotEmpty() -> StatusInfo("Офлайн", "${media.size} видео", false)
            else -> StatusInfo("Медиатека", "пока пусто", false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatusInfo("NOX", "офлайн", false))

    val space = MutableStateFlow<Storage.Space?>(null)
    val notificationsAllowed = MutableStateFlow(true)
    val appearance = settings.appearance
    val downloadPrefs = settings.downloads
    val playerPrefs = settings.player
    val updatePrefs = settings.updates
    val updateState = nox.updates.state
    val fileTask = nox.tasks.current
    val wallpaperFrame = nox.wallpaper.frame

    // ---------------- ввод ссылки ----------------

    val urlInput = MutableStateFlow("")
    val message = MutableStateFlow("")

    private val pickPrefs = { settings.downloads.value.let { PickPrefs(it.preferredHeight, it.preferSingleFile) } }
    private val analyzeGate = Semaphore(VideoFinder.PARALLEL_ANALYSES)
    private val analyze: (String) -> AnalyzeResult = { url -> nox.resolver.analyze(url) }

    /** «Найти видео»: карточка и реальные варианты качества конкретного видео. */
    val finder = VideoFinder(viewModelScope, analyze, nox.deviceCaps, pickPrefs, gate = analyzeGate)

    /** Пакет ссылок: у каждой свой каталог и свой выбор. */
    val batch = BatchFinder(viewModelScope, analyze, nox.deviceCaps, pickPrefs, gate = analyzeGate)
    /** Текст для листа массового добавления (например, пришёл через «Поделиться»). */
    val pendingBatch = MutableStateFlow<String?>(null)

    init {
        refreshSpace()
    }

    fun refreshSpace() {
        viewModelScope.launch(Dispatchers.IO) { space.value = nox.storage.space() }
    }

    fun setUrl(text: String) {
        // Несколько ссылок сразу — это массовое добавление.
        if (LinkParser.links(text).size > 1) {
            pendingBatch.value = text
            return
        }
        urlInput.value = text
        // Другая ссылка — прежние варианты недействительны.
        finder.onUrlChanged(SafeUrl.extract(text) ?: text.trim())
    }

    /** «Найти видео»: один разбор страницы, без передачи файла. */
    fun findVideo() {
        val url = SafeUrl.extract(urlInput.value) ?: urlInput.value.trim()
        if (!SafeUrl.looksLikeUrl(url)) {
            message.value = "Вставьте ссылку на видео"
            return
        }
        message.value = ""
        finder.search(url)
    }

    /** «Поделиться в NOX»: та же карточка, что и после «Найти видео». */
    fun openShared(text: String) {
        setUrl(text)
        if (LinkParser.links(text).size <= 1) findVideo()
    }

    /** Выбрать качество заново для остановленного задания (вариант пропал или изменился). */
    fun rechoose(d: DownloadEntity) {
        urlInput.value = d.pageUrl
        finder.search(d.pageUrl)
        finder.replanId = d.id
    }

    /** «Скачать» выбранный вариант: задание в очередь и запуск носителя. Только из видимого окна. */
    fun downloadSelected() {
        val s = finder.state.value as? FinderState.Ready ?: return
        val v = s.selected ?: run { message.value = "Выберите качество"; return }
        val replan = finder.replanId
        viewModelScope.launch(Dispatchers.IO) {
            val request = DownloadCoordinator.DownloadRequest(s.catalog.details, v)
            val r = if (replan != null) coordinator.replan(replan, request) else coordinator.enqueue(request)
            r.onFailure { message.value = it.message ?: "Не удалось добавить" }
            r.onSuccess {
                withContext(Dispatchers.Main) {
                    // Карточку сбрасываем, только если пользователь не начал новый поиск.
                    if (finder.state.value === s) finder.cancel()
                    if (urlInput.value.trim() == s.url || SafeUrl.extract(urlInput.value) == s.url) urlInput.value = ""
                }
                message.value = when (val st = TransferScheduler.ensureRunning(getApplication())) {
                    is TransferScheduler.Result.Failed -> "Добавлено, но фоновая загрузка не запустилась: ${st.reason}"
                    else -> "Добавлено в очередь: ${v.title}"
                }
            }
        }
    }

    suspend fun previewBatch(text: String): List<BatchLine> = withContext(Dispatchers.IO) {
        LinkParser.lines(text).map { line ->
            when (line.kind) {
                LinkParser.Kind.NOT_A_LINK -> BatchLine(line.raw, "не ссылка", false)
                LinkParser.Kind.DUPLICATE -> BatchLine(line.raw, "повтор в списке", false)
                LinkParser.Kind.LINK -> {
                    val reason = coordinator.duplicateReason(line.url!!)
                    BatchLine(line.url, reason, reason == null || reason == "уже в медиатеке")
                }
            }
        }
    }

    /** Пакет: у каждой ссылки свой анализ и свой каталог. */
    fun findBatch(lines: List<BatchLine>) = batch.start(lines.filter { it.addable }.map { it.url })

    /** «Добавить выбранные»: каждая ссылка со своим вариантом; ошибочная не отменяет остальные. */
    fun addBatchSelected(onDone: (String) -> Unit) {
        val entries = batch.entries.value
        viewModelScope.launch(Dispatchers.IO) {
            var added = 0
            var failed = 0
            var skipped = 0
            val errors = ArrayList<String>()
            for (e in entries) {
                val s = e.state as? FinderState.Ready
                val v = s?.selected
                if (s == null || v == null) { skipped++; continue }
                coordinator.enqueue(DownloadCoordinator.DownloadRequest(s.catalog.details, v))
                    .onSuccess { added++ }
                    .onFailure { failed++; errors.add(it.message.orEmpty()) }
            }
            if (added > 0) TransferScheduler.ensureRunning(getApplication())
            val text = buildString {
                append("Добавлено: $added")
                if (skipped > 0) append(", пропущено: $skipped")
                if (failed > 0) append(", не удалось: $failed (${errors.distinct().joinToString("; ").take(120)})")
            }
            NoxLog.event("batch-add", "added" to added, "skipped" to skipped, "failed" to failed)
            message.value = text
            withContext(Dispatchers.Main) {
                batch.cancel()
                onDone(text)
            }
        }
    }

    /** При возвращении в приложение: если есть очередь, а носителя нет — поднять. */
    fun ensureRunnerIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            if (coordinator.hasWork() && !coordinator.runnerAttached && !coordinator.updateHold.value) {
                TransferScheduler.ensureRunning(getApplication())
            }
        }
    }

    fun pause(id: Long) = viewModelScope.launch(Dispatchers.IO) { coordinator.pause(id) }

    fun resume(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        coordinator.resume(id)
        TransferScheduler.ensureRunning(getApplication())
    }

    fun toggle(d: DownloadEntity) = when (d.status) {
        DownloadStatus.PAUSED, DownloadStatus.ERROR -> resume(d.id)
        else -> pause(d.id)
    }

    fun cancel(id: Long) = viewModelScope.launch(Dispatchers.IO) { coordinator.cancel(id) }

    fun pauseAll() = viewModelScope.launch(Dispatchers.IO) {
        val n = coordinator.pauseAll()
        message.value = if (n > 0) "Приостановлено: $n" else "Нечего приостанавливать"
    }

    fun resumeAll() = viewModelScope.launch(Dispatchers.IO) {
        val n = coordinator.resumeAll()
        if (n > 0) TransferScheduler.ensureRunning(getApplication())
        message.value = if (n > 0) "Продолжено: $n" else "Нет приостановленных"
    }

    fun cancelAllUnfinished() = viewModelScope.launch(Dispatchers.IO) {
        val ids = coordinator.downloadsDao.getAll().filter { it.status != DownloadStatus.COMPLETED }.map { it.id }
        coordinator.cancelMany(ids)
        refreshSpace()
    }

    fun clearCompleted() = viewModelScope.launch(Dispatchers.IO) { coordinator.clearCompleted() }

    fun renameDownload(id: Long, title: String) = viewModelScope.launch(Dispatchers.IO) { coordinator.rename(id, title) }

    // ---------------- медиатека ----------------

    fun deleteMedia(m: MediaEntity) = viewModelScope.launch(Dispatchers.IO) {
        if (PlaybackRegistry.playingMediaId == m.id) {
            com.nox.offline.core.AppEvents.notice("Закройте плеер, чтобы удалить это видео"); return@launch
        }
        MediaLocator.delete(nox.saf, m)
        if (m.coverPath.isNotBlank()) runCatching { File(m.coverPath).delete() }
        nox.db.playback().delete(m.id)
        nox.db.media().delete(m)
        NoxLog.event("media-deleted", "id" to m.id)
        refreshSpace()
    }

    /**
     * Переименование: название меняется всегда, файл — по выбору и только
     * если его сейчас не читает плеер. Личность видео — его id, поэтому
     * позиция просмотра не теряется.
     */
    fun renameMedia(m: MediaEntity, title: String, renameFile: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val clean = title.trim()
        if (clean.isEmpty()) return@launch
        var updated = m.copy(title = clean)
        if (renameFile) {
            if (PlaybackRegistry.playingMediaId == m.id) {
                com.nox.offline.core.AppEvents.notice("Видео открыто в плеере — изменено только название")
            } else if (m.isExternal) {
                val newName = FileNames.targetName(clean, m.videoId, MediaLocator.fileName(m).substringAfterLast('.', "mp4"))
                nox.saf.rename(m.contentUri, newName)?.let { updated = updated.copy(contentUri = it) }
                    ?: run { com.nox.offline.core.AppEvents.notice("Папка не разрешила переименовать файл — изменено только название") }
            } else {
                val f = File(m.filePath)
                val target = FileNames.unique(f.parentFile!!, FileNames.targetName(clean, m.videoId, f.extension.ifBlank { "mp4" }))
                if (f.exists() && f.renameTo(target)) updated = updated.copy(filePath = target.absolutePath)
                else com.nox.offline.core.AppEvents.notice("Не удалось переименовать файл — изменено только название")
            }
        }
        nox.db.media().update(updated)
        NoxLog.event("media-renamed", "id" to m.id, "file" to renameFile)
    }

    fun shareIntent(m: MediaEntity): Intent {
        val uri = MediaLocator.shareUri(getApplication(), m)
        val send = Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Поделиться видео").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun restartFromBeginning(m: MediaEntity) = viewModelScope.launch(Dispatchers.IO) { nox.db.playback().delete(m.id) }

    fun exportMedia(list: List<MediaEntity>, tree: Uri) {
        val ctx = getApplication<Application>()
        nox.tasks.run("Экспорт видео") {
            val treeStr = tree.toString()
            val total = list.sumOf { it.sizeBytes }
            var done = 0L
            var ok = 0
            for (m in list) {
                val name = MediaLocator.fileName(m)
                val base = done
                if (m.isExternal) {
                    nox.saf.copyFrom(treeStr, m.sizeBytes, name, MediaTypes.mimeForName(name), { d, _ -> update(m.title, base + d, total) }) {
                        ctx.contentResolver.openInputStream(Uri.parse(m.contentUri)) ?: throw java.io.IOException("Нет доступа к исходнику")
                    }
                } else {
                    nox.saf.copyInto(treeStr, File(m.filePath), name, MediaTypes.mimeForName(name)) { d, _ -> update(m.title, base + d, total) }
                }
                done += m.sizeBytes
                ok++
            }
            "Экспортировано: $ok. Эти копии в выбранной папке не удалятся вместе с NOX"
        }
    }

    fun importVideos(uris: List<Uri>) {
        nox.tasks.run("Импорт видео") {
            var ok = 0
            val errors = ArrayList<String>()
            for (u in uris) {
                val src = nox.importer.describe(u)
                try {
                    nox.importer.import(src) { d, t -> update(src.name, d, t) }
                    ok++
                } catch (e: Exception) {
                    errors.add("${src.name}: ${e.message}")
                }
            }
            refreshSpace()
            if (errors.isEmpty()) "Импортировано: $ok" else "Импортировано: $ok, ошибки: ${errors.joinToString("; ").take(200)}"
        }
    }

    // ---------------- папка готовых видео ----------------

    fun setDestination(tree: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val old = settings.downloads.value.destinationTree
            val label = nox.saf.persist(tree)
            if (old.isNotBlank() && old != tree.toString()) nox.saf.release(old)
            settings.updateDownloads { it.copy(destinationTree = tree.toString(), destinationLabel = label) }
            com.nox.offline.core.AppEvents.notice("Новые видео будут сохраняться в «$label»")
        } catch (e: Exception) {
            com.nox.offline.core.AppEvents.notice("Не удалось получить доступ к папке: ${e.message}")
        }
    }

    fun clearDestination() {
        val old = settings.downloads.value.destinationTree
        nox.saf.release(old)
        settings.updateDownloads { it.copy(destinationTree = "", destinationLabel = "") }
    }

    fun destinationStatus(): String {
        val p = settings.downloads.value
        if (p.destinationTree.isBlank()) return "Внутри NOX (Android/data/${getApplication<Application>().packageName})"
        return if (nox.saf.isWritable(p.destinationTree)) "«${p.destinationLabel}»" else "«${p.destinationLabel}» — доступ потерян, выберите папку заново"
    }

    fun moveLibraryToDestination() {
        nox.tasks.run("Перенос медиатеки") {
            val (moved, failed) = nox.relocator.relocateAll { done, total, title -> update(title, done.toLong(), total.toLong()) }
            refreshSpace()
            if (failed == 0) "Перенесено: $moved" else "Перенесено: $moved, осталось в NOX: $failed"
        }
    }

    fun internalCount(): Int = allItems.value.count { !it.media.isExternal }

    // ---------------- резервная копия ----------------

    fun backupExport(tree: Uri, includeMedia: Boolean, includeParts: Boolean) {
        nox.tasks.run("Резервная копия") {
            nox.backup.export(tree, includeMedia, includeParts) { t, d, total -> update(t, d, total) }
        }
    }

    fun backupImport(tree: Uri) {
        nox.tasks.run("Восстановление") {
            val r = nox.backup.import(tree) { t, d, total -> update(t, d, total) }
            refreshSpace()
            "Восстановлено видео: ${r.media}, позиций: ${r.positions}, заданий: ${r.downloads}" +
                if (r.skipped > 0) ", пропущено: ${r.skipped}" else ""
        }
    }

    fun cancelFileTask() = nox.tasks.cancel()
    fun dismissFileTask() = nox.tasks.dismiss()

    // ---------------- оформление ----------------

    fun updateAppearance(preview: Boolean = false, t: (Appearance) -> Appearance) {
        settings.updateAppearance(t)
        nox.wallpaper.request(settings.appearance.value, preview)
    }

    fun resetAppearance() {
        settings.resetAppearance()
        nox.wallpaper.request(settings.appearance.value)
    }

    fun importWallpaper(uri: Uri) = viewModelScope.launch {
        nox.wallpaper.importCustom(uri)
            .onSuccess { nox.wallpaper.request(settings.appearance.value) }
            .onFailure { com.nox.offline.core.AppEvents.notice("Не удалось поставить фон: ${it.message ?: it.javaClass.simpleName}") }
    }

    fun clearCustomWallpaper() {
        nox.wallpaper.clearCustom()
        nox.wallpaper.request(settings.appearance.value)
    }

    fun onWindowSize(w: Int, h: Int) = nox.wallpaper.onWindowSize(w, h)

    fun setPreferSingleFile(on: Boolean) = settings.updateDownloads { it.copy(preferSingleFile = on) }
    fun setPreferredHeight(h: Int) = settings.updateDownloads { it.copy(preferredHeight = h) }
    fun setPipOnLeave(on: Boolean) = settings.updatePlayer { it.copy(pipOnLeave = on) }

    // ---------------- обновления ----------------

    fun checkUpdates() = nox.updates.check(manual = true)
    fun autoCheckUpdates() = nox.updates.autoCheck()
    fun downloadUpdate(m: UpdateManifest) = nox.updates.download(m)
    fun cancelUpdateDownload() = nox.updates.cancelDownload()
    fun updateLater() = nox.updates.later()
    fun skipUpdate(m: UpdateManifest) = nox.updates.skip(m)
    fun updatesOnResume() = nox.updates.onResume()
    fun installPermissionIntent(): Intent = nox.updates.installer.permissionIntent()
    fun setAutoCheck(on: Boolean) = settings.updateUpdates { it.copy(autoCheck = on) }

    fun clearMessage() { message.value = "" }

    /** Текст для кнопки «Скопировать диагностику». Без секретов. */
    suspend fun diagnostics(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("NOX Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Носитель: ${if (Build.VERSION.SDK_INT >= 34) "UIDT job" else "foreground service"}; подключён=${coordinator.runnerAttached}; пауза под обновление=${coordinator.updateHold.value}")
        sb.appendLine("Активных передач: ${coordinator.activeCount.value}")
        sb.appendLine("Последняя остановка носителя: ${coordinator.lastStopReason.ifBlank { "-" }}")
        sb.appendLine("yt-dlp: ${nox.resolver.version()}")
        sb.appendLine("JS-движок YouTube: ${nox.resolver.jsStatus()}")
        sb.appendLine("Последняя ошибка резолвера: ${nox.resolver.lastError.ifBlank { "-" }}")
        val a = settings.appearance.value
        sb.appendLine("Оформление: тема=${a.preset} стекло=${a.glassMode.key} фон=${a.wallpaper.key}")
        sb.appendLine("Обновления: ${nox.updates.state.value.javaClass.simpleName}")
        val sp = nox.storage.space()
        sb.appendLine("Хранилище: ${nox.storage.root.absolutePath}")
        sb.appendLine("Папка готовых видео: ${destinationStatus()}")
        sb.appendLine("Свободно ${Format.bytes(sp.freeBytes)}, занято NOX ${Format.bytes(sp.usedByNox)}")
        sb.appendLine()
        sb.appendLine("Задания:")
        for (d in nox.db.downloads().getAll()) {
            sb.appendLine("  #${d.id} ${d.status} ${d.qualityLabel} ${d.mode} ${Format.bytes(d.downloadedBytes)}/${Format.bytes(d.totalBytes)} " +
                "source=${d.extractorKey.ifBlank { "-" }} plan=${d.planVersion} format=${d.formatId.ifBlank { "-" }}+${d.audioFormatId.ifBlank { "-" }} " +
                "container=${d.container.ifBlank { d.ext }} stage=${d.stage.ifBlank { "-" }} " +
                "host=${SafeUrl.host(d.resolvedUrl)} retries=${d.retries}/${d.resolveRetries} " +
                "stop=${d.lastStopReason.ifBlank { "-" }} kind=${d.errorKind.ifBlank { "-" }} err=${d.error.take(80).ifBlank { "-" }}")
        }
        sb.appendLine()
        sb.appendLine("Журнал:")
        for (line in NoxLog.dump().takeLast(200)) sb.appendLine("  $line")
        sb.toString()
    }

    fun freeNeeded(bytes: Long): Boolean = try {
        FileOps.requireSpace(bytes, nox.storage.space().freeBytes); true
    } catch (_: Exception) {
        false
    }
}
