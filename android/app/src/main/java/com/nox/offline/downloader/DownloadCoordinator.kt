package com.nox.offline.downloader

import com.nox.offline.core.FileNames
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
import com.nox.offline.core.Storage
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.NoxDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 */
class DownloadCoordinator(
    private val db: NoxDatabase,
    private val storage: Storage,
    private val resolver: YtDlpResolver,
    private val http: HttpDownloader,
    private val client: OkHttpClient,
) {
    companion object {
        const val MAX_CONCURRENT = 3
        const val HTTP_RETRIES = 8
        const val RESOLVE_RETRIES = 3
        const val PROGRESS_WRITE_EVERY_MS = 500L
        const val SPEED_WINDOW_MS = 1500L
        const val COVER_LIMIT = 8L * 1024 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pumpLock = Mutex()
    private val active = ConcurrentHashMap<Long, Job>()
    private val pauseRequested: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val cancelRequested: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Сколько передач идёт прямо сейчас. */
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount

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

    suspend fun enqueue(pageUrl: String, quality: Quality): Result<Long> {
        val url = pageUrl.trim()
        if (!SafeUrl.looksLikeUrl(url)) return Result.failure(IllegalArgumentException("Это не похоже на ссылку"))
        if (downloadsDao.countLiveFor(url) > 0) return Result.failure(IllegalStateException("Эта загрузка уже есть"))
        val now = System.currentTimeMillis()
        val id = downloadsDao.insert(
            DownloadEntity(pageUrl = url, quality = quality.key, createdAt = now, updatedAt = now)
        )
        NoxLog.event("job-added", "id" to id, "host" to SafeUrl.host(url), "quality" to quality.key)
        pump()
        return Result.success(id)
    }

    suspend fun pause(id: Long) {
        val e = downloadsDao.get(id) ?: return
        if (e.status == DownloadStatus.PAUSED || e.status == DownloadStatus.COMPLETED) return
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
                    downloadedBytes = partSize(e), updatedAt = System.currentTimeMillis())
            )
            NoxLog.event("job-resume", "id" to id, "part" to partSize(e))
            listener?.onCleared(id)
        }
        pump()
    }

    suspend fun cancel(id: Long) {
        val e = downloadsDao.get(id) ?: return
        cancelRequested.add(id)
        active[id]?.cancel(CancellationException("cancel"))
        withContext(NonCancellable) {
            deleteFilesOf(e)
            downloadsDao.delete(e)
        }
        listener?.onCleared(id)
        NoxLog.event("job-cancelled", "id" to id)
        pump()
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
                    if (final != null && final.exists() && final.length() > 0 && !partFileOf(e).exists()) {
                        // Переименование успело, запись в медиатеку — нет.
                        finishIntoLibrary(e.copy(status = DownloadStatus.PROCESSING), final)
                    } else {
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
        }
        NoxLog.event("recover", "restored" to restored)
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
            if (!runnerAttached) return
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
                    scope.launch {
                        pumpNow()
                        if (active.isEmpty() && !hasWork()) onIdle?.invoke()
                    }
                }
            }
            if (started > 0) NoxLog.event("pump", "started" to started, "active" to active.size)
            if (active.isEmpty() && queued.isEmpty() && !hasWork()) onIdle?.invoke()
        }
    }

    // ------------------------------------------------------------------
    //  Одна передача
    // ------------------------------------------------------------------

    private suspend fun runOne(id: Long) {
        try {
            loop@ while (true) {
                var e = downloadsDao.get(id) ?: return
                if (e.status == DownloadStatus.PAUSED || e.status == DownloadStatus.COMPLETED) return

                // 1) Разбор ссылки, если прямого адреса нет.
                if (e.resolvedUrl.isBlank()) {
                    downloadsDao.setStatus(id, DownloadStatus.RESOLVING, "", System.currentTimeMillis())
                    val r = resolver.resolve(e.pageUrl, Quality.fromKey(e.quality))
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
                    val fileName = if (e.fileName.isNotBlank()) e.fileName
                    else FileNames.unique(storage.media, FileNames.targetName(r.title, r.videoId, r.ext)).name
                    e = e.copy(
                        title = r.title, videoId = r.videoId, formatId = r.formatId,
                        resolvedUrl = r.directUrl, headersJson = JSONObject(r.headers).toString(),
                        fileName = fileName, ext = r.ext, height = r.height,
                        totalBytes = if (e.totalBytes > 0) e.totalBytes else r.filesize,
                        thumbnailUrl = r.thumbnail.ifBlank { e.thumbnailUrl },
                        durationSec = if (r.durationSec > 0) r.durationSec else e.durationSec,
                        status = DownloadStatus.DOWNLOADING, error = "",
                        updatedAt = System.currentTimeMillis(),
                    )
                    downloadsDao.update(e)
                } else {
                    downloadsDao.setStatus(id, DownloadStatus.DOWNLOADING, "", System.currentTimeMillis())
                }

                // 2) Передача.
                val part = partFileOf(e)
                val headers = headersOf(e)
                val meter = SpeedMeter(partSize(e))
                var lastWrite = 0L
                NoxLog.event("transfer-start", "id" to id, "part" to part.length(),
                    "host" to SafeUrl.host(e.resolvedUrl), "headers" to headers.keys.joinToString(","))
                val outcome = http.download(e.resolvedUrl, headers, part) { downloaded, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastWrite >= PROGRESS_WRITE_EVERY_MS) {
                        lastWrite = now
                        val (speed, eta) = meter.sample(downloaded, total, now)
                        val t = if (total > 0) total else e.totalBytes
                        // Запись в Room — отдельной корутиной: поток чтения
                        // сокета ждать базу не должен.
                        scope.launch { downloadsDao.updateProgress(id, downloaded, t, speed, eta, now) }
                    }
                }

                // 3) Итог.
                when (outcome) {
                    is HttpDownloader.Outcome.Completed -> {
                        withContext(NonCancellable) { complete(id, outcome.totalBytes) }
                        return
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
                        downloadsDao.update(fresh.copy(resolvedUrl = "", resolveRetries = tries,
                            downloadedBytes = part.length(), updatedAt = System.currentTimeMillis()))
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
                            downloadedBytes = part.length(), speedBps = 0, etaSec = -1,
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

    private suspend fun afterCancel(id: Long, part: File?) {
        val e = downloadsDao.get(id) ?: run { cancelRequested.remove(id); return }
        val size = (part ?: partFileOf(e)).let { if (it.exists()) it.length() else 0L }
        when {
            cancelRequested.remove(id) -> Unit                       // запись уже удалена в cancel()
            pauseRequested.remove(id) -> {
                val paused = e.copy(status = DownloadStatus.PAUSED, downloadedBytes = size,
                    speedBps = 0, etaSec = -1, updatedAt = System.currentTimeMillis())
                downloadsDao.update(paused)
                NoxLog.event("job-paused", "id" to id, "part" to size)
                listener?.onPaused(paused)
            }
            else -> {
                // Носитель остановлен системой: в очередь, продолжим с .part.
                if (e.status.isActive) {
                    downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, downloadedBytes = size,
                        speedBps = 0, etaSec = -1, updatedAt = System.currentTimeMillis()))
                }
                NoxLog.event("job-requeued", "id" to id, "part" to size)
            }
        }
    }

    private suspend fun fail(id: Long, message: String) {
        val e = downloadsDao.get(id) ?: return
        downloadsDao.update(e.copy(status = DownloadStatus.ERROR, error = message.take(300),
            downloadedBytes = partSize(e), speedBps = 0, etaSec = -1, updatedAt = System.currentTimeMillis()))
        NoxLog.event("job-error", "id" to id, "error" to message.take(160))
    }

    private suspend fun complete(id: Long, total: Long) {
        val e = downloadsDao.get(id) ?: return
        val part = partFileOf(e)
        if (!part.exists()) { fail(id, ".part исчез до завершения"); return }
        if (total > 0 && part.length() != total) {
            fail(id, "размер не совпал: ${part.length()} из $total"); return
        }
        downloadsDao.update(e.copy(status = DownloadStatus.PROCESSING, downloadedBytes = part.length(),
            totalBytes = part.length(), speedBps = 0, etaSec = -1, updatedAt = System.currentTimeMillis()))
        val final = FileNames.unique(storage.media, e.fileName)
        val renamed = part.renameTo(final)
        if (!renamed) {
            try {
                part.copyTo(final, overwrite = true); part.delete()
            } catch (ex: Exception) {
                fail(id, "не удалось переименовать файл: ${ex.message}"); return
            }
        }
        NoxLog.event("transfer-complete", "id" to id, "size" to final.length(), "file" to final.name)
        finishIntoLibrary(e.copy(fileName = final.name), final)
    }

    private suspend fun finishIntoLibrary(e: DownloadEntity, final: File) {
        val cover = fetchCover(e, final)
        val mediaId = db.media().insert(
            MediaEntity(title = e.title.ifBlank { final.nameWithoutExtension }, filePath = final.absolutePath,
                sizeBytes = final.length(), quality = e.quality, height = e.height, durationSec = e.durationSec,
                coverPath = cover, pageUrl = e.pageUrl, videoId = e.videoId, createdAt = System.currentTimeMillis())
        )
        downloadsDao.update(e.copy(status = DownloadStatus.COMPLETED, fileName = final.name,
            downloadedBytes = final.length(), totalBytes = final.length(), error = "",
            updatedAt = System.currentTimeMillis()))
        NoxLog.event("library-added", "media" to mediaId, "download" to e.id, "cover" to cover.isNotEmpty())
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
                val target = File(storage.covers, final.nameWithoutExtension + ".$ext")
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

    fun partFileOf(e: DownloadEntity): File {
        val name = if (e.fileName.isNotBlank()) e.fileName else "download-${e.id}.mp4"
        return File(storage.downloads, "$name.part")
    }

    private fun finalFileOf(e: DownloadEntity): File? =
        if (e.fileName.isBlank()) null else File(storage.media, e.fileName)

    fun partSize(e: DownloadEntity): Long = partFileOf(e).let { if (it.exists()) it.length() else 0L }

    private fun deleteFilesOf(e: DownloadEntity) {
        try { partFileOf(e).delete() } catch (_: Exception) { }
    }

    private fun headersOf(e: DownloadEntity): Map<String, String> {
        val out = linkedMapOf<String, String>()
        try {
            val json = JSONObject(e.headersJson)
            for (key in json.keys()) out[key] = json.optString(key)
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
