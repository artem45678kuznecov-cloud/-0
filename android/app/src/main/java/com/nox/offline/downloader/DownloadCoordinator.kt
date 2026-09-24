package com.nox.offline.downloader

import com.nox.offline.core.FileNames
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
import com.nox.offline.core.Storage
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadMode
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.NoxDatabase
import com.nox.offline.storage.MediaRelocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Единый контроллер загрузок — аналог DownloadManager из NOX.
 *
 * Держит очередь в Room, ведёт не больше [MAX_CONCURRENT] передач
 * одновременно, умеет паузу, продолжение, отмену и восстановление после
 * гибели процесса. Сам по себе ничего не запускает: передачи идут только
 * пока к нему подключён «носитель» — UIDT-job на Android 14+ или
 * foreground service на более старых. Так загрузка не зависит от Activity.
 *
 * v0.2.0 добавляет, не меняя прежний путь:
 *  - раздельные дорожки (видео и звук качаются по очереди в ОДНОМ сетевом
 *    слоте, склейка идёт в отдельном однопоточном бюджете);
 *  - «Приостановить все» / «Продолжить все», переименование задания;
 *  - контрольную точку перед установкой обновления APK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCoordinator(
    private val db: NoxDatabase,
    private val storage: Storage,
    private val resolver: YtDlpResolver,
    private val http: HttpDownloader,
    private val client: OkHttpClient,
    private val relocator: MediaRelocator? = null,
    private val splitDefault: () -> Boolean = { false },
) {
    companion object {
        const val MAX_CONCURRENT = 3
        const val HTTP_RETRIES = 8
        const val RESOLVE_RETRIES = 3
        const val PROGRESS_WRITE_EVERY_MS = 500L
        const val SPEED_WINDOW_MS = 1500L
        const val COVER_LIMIT = 8L * 1024 * 1024
        const val STOP_UPDATE = "update"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Склейка дорожек: отдельный бюджет, не больше одной одновременно. */
    private val mergeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val pumpLock = Mutex()
    private val active = ConcurrentHashMap<Long, Job>()
    private val merging = ConcurrentHashMap<Long, Job>()
    private val pauseRequested: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val cancelRequested: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val updateStopping: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Сколько передач идёт прямо сейчас. */
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount

    /** Установка обновления: новые передачи не стартуют. */
    private val _updateHold = MutableStateFlow(false)
    val updateHold: StateFlow<Boolean> = _updateHold

    /** Подключён ли носитель (job/service). Без него передачи не стартуют. */
    @Volatile
    var runnerAttached: Boolean = false
        private set

    /** Носитель просит сообщить, когда работы не осталось. */
    @Volatile
    private var onIdle: (() -> Unit)? = null

    @Volatile
    var lastStopReason: String = ""
        private set

    /** Кому сообщать о паузе и о снятии задания (уведомления). */
    interface Listener {
        fun onPaused(e: DownloadEntity)
        fun onCleared(id: Long)
    }

    @Volatile
    var listener: Listener? = null

    val downloadsDao get() = db.downloads()

    // ------------------------------------------------------------------
    //  Носитель
    // ------------------------------------------------------------------

    fun attachRunner(name: String, onIdle: () -> Unit) {
        this.onIdle = onIdle
        runnerAttached = true
        NoxLog.event("runner-attached", "runner" to name)
        pump()
    }

    fun detachRunner(name: String) {
        runnerAttached = false
        onIdle = null
        NoxLog.event("runner-detached", "runner" to name)
    }

    /** Есть ли что делать: очередь или живые передачи. */
    suspend fun hasWork(): Boolean = downloadsDao.pendingCount() > 0

    fun hasWorkBlocking(): Boolean = runBlocking { hasWork() }

    suspend fun remainingKnownBytes(): Long = downloadsDao.remainingKnownBytes()

    // ------------------------------------------------------------------
    //  Действия пользователя
    // ------------------------------------------------------------------

    suspend fun enqueue(
        pageUrl: String,
        quality: Quality,
        allowSplit: Boolean = splitDefault(),
        customTitle: String = "",
    ): Result<Long> {
        val url = pageUrl.trim()
        if (!SafeUrl.looksLikeUrl(url)) return Result.failure(IllegalArgumentException("Это не похоже на ссылку"))
        if (downloadsDao.countLiveFor(url) > 0) return Result.failure(IllegalStateException("Эта загрузка уже есть"))
        val now = System.currentTimeMillis()
        val id = downloadsDao.insert(
            DownloadEntity(pageUrl = url, quality = quality.key, createdAt = now, updatedAt = now,
                allowSplit = allowSplit, customTitle = customTitle.trim())
        )
        NoxLog.event("job-added", "id" to id, "host" to SafeUrl.host(url), "quality" to quality.key, "split" to allowSplit)
        pump()
        return Result.success(id)
    }

    /** Для списка массового добавления: почему ссылку не стоит добавлять ещё раз. */
    suspend fun duplicateReason(url: String): String? = when {
        downloadsDao.countLiveFor(url.trim()) > 0 -> "уже в очереди"
        db.media().countByPageUrl(url.trim()) > 0 -> "уже в медиатеке"
        else -> null
    }

    suspend fun pause(id: Long) {
        val e = downloadsDao.get(id) ?: return
        if (e.status == DownloadStatus.PAUSED || e.status == DownloadStatus.COMPLETED) return
        if (e.status == DownloadStatus.PROCESSING) return   // склейку/завершение не приостанавливаем
        pauseRequested.add(id)
        val job = active[id]
        if (job != null) {
            job.cancel(CancellationException("pause"))
        } else {
            downloadsDao.setStatus(id, DownloadStatus.PAUSED, "", System.currentTimeMillis())
            pauseRequested.remove(id)
            NoxLog.event("job-paused", "id" to id, "part" to partSize(e))
            downloadsDao.get(id)?.let { listener?.onPaused(it) }
        }
    }

    suspend fun resume(id: Long) {
        val e = downloadsDao.get(id) ?: return
        if (e.status == DownloadStatus.PAUSED || e.status == DownloadStatus.ERROR) {
            pauseRequested.remove(id)
            downloadsDao.update(
                e.copy(status = DownloadStatus.QUEUED, error = "", retries = 0, resolveRetries = 0,
                    downloadedBytes = partSize(e), lastStopReason = "", updatedAt = System.currentTimeMillis())
            )
            NoxLog.event("job-resume", "id" to id, "part" to partSize(e))
            listener?.onCleared(id)
        }
        pump()
    }

    /** Приостановить всё, что в очереди или качается. Каждое — своей паузой. */
    suspend fun pauseAll(): Int {
        val list = downloadsDao.getAll().filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RESOLVING || it.status == DownloadStatus.DOWNLOADING
        }
        for (e in list) pause(e.id)
        NoxLog.event("pause-all", "count" to list.size)
        return list.size
    }

    /** Продолжить все пользовательские паузы. Ошибки не трогаем — их повторяют по одной. */
    suspend fun resumeAll(): Int {
        val list = downloadsDao.byStatus(DownloadStatus.PAUSED)
        for (e in list) resume(e.id)
        NoxLog.event("resume-all", "count" to list.size)
        return list.size
    }

    suspend fun cancel(id: Long) {
        val e = downloadsDao.get(id) ?: return
        cancelRequested.add(id)
        active[id]?.cancel(CancellationException("cancel"))
        merging[id]?.cancel(CancellationException("cancel"))
        withContext(NonCancellable) {
            (listOfNotNull(active[id], merging[id])).forEach { runCatching { it.join() } }
            deleteFilesOf(e)
            downloadsDao.delete(e)
        }
        cancelRequested.remove(id)
        listener?.onCleared(id)
        NoxLog.event("job-cancelled", "id" to id)
        pump()
    }

    suspend fun cancelMany(ids: Collection<Long>) {
        for (id in ids) cancel(id)
    }

    /** Убрать из списка завершённое или сбойное задание (файлы медиатеки не трогаются). */
    suspend fun remove(id: Long) {
        val e = downloadsDao.get(id) ?: return
        if (e.status.isPending) {
            cancel(id); return
        }
        if (e.status == DownloadStatus.ERROR || e.status == DownloadStatus.PAUSED) deleteFilesOf(e)
        downloadsDao.delete(e)
        listener?.onCleared(id)
    }

    /** Убрать из списка все готовые (видео остаются в медиатеке). */
    suspend fun clearCompleted(): Int {
        val list = downloadsDao.byStatus(DownloadStatus.COMPLETED)
        list.forEach { downloadsDao.delete(it) }
        return list.size
    }

    /**
     * Переименовать задание. Пока файл качается, меняется только
     * отображаемое название; физическое имя файл получит при завершении.
     */
    suspend fun rename(id: Long, title: String) {
        val e = downloadsDao.get(id) ?: return
        downloadsDao.update(e.copy(customTitle = title.trim(), updatedAt = System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------
    //  Обновление приложения
    // ------------------------------------------------------------------

    /**
     * Контрольная точка перед установкой нового APK. Идущие передачи и
     * склейки останавливаются штатной отменой, их файлы закрываются, размер
     * .part записывается в базу. Такие задания возвращаются в очередь с
     * причиной «update» и продолжатся после обновления сами. Пользовательские
     * паузы остаются паузами. Пока держится [updateHold], новые передачи не
     * стартуют.
     */
    suspend fun checkpointForUpdate(): Int {
        _updateHold.value = true
        val ids = active.keys.toList()
        for (id in ids) {
            updateStopping.add(id)
            active[id]?.cancel(CancellationException(STOP_UPDATE))
        }
        for ((_, job) in merging) job.cancel(CancellationException(STOP_UPDATE))
        withTimeoutOrNull(20_000) { (active.values + merging.values).toList().joinAll() }
        val now = System.currentTimeMillis()
        for (e in downloadsDao.getAll()) {
            if (e.id in ids && e.status == DownloadStatus.QUEUED) {
                downloadsDao.update(e.copy(lastStopReason = STOP_UPDATE, downloadedBytes = partSize(e), updatedAt = now))
            }
        }
        NoxLog.event("update-checkpoint", "stopped" to ids.size, "merges" to merging.size)
        return ids.size
    }

    /** Установка отменена или не удалась: всё продолжается как было. */
    fun releaseUpdateHold() {
        if (!_updateHold.value) return
        _updateHold.value = false
        updateStopping.clear()
        NoxLog.event("update-hold-released")
        pump()
    }

    // ------------------------------------------------------------------
    //  Восстановление
    // ------------------------------------------------------------------

    /**
     * При старте процесса: всё, что числилось «в работе», в работе уже не
     * находится — процесс новый. Возвращаем в очередь, размер берём с
     * диска, ничего не считаем завершённым без проверки.
     */
    suspend fun recover() {
        val now = System.currentTimeMillis()
        var restored = 0
        for (e in downloadsDao.getAll()) {
            when (e.status) {
                DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING -> {
                    downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, downloadedBytes = partSize(e),
                        speedBps = 0, etaSec = -1, updatedAt = now))
                    restored++
                }
                DownloadStatus.PROCESSING -> {
                    val final = finalFileOf(e)
                    val partsGone = !partFileOf(e).exists() && !videoPartOf(e).exists() && !audioPartOf(e).exists()
                    if (final != null && final.exists() && final.length() > 0 && partsGone) {
                        // Переименование успело, запись в медиатеку — нет.
                        finishIntoLibrary(e.copy(status = DownloadStatus.PROCESSING), final)
                    } else {
                        // Склейка или завершение прервались: начнутся заново с уже скачанных частей.
                        downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, downloadedBytes = partSize(e), updatedAt = now))
                    }
                    restored++
                }
                DownloadStatus.PAUSED -> {
                    val size = partSize(e)
                    if (size != e.downloadedBytes) downloadsDao.update(e.copy(downloadedBytes = size, updatedAt = now))
                }
                else -> Unit
            }
            // Остатки прерванной склейки.
            mergeTmpOf(e).delete()
        }
        NoxLog.event("recover", "restored" to restored)
        scope.launch { runCatching { relocator?.resumePending() } }
        // Если носитель уже подключился раньше, чем мы дошли сюда, —
        // возвращённые в очередь задания надо запустить прямо сейчас.
        pump()
    }

    // ------------------------------------------------------------------
    //  Носитель остановлен системой
    // ------------------------------------------------------------------

    /**
     * onStopJob / onDestroy: все передачи обрываются, .part остаются,
     * задания возвращаются в очередь (или в паузу, если остановил сам
     * пользователь), чтобы следующий носитель продолжил с того же места.
     */
    fun stopAll(reason: String, asPaused: Boolean) {
        lastStopReason = reason
        NoxLog.event("runner-stop", "reason" to reason, "active" to active.size, "asPaused" to asPaused)
        val ids = active.keys.toList()
        for (id in ids) {
            if (asPaused) pauseRequested.add(id)
            active[id]?.cancel(CancellationException("runner-stop:$reason"))
        }
        for ((_, job) in merging) job.cancel(CancellationException("runner-stop:$reason"))
        runBlocking {
            val now = System.currentTimeMillis()
            for (e in downloadsDao.getAll()) {
                if (e.status.isActive) {
                    downloadsDao.update(e.copy(
                        status = if (asPaused) DownloadStatus.PAUSED else DownloadStatus.QUEUED,
                        downloadedBytes = partSize(e), speedBps = 0, etaSec = -1,
                        lastStopReason = reason, updatedAt = now))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Очередь
    // ------------------------------------------------------------------

    /** Занять свободные слоты. Дёшево, идемпотентно, зовётся отовсюду. */
    fun pump() {
        scope.launch { pumpNow() }
    }

    private suspend fun pumpNow() {
        pumpLock.withLock {
            if (!runnerAttached || _updateHold.value) return
            var started = 0
            val queued = downloadsDao.byStatus(DownloadStatus.QUEUED)
            val toStart = QueuePolicy.pick(
                queuedInOrder = queued.map { it.id },
                active = active.keys.toSet(),
                blocked = pauseRequested + cancelRequested,
            )
            for (id in toStart) {
                val job = scope.launch { runOne(id) }
                active[id] = job
                _activeCount.value = active.size
                started++
                job.invokeOnCompletion {
                    active.remove(id)
                    _activeCount.value = active.size
                    scope.launch { afterWorkFinished() }
                }
            }
            // Склейки, ожидающие своего бюджета (в том числе после перезапуска).
            for (e in downloadsDao.byStatus(DownloadStatus.PROCESSING)) {
                if (e.isSplit && e.videoDone && e.audioDone && !merging.containsKey(e.id)) scheduleMerge(e.id)
            }
            if (started > 0) NoxLog.event("pump", "started" to started, "active" to active.size)
            if (active.isEmpty() && merging.isEmpty() && queued.isEmpty() && !hasWork()) onIdle?.invoke()
        }
    }

    private suspend fun afterWorkFinished() {
        pumpNow()
        if (active.isEmpty() && merging.isEmpty() && !hasWork()) onIdle?.invoke()
    }

    // ------------------------------------------------------------------
    //  Одна передача
    // ------------------------------------------------------------------

    private enum class Track { PROGRESSIVE, VIDEO, AUDIO }

    private suspend fun runOne(id: Long) {
        try {
            loop@ while (true) {
                var e = downloadsDao.get(id) ?: return
                if (e.status == DownloadStatus.PAUSED || e.status == DownloadStatus.COMPLETED) return

                // Обе дорожки уже на диске — дальше склейка в своём бюджете.
                if (e.isSplit && e.videoDone && e.audioDone) {
                    toMerge(e); return
                }

                // 1) Разбор ссылки, если прямого адреса нет.
                val needResolve = e.resolvedUrl.isBlank() || (e.isSplit && !e.audioDone && e.audioUrl.isBlank())
                if (needResolve) {
                    downloadsDao.setStatus(id, DownloadStatus.RESOLVING, "", System.currentTimeMillis())
                    // Если часть файла уже скачана, просим тот же формат —
                    // иначе .part продолжился бы байтами другого файла.
                    val hasVideoData = partFileOf(e).exists() || videoPartOf(e).exists() || e.videoDone
                    val hasAudioData = audioPartOf(e).exists() || e.audioDone
                    val r = resolver.resolve(
                        e.pageUrl, Quality.fromKey(e.quality), allowSplit = e.allowSplit,
                        preferFormat = if (hasVideoData) e.formatId else "",
                        preferAudio = if (hasAudioData && e.isSplit) e.audioFormatId else "",
                    )
                    if (!r.ok) {
                        val tries = e.resolveRetries + 1
                        if (tries >= RESOLVE_RETRIES || r.kind == "no-direct-format") {
                            fail(id, r.error.ifBlank { "не удалось разобрать ссылку" })
                            return
                        }
                        downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, resolveRetries = tries,
                            error = r.error, updatedAt = System.currentTimeMillis()))
                        delay(3000L * tries)
                        continue@loop
                    }
                    e = applyResolve(e, r)
                    downloadsDao.update(e)
                    if (e.isSplit && e.videoDone && e.audioDone) { toMerge(e); return }
                } else {
                    downloadsDao.setStatus(id, DownloadStatus.DOWNLOADING, "", System.currentTimeMillis())
                }

                // 2) Передача. Раздельные дорожки идут по очереди в этом же слоте.
                val track = when {
                    !e.isSplit -> Track.PROGRESSIVE
                    !e.videoDone -> Track.VIDEO
                    else -> Track.AUDIO
                }
                val part = when (track) {
                    Track.PROGRESSIVE -> partFileOf(e)
                    Track.VIDEO -> videoPartOf(e)
                    Track.AUDIO -> audioPartOf(e)
                }
                val url = if (track == Track.AUDIO) e.audioUrl else e.resolvedUrl
                val headers = headersOf(if (track == Track.AUDIO) e.audioHeadersJson else e.headersJson)
                // Уже готовая часть другой дорожки входит в общий прогресс.
                val baseOther = when (track) {
                    Track.PROGRESSIVE -> 0L
                    Track.VIDEO -> fileLen(audioPartOf(e))
                    Track.AUDIO -> fileLen(videoPartOf(e))
                }
                val otherTotal = when (track) {
                    Track.PROGRESSIVE -> 0L
                    Track.VIDEO -> e.audioTotalBytes
                    Track.AUDIO -> e.videoTotalBytes
                }
                val meter = SpeedMeter(part.length() + baseOther)
                var lastWrite = 0L
                NoxLog.event("transfer-start", "id" to id, "track" to track.name, "part" to part.length(),
                    "host" to SafeUrl.host(url), "headers" to headers.keys.joinToString(","))
                val outcome = http.download(url, headers, part) { downloaded, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastWrite >= PROGRESS_WRITE_EVERY_MS) {
                        lastWrite = now
                        val overall = downloaded + baseOther
                        val t = when {
                            track == Track.PROGRESSIVE -> if (total > 0) total else e.totalBytes
                            total > 0 && otherTotal > 0 -> total + otherTotal
                            else -> e.totalBytes
                        }
                        val (speed, eta) = meter.sample(overall, t, now)
                        // Запись в Room — отдельной корутиной: поток чтения
                        // сокета ждать базу не должен.
                        scope.launch { downloadsDao.updateProgress(id, overall, t, speed, eta, now) }
                    }
                }

                // 3) Итог.
                when (outcome) {
                    is HttpDownloader.Outcome.Completed -> {
                        when (track) {
                            Track.PROGRESSIVE -> {
                                withContext(NonCancellable) { complete(id, outcome.totalBytes) }
                                return
                            }
                            Track.VIDEO, Track.AUDIO -> {
                                val fresh = downloadsDao.get(id) ?: return
                                val size = part.length()
                                val next = if (track == Track.VIDEO) fresh.copy(videoDone = true, videoTotalBytes = size)
                                else fresh.copy(audioDone = true, audioTotalBytes = size)
                                downloadsDao.update(next.copy(retries = 0, totalBytes = next.videoTotalBytes + next.audioTotalBytes,
                                    downloadedBytes = partSize(next), updatedAt = System.currentTimeMillis()))
                                NoxLog.event("track-done", "id" to id, "track" to track.name, "size" to size)
                                continue@loop
                            }
                        }
                    }
                    is HttpDownloader.Outcome.Expired -> {
                        val fresh = downloadsDao.get(id) ?: return
                        val tries = fresh.resolveRetries + 1
                        NoxLog.event("transfer-expired", "id" to id, "code" to outcome.code, "tries" to tries,
                            "part" to part.length())
                        if (tries > RESOLVE_RETRIES) {
                            fail(id, "ссылка устарела (HTTP ${outcome.code})"); return
                        }
                        // .part не трогаем: свежий адрес продолжит с того же места.
                        downloadsDao.update(fresh.copy(resolvedUrl = "", audioUrl = "", resolveRetries = tries,
                            downloadedBytes = partSize(fresh), updatedAt = System.currentTimeMillis()))
                        continue@loop
                    }
                    is HttpDownloader.Outcome.Failed -> {
                        val fresh = downloadsDao.get(id) ?: return
                        val tries = fresh.retries + 1
                        NoxLog.event("transfer-failed", "id" to id, "code" to outcome.code,
                            "error" to outcome.message.take(120), "tries" to tries, "part" to part.length())
                        if (tries >= HTTP_RETRIES) {
                            fail(id, outcome.message); return
                        }
                        downloadsDao.update(fresh.copy(retries = tries, error = outcome.message,
                            downloadedBytes = partSize(fresh), speedBps = 0, etaSec = -1,
                            updatedAt = System.currentTimeMillis()))
                        delay(minOf(30_000L, 2000L * (1L shl minOf(tries, 4))))
                        continue@loop
                    }
                    HttpDownloader.Outcome.Cancelled -> {
                        withContext(NonCancellable) { afterCancel(id, part) }
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { afterCancel(id, null) }
        } catch (t: Throwable) {
            NoxLog.event("transfer-crash", "id" to id, "error" to "${t.javaClass.simpleName}: ${t.message?.take(160)}")
            withContext(NonCancellable) { fail(id, "внутренняя ошибка: ${t.javaClass.simpleName}") }
        }
    }

    /**
     * Применить свежий разбор. Если формат сменился относительно уже
     * скачанной части — эта часть не может быть продолжена и удаляется.
     */
    private fun applyResolve(e: DownloadEntity, r: YtDlpResolver.Result): DownloadEntity {
        val newMode = if (r.isSplit) DownloadMode.SPLIT else DownloadMode.PROGRESSIVE
        var videoDone = e.videoDone
        var audioDone = e.audioDone
        val modeChanged = e.formatId.isNotBlank() && e.mode != newMode
        if (modeChanged) {
            partFileOf(e).delete(); videoPartOf(e).delete(); audioPartOf(e).delete()
            videoDone = false; audioDone = false
            NoxLog.event("resolve-mode-changed", "id" to e.id, "from" to e.mode, "to" to newMode)
        } else {
            if (e.formatId.isNotBlank() && e.formatId != r.formatId) {
                partFileOf(e).delete(); videoPartOf(e).delete(); videoDone = false
                NoxLog.event("resolve-format-changed", "id" to e.id)
            }
            if (newMode == DownloadMode.SPLIT && e.audioFormatId.isNotBlank() && e.audioFormatId != r.audioFormatId) {
                audioPartOf(e).delete(); audioDone = false
            }
        }
        val fileName = if (e.fileName.isNotBlank()) e.fileName
        else FileNames.unique(storage.media, FileNames.targetName(e.customTitle.ifBlank { r.title }, r.videoId, r.ext)).name
        val total = when {
            newMode == DownloadMode.SPLIT && r.filesize > 0 && r.audioFilesize > 0 -> r.filesize + r.audioFilesize
            newMode == DownloadMode.PROGRESSIVE && e.totalBytes > 0 && !modeChanged -> e.totalBytes
            newMode == DownloadMode.PROGRESSIVE -> r.filesize
            else -> 0L
        }
        return e.copy(
            title = r.title, videoId = r.videoId, formatId = r.formatId,
            resolvedUrl = r.directUrl, headersJson = JSONObject(r.headers).toString(),
            fileName = fileName, ext = r.ext, height = r.height,
            totalBytes = total,
            thumbnailUrl = r.thumbnail.ifBlank { e.thumbnailUrl },
            durationSec = if (r.durationSec > 0) r.durationSec else e.durationSec,
            uploader = r.uploader.ifBlank { e.uploader },
            mode = newMode,
            audioUrl = r.audioUrl, audioFormatId = r.audioFormatId,
            audioHeadersJson = JSONObject(r.audioHeaders).toString(),
            videoTotalBytes = if (videoDone) e.videoTotalBytes else r.filesize,
            audioTotalBytes = if (audioDone) e.audioTotalBytes else r.audioFilesize,
            videoDone = videoDone, audioDone = audioDone,
            status = DownloadStatus.DOWNLOADING, error = "",
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun afterCancel(id: Long, part: File?) {
        val e = downloadsDao.get(id) ?: run { cancelRequested.remove(id); return }
        val size = partSize(e)
        when {
            cancelRequested.contains(id) -> Unit                     // запись удалит cancel()
            pauseRequested.remove(id) -> {
                val paused = e.copy(status = DownloadStatus.PAUSED, downloadedBytes = size,
                    speedBps = 0, etaSec = -1, updatedAt = System.currentTimeMillis())
                downloadsDao.update(paused)
                NoxLog.event("job-paused", "id" to id, "part" to size)
                listener?.onPaused(paused)
            }
            else -> {
                // Носитель остановлен системой или ставится обновление:
                // в очередь, продолжим с .part.
                val reason = if (updateStopping.remove(id)) STOP_UPDATE else e.lastStopReason
                if (e.status.isActive) {
                    downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, downloadedBytes = size,
                        speedBps = 0, etaSec = -1, lastStopReason = reason, updatedAt = System.currentTimeMillis()))
                }
                NoxLog.event("job-requeued", "id" to id, "part" to size, "reason" to reason.ifBlank { null })
            }
        }
        if (part == null) Unit
    }

    private suspend fun fail(id: Long, message: String) {
        val e = downloadsDao.get(id) ?: return
        downloadsDao.update(e.copy(status = DownloadStatus.ERROR, error = message.take(300),
            downloadedBytes = partSize(e), speedBps = 0, etaSec = -1, updatedAt = System.currentTimeMillis()))
        NoxLog.event("job-error", "id" to id, "error" to message.take(160))
    }

    /** Имя итогового файла: название пользователя, если он его задал. */
    private fun finalNameOf(e: DownloadEntity): String =
        if (e.customTitle.isNotBlank()) FileNames.targetName(e.customTitle, e.videoId, e.ext) else e.fileName

    private suspend fun complete(id: Long, total: Long) {
        val e = downloadsDao.get(id) ?: return
        val part = partFileOf(e)
        if (!part.exists()) { fail(id, ".part исчез до завершения"); return }
        if (total > 0 && part.length() != total) {
            fail(id, "размер не совпал: ${part.length()} из $total"); return
        }
        downloadsDao.update(e.copy(status = DownloadStatus.PROCESSING, downloadedBytes = part.length(),
            totalBytes = part.length(), speedBps = 0, etaSec = -1, updatedAt = System.currentTimeMillis()))
        val final = FileNames.unique(storage.media, finalNameOf(e))
        if (!moveInto(part, final)) { fail(id, "не удалось переименовать файл"); return }
        NoxLog.event("transfer-complete", "id" to id, "size" to final.length(), "file" to final.name)
        finishIntoLibrary(e.copy(fileName = final.name), final)
    }

    private fun moveInto(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        return try {
            src.copyTo(dst, overwrite = true); src.delete(); true
        } catch (ex: Exception) {
            dst.delete(); false
        }
    }

    // ------------------------------------------------------------------
    //  Склейка раздельных дорожек
    // ------------------------------------------------------------------

    private suspend fun toMerge(e: DownloadEntity) {
        downloadsDao.update(e.copy(status = DownloadStatus.PROCESSING, speedBps = 0, etaSec = -1,
            downloadedBytes = partSize(e), updatedAt = System.currentTimeMillis()))
        scheduleMerge(e.id)
    }

    private fun scheduleMerge(id: Long) {
        if (!runnerAttached || _updateHold.value) return
        if (merging.containsKey(id)) return
        val job = scope.launch(mergeDispatcher) { runMerge(id) }
        merging[id] = job
        job.invokeOnCompletion {
            merging.remove(id)
            scope.launch { afterWorkFinished() }
        }
    }

    private suspend fun runMerge(id: Long) {
        val e = downloadsDao.get(id) ?: return
        if (e.status != DownloadStatus.PROCESSING) return
        val v = videoPartOf(e)
        val a = audioPartOf(e)
        if (!v.exists() || !a.exists()) {
            // Дорожка пропала: докачаем недостающую, готовую не трогаем.
            downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, videoDone = e.videoDone && v.exists(),
                audioDone = e.audioDone && a.exists(), updatedAt = System.currentTimeMillis()))
            return
        }
        val tmp = mergeTmpOf(e)
        NoxLog.event("merge-start", "id" to id, "video" to v.length(), "audio" to a.length())
        try {
            MediaMerger.merge(v, a, tmp)
            val probe = MediaMerger.probe(tmp)
            if (!probe.hasVideo || !probe.hasAudio || probe.durationUs <= 0) {
                tmp.delete()
                fail(id, "после склейки нет изображения или звука"); return
            }
            withContext(NonCancellable) {
                val fresh = downloadsDao.get(id) ?: return@withContext
                val final = FileNames.unique(storage.media, finalNameOf(fresh))
                if (!moveInto(tmp, final)) { fail(id, "не удалось сохранить склеенный файл"); return@withContext }
                v.delete(); a.delete()
                NoxLog.event("merge-done", "id" to id, "size" to final.length(), "video" to probe.videoMime, "audio" to probe.audioMime)
                finishIntoLibrary(fresh.copy(fileName = final.name), final)
            }
        } catch (c: CancellationException) {
            tmp.delete()
            NoxLog.event("merge-cancelled", "id" to id)
            throw c
        } catch (t: Throwable) {
            tmp.delete()
            NoxLog.event("merge-error", "id" to id, "error" to "${t.javaClass.simpleName}: ${t.message?.take(120)}")
            withContext(NonCancellable) { fail(id, "склейка не удалась: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------------------------
    //  Медиатека
    // ------------------------------------------------------------------

    private suspend fun finishIntoLibrary(e: DownloadEntity, final: File) {
        val cover = fetchCover(e, final)
        val mediaId = db.media().insert(
            MediaEntity(title = e.displayTitle.ifBlank { final.nameWithoutExtension }, filePath = final.absolutePath,
                sizeBytes = final.length(), quality = e.quality, height = e.height, durationSec = e.durationSec,
                coverPath = cover, pageUrl = e.pageUrl, videoId = e.videoId, createdAt = System.currentTimeMillis(),
                uploader = e.uploader)
        )
        downloadsDao.update(e.copy(status = DownloadStatus.COMPLETED, fileName = final.name,
            downloadedBytes = final.length(), totalBytes = final.length(), error = "",
            updatedAt = System.currentTimeMillis()))
        NoxLog.event("library-added", "media" to mediaId, "download" to e.id, "cover" to cover.isNotEmpty())
        relocator?.let { r -> scope.launch { runCatching { r.afterDownload(mediaId) } } }
    }

    /** Обложка — необязательный шаг: без неё файл всё равно в медиатеке. */
    private fun fetchCover(e: DownloadEntity, final: File): String {
        val url = e.thumbnailUrl
        if (url.isBlank() || !url.startsWith("http")) return ""
        return try {
            val req = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val type = resp.header("Content-Type") ?: ""
                val ext = when {
                    type.contains("png") -> "png"
                    type.contains("webp") -> "webp"
                    else -> "jpg"
                }
                val body = resp.body ?: return ""
                val len = body.contentLength()
                if (len > COVER_LIMIT) return ""
                val target = FileNames.unique(storage.covers, final.nameWithoutExtension + ".$ext")
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        var copied = 0L
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); copied += n
                            if (copied > COVER_LIMIT) { target.delete(); return "" }
                        }
                    }
                }
                target.absolutePath
            }
        } catch (ex: Exception) {
            NoxLog.event("cover-error", "id" to e.id, "error" to (ex.message ?: ex.javaClass.simpleName).take(120))
            ""
        }
    }

    // ------------------------------------------------------------------
    //  Файлы
    // ------------------------------------------------------------------

    private fun baseName(e: DownloadEntity) = if (e.fileName.isNotBlank()) e.fileName else "download-${e.id}.mp4"

    fun partFileOf(e: DownloadEntity): File = File(storage.downloads, "${baseName(e)}.part")
    fun videoPartOf(e: DownloadEntity): File = File(storage.downloads, "${baseName(e)}.video.part")
    fun audioPartOf(e: DownloadEntity): File = File(storage.downloads, "${baseName(e)}.audio.part")
    private fun mergeTmpOf(e: DownloadEntity): File = File(storage.downloads, "${baseName(e)}.merge.tmp")

    private fun finalFileOf(e: DownloadEntity): File? =
        if (e.fileName.isBlank()) null else File(storage.media, e.fileName)

    private fun fileLen(f: File) = if (f.exists()) f.length() else 0L

    /** Сколько байт этого задания реально лежит на диске. */
    fun partSize(e: DownloadEntity): Long =
        if (e.isSplit) fileLen(videoPartOf(e)) + fileLen(audioPartOf(e)) else fileLen(partFileOf(e))

    private fun deleteFilesOf(e: DownloadEntity) {
        for (f in listOf(partFileOf(e), videoPartOf(e), audioPartOf(e), mergeTmpOf(e))) {
            try { f.delete() } catch (_: Exception) { }
        }
    }

    private fun headersOf(json: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) out[key] = obj.optString(key)
        } catch (_: Exception) { }
        return out
    }

    /** Скорость и ETA по скользящему окну — та же арифметика, что в NOX. */
    private class SpeedMeter(start: Long) {
        private var markBytes = start
        private var markTime = System.currentTimeMillis()
        private var speed = 0L

        fun sample(downloaded: Long, total: Long, now: Long): Pair<Long, Long> {
            val dt = now - markTime
            if (dt >= SPEED_WINDOW_MS) {
                speed = ((downloaded - markBytes) * 1000L / dt).coerceAtLeast(0)
                markBytes = downloaded
                markTime = now
            }
            val eta = if (speed > 0 && total > 0) ((total - downloaded) / speed).coerceAtLeast(0) else -1L
            return speed to eta
        }
    }
}
