package com.nox.offline.downloader

import com.nox.offline.core.FileNames
import com.nox.offline.core.Format
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
import com.nox.offline.core.Storage
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadMode
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.NoxDatabase
import com.nox.offline.downloader.catalog.CodecNames
import com.nox.offline.downloader.catalog.PlanResult
import com.nox.offline.downloader.catalog.Support
import com.nox.offline.downloader.catalog.Variant
import com.nox.offline.downloader.catalog.VideoDetails
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
 *
 * v0.3.0: задания с точным планом из каталога (planVersion = 1). Для них
 * адреса берутся только для сохранённых format ID; если источник больше
 * не отдаёт выбранный вариант или отдаёт файл другого размера, задание
 * останавливается с понятной причиной, а скачанные части сохраняются —
 * к ним никогда не дописываются байты другого файла.
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
    private val policy: Policy = Policy(),
) {
    /**
     * 0.4.0: правила очереди из настроек. Меняются на лету: уменьшение
     * числа одновременных загрузок не обрывает идущие (новые просто не
     * стартуют), смена предела скорости действует со следующего куска.
     */
    class Policy(
        val concurrency: () -> Int = { MAX_CONCURRENT },
        val network: NetworkGate? = null,
        val limiter: SpeedLimiter? = null,
        /** Готовые видео уходят в другую папку (SAF) — там тоже нужно место. */
        val externalTarget: () -> Boolean = { false },
        /** Свободно в той папке, байт (-1 — неизвестно). */
        val externalFree: () -> Long = { -1L },
    )

    /** Медиатека узнаёт о новом файле: коллекция из плейлиста, ожидающие серии. */
    interface LibraryHook {
        suspend fun onMediaAdded(mediaId: Long, e: DownloadEntity)
    }

    @Volatile
    var libraryHook: LibraryHook? = null

    /** Куда сохранять скачанные субтитры (null — субтитры не качаются). */
    @Volatile
    var subtitleStore: com.nox.offline.subtitles.SubtitleRepository? = null

    companion object {
        const val MAX_CONCURRENT = 3
        const val HTTP_RETRIES = 8
        const val RESOLVE_RETRIES = 3
        const val PROGRESS_WRITE_EVERY_MS = 500L
        const val SPEED_WINDOW_MS = 1500L
        const val COVER_LIMIT = 8L * 1024 * 1024
        const val STOP_UPDATE = "update"
        /** Ждёт сети (нет связи или не Wi-Fi при «Только Wi-Fi»). Не пользовательская пауза. */
        const val STOP_NETWORK = "network"
        const val SPACE_MARGIN = 64L * 1024 * 1024
        /** Ошибки, после которых повтор разбора бессмыслен. */
        val FINAL_KINDS = setOf("private", "age-restricted", "members-only", "unavailable", "unsupported",
            "live", "drm", "geo-blocked", "unsupported-transport", "no-formats")
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
    private val networkStopping: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())

    init {
        policy.network?.onChanged = { state ->
            if (state == NetworkGate.State.OK) pump() else stopForNetwork()
        }
    }

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
                allowSplit = allowSplit, customTitle = customTitle.trim(), queueOrder = nextQueueOrder(now))
        )
        NoxLog.event("job-added", "id" to id, "host" to SafeUrl.host(url), "quality" to quality.key, "split" to allowSplit)
        pump()
        return Result.success(id)
    }

    /** Точный выбор пользователя из каталога. */
    data class DownloadRequest(
        val details: VideoDetails,
        val variant: Variant,
        val customTitle: String = "",
        /** 0.4.0: субтитры, которые скачать вместе с видео. */
        val subtitles: List<com.nox.offline.downloader.catalog.SourceSubtitle> = emptyList(),
        /** 0.4.0: коллекция (из плейлиста) и место в ней по порядку источника. */
        val collectionId: Long = 0,
        val collectionPosition: Long = 0,
    )

    /** Сколько места нужно варианту: дорожки + собранный файл, с запасом. */
    fun requiredSpace(v: Variant): Long {
        if (v.sizeBytes <= 0) return SPACE_MARGIN
        return (if (v.needsMerge) v.sizeBytes * 2 else v.sizeBytes) + SPACE_MARGIN
    }

    /** Проверка места на пике (части + объединение + папка назначения + очередь). */
    suspend fun checkSpace(sizeBytes: Long, approx: Boolean, needsMerge: Boolean): SpaceEstimate.Check {
        val need = SpaceEstimate.need(sizeBytes, approx, needsMerge, 0, downloadsDao.remainingKnownBytes(), policy.externalTarget())
        return SpaceEstimate.check(need, storage.space().freeBytes, policy.externalFree(), Format::bytes)
    }

    private suspend fun nextQueueOrder(now: Long): Long = maxOf(now, downloadsDao.maxQueueOrder() + 1)

    /** Уже скачано или уже в очереди — для плейлистов и повторного добавления. */
    suspend fun presence(extractor: String, videoId: String): String? {
        if (videoId.isBlank()) return null
        if (db.media().byVideoId(videoId).isNotEmpty()) return "уже в медиатеке"
        if (downloadsDao.liveByVideoId(videoId).any { it.extractorKey.equals(extractor, true) || extractor.isBlank() }) return "уже в очереди"
        return null
    }

    /** «Только звук»: отдельная звуковая дорожка источника без перекодирования. */
    data class AudioRequest(
        val details: VideoDetails,
        val variant: com.nox.offline.downloader.catalog.AudioVariant,
        val customTitle: String = "",
        val collectionId: Long = 0,
        val collectionPosition: Long = 0,
    )

    suspend fun enqueueAudio(request: AudioRequest): Result<Long> {
        val d = request.details
        val a = request.variant
        val t = a.track
        val url = d.pageUrl.trim()
        if (!SafeUrl.looksLikeUrl(url)) return Result.failure(IllegalArgumentException("Это не похоже на ссылку"))
        (a.support as? Support.No)?.let { return Result.failure(IllegalStateException(it.reason)) }
        if (d.videoId.isNotBlank() && downloadsDao.countLiveVariant(d.extractor, d.videoId, a.key) > 0) {
            return Result.failure(IllegalStateException("Этот звук уже в очереди"))
        }
        val space = checkSpace(a.sizeBytes, a.sizeKind != com.nox.offline.downloader.catalog.SizeKind.EXACT, false)
        if (!space.ok) return Result.failure(IllegalStateException(space.message))
        val now = System.currentTimeMillis()
        val name = uniqueJobFileName(FileNames.targetName(request.customTitle.ifBlank { d.title }, d.videoId, a.outputExt))
        val entity = DownloadEntity(
            pageUrl = url, quality = "Звук · ${a.title}", title = d.title, videoId = d.videoId,
            formatId = t.id, fileName = name, ext = a.outputExt, height = 0,
            totalBytes = a.sizeBytes, thumbnailUrl = d.thumbnail, durationSec = d.durationSec,
            createdAt = now, updatedAt = now, customTitle = request.customTitle.trim(),
            mode = DownloadMode.PROGRESSIVE, videoTotalBytes = t.knownSize,
            uploader = d.uploader, planVersion = 1, extractorKey = d.extractor, variantKey = a.key,
            acodec = t.acodec, container = t.container.ifBlank { a.outputExt }, audioLang = t.language,
            videoExact = t.filesizeExact && t.filesize > 0, videoChunk = t.chunkSize,
            audioOnly = true, collectionId = request.collectionId, collectionPosition = request.collectionPosition,
            queueOrder = nextQueueOrder(now),
        )
        val id = downloadsDao.insert(entity)
        NoxLog.event("job-added", "id" to id, "host" to SafeUrl.host(url), "source" to d.extractor, "variant" to a.key,
            "audioOnly" to true, "codec" to t.acodec, "size" to a.sizeBytes)
        pump()
        return Result.success(id)
    }

    /** «Скачать следующим»: то же задание (с его частями) поднимается в начало очереди. */
    suspend fun playNext(id: Long) {
        val e = downloadsDao.get(id) ?: return
        if (e.status == DownloadStatus.COMPLETED || e.status.isActive) return
        val top = downloadsDao.minQueueOrder() - 1
        downloadsDao.setQueueOrder(id, top, System.currentTimeMillis())
        NoxLog.event("job-next", "id" to id, "status" to e.status.name)
        if (e.status == DownloadStatus.PAUSED || e.status == DownloadStatus.ERROR) resume(id) else pump()
    }

    suspend fun pauseMany(ids: Collection<Long>) { for (id in ids) pause(id) }
    suspend fun resumeMany(ids: Collection<Long>) { for (id in ids) resume(id) }
    suspend fun removeMany(ids: Collection<Long>) { for (id in ids) remove(id) }

    /** Повторить все задания с ошибкой (части сохраняются). */
    suspend fun retryErrors(): Int {
        val list = downloadsDao.byStatus(DownloadStatus.ERROR)
        for (e in list) resume(e.id)
        return list.size
    }

    suspend fun enqueue(request: DownloadRequest): Result<Long> {
        val d = request.details
        val v = request.variant
        val url = d.pageUrl.trim()
        if (!SafeUrl.looksLikeUrl(url)) return Result.failure(IllegalArgumentException("Это не похоже на ссылку"))
        (v.support as? Support.No)?.let { return Result.failure(IllegalStateException(it.reason)) }
        if (d.videoId.isNotBlank() && downloadsDao.countLiveVariant(d.extractor, d.videoId, v.key) > 0) {
            return Result.failure(IllegalStateException("Этот вариант уже в очереди"))
        }
        val space = checkSpace(v.sizeBytes, v.sizeKind != com.nox.offline.downloader.catalog.SizeKind.EXACT, v.needsMerge)
        if (!space.ok) return Result.failure(IllegalStateException(space.message))
        val a = v.audio
        val now = System.currentTimeMillis()
        val name = uniqueJobFileName(FileNames.targetName(request.customTitle.ifBlank { d.title }, d.videoId, v.outputExt))
        val entity = DownloadEntity(
            pageUrl = url, quality = v.title, title = d.title, videoId = d.videoId,
            formatId = v.video.id, fileName = name, ext = v.outputExt, height = v.height,
            totalBytes = v.sizeBytes, thumbnailUrl = d.thumbnail, durationSec = d.durationSec,
            createdAt = now, updatedAt = now, customTitle = request.customTitle.trim(),
            mode = if (a != null) DownloadMode.SPLIT else DownloadMode.PROGRESSIVE,
            audioFormatId = a?.id.orEmpty(),
            videoTotalBytes = v.video.knownSize, audioTotalBytes = a?.knownSize ?: 0L,
            uploader = d.uploader, allowSplit = a != null,
            planVersion = 1, extractorKey = d.extractor, variantKey = v.key,
            width = v.width, fps = v.fps, vcodec = v.video.vcodec,
            acodec = a?.acodec ?: v.video.acodec, container = v.outputContainer,
            dynamicRange = v.dynamicRange, audioLang = a?.language.orEmpty(),
            videoExact = v.video.filesizeExact && v.video.filesize > 0,
            audioExact = a != null && a.filesizeExact && a.filesize > 0,
            videoChunk = v.video.chunkSize, audioChunk = a?.chunkSize ?: 0L,
            subtitleRequest = com.nox.offline.downloader.catalog.SourceSubtitle.listToJson(request.subtitles),
            collectionId = request.collectionId, collectionPosition = request.collectionPosition,
            queueOrder = nextQueueOrder(now),
        )
        val id = downloadsDao.insert(entity)
        NoxLog.event("job-added", "id" to id, "host" to SafeUrl.host(url), "source" to d.extractor,
            "variant" to v.key, "quality" to v.title, "container" to v.outputContainer, "size" to v.sizeBytes)
        pump()
        return Result.success(id)
    }

    /**
     * Новый выбор качества для остановленного задания (вариант пропал или
     * изменился). Старые части удаляются только здесь — по явному выбору
     * пользователя.
     */
    suspend fun replan(id: Long, request: DownloadRequest): Result<Long> {
        val old = downloadsDao.get(id) ?: return Result.failure(IllegalStateException("Задание не найдено"))
        if (old.status.isActive) return Result.failure(IllegalStateException("Задание ещё выполняется"))
        (request.variant.support as? Support.No)?.let { return Result.failure(IllegalStateException(it.reason)) }
        deleteFilesOf(old)
        downloadsDao.delete(old)
        listener?.onCleared(id)
        NoxLog.event("job-replanned", "id" to id, "variant" to request.variant.key)
        return enqueue(request.copy(customTitle = request.customTitle.ifBlank { old.customTitle },
            subtitles = request.subtitles.ifEmpty { com.nox.offline.downloader.catalog.SourceSubtitle.listFromJson(old.subtitleRequest) },
            collectionId = if (request.collectionId > 0) request.collectionId else old.collectionId,
            collectionPosition = if (request.collectionId > 0) request.collectionPosition else old.collectionPosition))
    }

    /** Имя итогового файла, не занятое ни файлом медиатеки, ни другим заданием. */
    private suspend fun uniqueJobFileName(name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var n = 2
        while (File(storage.media, candidate).exists() || downloadsDao.countByFileName(candidate) > 0) {
            candidate = "$stem ($n)$ext"
            n++
            if (n > 999) { candidate = "$stem (${System.currentTimeMillis()})$ext"; break }
        }
        return candidate
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
                e.copy(status = DownloadStatus.QUEUED, error = "", errorKind = "", retries = 0, resolveRetries = 0,
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
            val netOk = policy.network?.allowed() ?: true
            val toStart = if (!netOk) emptyList() else QueuePolicy.pick(
                queuedInOrder = queued.map { it.id },
                active = active.keys.toSet(),
                blocked = pauseRequested + cancelRequested,
                max = policy.concurrency().coerceIn(1, MAX_CONCURRENT),
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
            // Ждём сети: носитель не отпускаем — очередь стоит с причиной «network»,
            // а вернувшаяся сеть сама позовёт pump(). UIDT-job к тому же объявляет
            // системе нужный тип сети, и система сама перезапустит его при потере связи.
        }
    }

    /**
     * Сеть пропала или перестала подходить («Только Wi-Fi»): идущие передачи
     * останавливаются штатно, .part сохраняются, задания ждут в очереди с
     * причиной «network» — это не пользовательская пауза.
     */
    private fun stopForNetwork() {
        val ids = active.keys.toList()
        if (ids.isEmpty()) return
        NoxLog.event("network-stop", "active" to ids.size)
        for (id in ids) {
            networkStopping.add(id)
            active[id]?.cancel(CancellationException(STOP_NETWORK))
        }
    }

    /** Сбой передачи на пропавшей сети — не ошибка задания: ждать сети без траты попыток. */
    private suspend fun requeueIfNetworkLost(id: Long): Boolean {
        val gate = policy.network ?: return false
        if (gate.allowed()) return false
        val e = downloadsDao.get(id) ?: return true
        downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, downloadedBytes = partSize(e), speedBps = 0, etaSec = -1,
            lastStopReason = STOP_NETWORK, updatedAt = System.currentTimeMillis()))
        NoxLog.event("job-wait-network", "id" to id, "part" to partSize(e))
        return true
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
                    e = (if (e.isPlanned) resolvePlanned(e) else resolveLegacy(e)) ?: return
                    if (e.status == DownloadStatus.QUEUED) continue@loop   // повтор после паузы
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
                // Точный размер дорожки от источника: другой размер = другой файл.
                val expected = when (track) {
                    Track.AUDIO -> if (e.audioExact) e.audioTotalBytes else -1L
                    else -> if (e.videoExact) e.videoTotalBytes else -1L
                }
                val chunk = if (track == Track.AUDIO) e.audioChunk else e.videoChunk
                if (e.isPlanned) {
                    val stage = when (track) { Track.AUDIO -> "audio"; else -> "video" }
                    if (e.stage != stage) downloadsDao.update(e.copy(stage = stage, updatedAt = System.currentTimeMillis()))
                }
                val meter = SpeedMeter(part.length() + baseOther)
                var lastWrite = 0L
                NoxLog.event("transfer-start", "id" to id, "track" to track.name, "part" to part.length(),
                    "host" to SafeUrl.host(url), "headers" to headers.keys.joinToString(","))
                val outcome = http.download(url, headers, part, expected, chunk, policy.limiter) { downloaded, total ->
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
                        // Кэш разбора мог отдать уже отвергнутый адрес: следующий план — со свежего разбора.
                        resolver.forget(fresh.pageUrl)
                        if (tries > RESOLVE_RETRIES) {
                            val yt = fresh.extractorKey.startsWith("Youtube", true)
                            if (yt && outcome.code == 403) {
                                fail(id, "YouTube отказал в выдаче файла (HTTP 403) даже по свежей ссылке. Так бывает, " +
                                    "когда YouTube требует дополнительную проверку (PO-токен) или ограничивает сеть. " +
                                    "Скачанные части сохранены — попробуйте позже.", "forbidden")
                            } else {
                                fail(id, "Источник отказал в доступе к файлу (HTTP ${outcome.code}). Скачанные части сохранены.", "forbidden")
                            }
                            return
                        }
                        delay(1500L * tries)
                        // .part не трогаем: свежий адрес продолжит с того же места.
                        downloadsDao.update(fresh.copy(resolvedUrl = "", audioUrl = "", resolveRetries = tries,
                            downloadedBytes = partSize(fresh), updatedAt = System.currentTimeMillis()))
                        continue@loop
                    }
                    is HttpDownloader.Outcome.Failed -> {
                        if (outcome.isSizeMismatch) {
                            NoxLog.event("transfer-mismatch", "id" to id, "track" to track.name,
                                "expected" to outcome.expected, "actual" to outcome.actual, "part" to part.length())
                            fail(id, "Источник теперь отдаёт другой файл (${Format.bytes(outcome.actual)} вместо " +
                                "${Format.bytes(outcome.expected)}). Скачанные части не дополняются чужими данными — " +
                                "выберите качество заново.", "size-mismatch")
                            return
                        }
                        if (requeueIfNetworkLost(id)) return
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
     * Задание 0.2.x (лестница качества). Если часть файла уже скачана, просим
     * тот же формат; если источник его больше не отдаёт — останавливаемся с
     * понятной причиной и сохраняем части, а не качаем другой формат поверх.
     * null — задание остановлено; статус QUEUED — повтор после паузы.
     */
    private suspend fun resolveLegacy(e: DownloadEntity): DownloadEntity? {
        val hasVideoData = partFileOf(e).exists() || videoPartOf(e).exists() || e.videoDone
        val hasAudioData = audioPartOf(e).exists() || e.audioDone
        val r = resolver.resolve(
            e.pageUrl, Quality.fromKey(e.quality), allowSplit = e.allowSplit,
            preferFormat = if (hasVideoData) e.formatId else "",
            preferAudio = if (hasAudioData && e.isSplit) e.audioFormatId else "",
        )
        if (!r.ok) {
            if (requeueIfNetworkLost(e.id)) return null
            val tries = e.resolveRetries + 1
            if (tries >= RESOLVE_RETRIES || r.kind == "no-direct-format") {
                fail(e.id, r.error.ifBlank { "не удалось разобрать ссылку" }, r.kind)
                return null
            }
            downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, resolveRetries = tries,
                error = r.error, updatedAt = System.currentTimeMillis()))
            delay(3000L * tries)
            return e.copy(status = DownloadStatus.QUEUED)
        }
        val newMode = if (r.isSplit) DownloadMode.SPLIT else DownloadMode.PROGRESSIVE
        val videoChanged = hasVideoData && e.formatId.isNotBlank() && (e.formatId != r.formatId || e.mode != newMode)
        val audioChanged = hasAudioData && e.isSplit && e.audioFormatId.isNotBlank() &&
            (newMode != DownloadMode.SPLIT || e.audioFormatId != r.audioFormatId)
        if (videoChanged || audioChanged) {
            NoxLog.event("resolve-format-changed", "id" to e.id, "video" to videoChanged, "audio" to audioChanged)
            fail(e.id, "Формат, с которого началась загрузка, больше не предлагается источником. " +
                "Скачанные части сохранены — выберите качество заново.", "format-gone")
            return null
        }
        return applyResolve(e, r)
    }

    /** Применить свежий разбор задания 0.2.x (формат уже проверен на совпадение). */
    private fun applyResolve(e: DownloadEntity, r: YtDlpResolver.Result): DownloadEntity {
        val newMode = if (r.isSplit) DownloadMode.SPLIT else DownloadMode.PROGRESSIVE
        val keep = e.formatId.isNotBlank() && e.mode == newMode
        val videoDone = keep && e.videoDone
        val audioDone = keep && newMode == DownloadMode.SPLIT && e.audioDone
        val fileName = if (e.fileName.isNotBlank()) e.fileName
        else FileNames.unique(storage.media, FileNames.targetName(e.customTitle.ifBlank { r.title }, r.videoId, r.ext)).name
        val total = when {
            newMode == DownloadMode.SPLIT && r.filesize > 0 && r.audioFilesize > 0 -> r.filesize + r.audioFilesize
            newMode == DownloadMode.PROGRESSIVE && e.totalBytes > 0 && keep -> e.totalBytes
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
            extractorKey = e.extractorKey.ifBlank { r.extractor },
            mode = newMode,
            audioUrl = r.audioUrl, audioFormatId = r.audioFormatId,
            audioHeadersJson = JSONObject(r.audioHeaders).toString(),
            videoTotalBytes = if (videoDone) e.videoTotalBytes else r.filesize,
            audioTotalBytes = if (audioDone) e.audioTotalBytes else r.audioFilesize,
            videoDone = videoDone, audioDone = audioDone,
            status = DownloadStatus.DOWNLOADING, error = "", errorKind = "",
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Задание 0.3.0: свежие адреса ровно для сохранённых format ID.
     * Подмены формата нет: пропал вариант или изменился его точный размер
     * при уже скачанных частях — остановка с понятной причиной.
     */
    private suspend fun resolvePlanned(e: DownloadEntity): DownloadEntity? {
        if (e.stage != "plan") downloadsDao.update(e.copy(stage = "plan", updatedAt = System.currentTimeMillis()))
        val r = resolver.plan(e.pageUrl, e.formatId, if (e.isSplit) e.audioFormatId else "")
        val saved = if (partSize(e) > 0 || e.videoDone || e.audioDone) " Скачанные части сохранены." else ""
        when (r) {
            is PlanResult.FormatGone -> {
                fail(e.id, "Выбранный вариант (${e.quality}) больше не предлагается источником.$saved Выберите качество заново.",
                    "format-gone")
                return null
            }
            is PlanResult.Failed -> {
                val err = r.error
                if (requeueIfNetworkLost(e.id)) return null
                val tries = e.resolveRetries + 1
                if (err.kind in FINAL_KINDS || !err.retryable || tries >= RESOLVE_RETRIES) {
                    fail(e.id, err.message + saved, err.kind)
                    return null
                }
                downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, resolveRetries = tries,
                    error = err.message, updatedAt = System.currentTimeMillis()))
                delay(3000L * tries)
                return e.copy(status = DownloadStatus.QUEUED)
            }
            is PlanResult.Ok -> {
                val v = r.video
                val a = r.audio
                if (e.isSplit && a == null) {
                    fail(e.id, "План загрузки пришёл без звуковой дорожки.", "internal"); return null
                }
                val vData = partFileOf(e).exists() || videoPartOf(e).exists() || e.videoDone
                val aData = audioPartOf(e).exists() || e.audioDone
                val vChanged = e.videoExact && e.videoTotalBytes > 0 && v.filesizeExact && v.filesize != e.videoTotalBytes
                val aChanged = a != null && e.audioExact && e.audioTotalBytes > 0 && a.filesizeExact && a.filesize != e.audioTotalBytes
                if ((vChanged && vData) || (aChanged && aData)) {
                    NoxLog.event("plan-size-changed", "id" to e.id, "video" to vChanged, "audio" to aChanged)
                    fail(e.id, "Источник изменил файл выбранного варианта (${e.quality}).$saved Выберите качество заново.",
                        "format-changed")
                    return null
                }
                val vTotal = when {
                    e.videoDone -> e.videoTotalBytes
                    v.filesize > 0 -> v.filesize
                    v.filesizeApprox > 0 -> v.filesizeApprox
                    else -> e.videoTotalBytes
                }
                val aTotal = when {
                    a == null -> 0L
                    e.audioDone -> e.audioTotalBytes
                    a.filesize > 0 -> a.filesize
                    a.filesizeApprox > 0 -> a.filesizeApprox
                    else -> e.audioTotalBytes
                }
                return e.copy(
                    resolvedUrl = v.url, headersJson = JSONObject(v.headers).toString(),
                    audioUrl = a?.url.orEmpty(), audioHeadersJson = JSONObject(a?.headers ?: emptyMap<String, String>()).toString(),
                    videoTotalBytes = vTotal, audioTotalBytes = aTotal,
                    videoExact = if (e.videoDone) e.videoExact else v.filesizeExact && v.filesize > 0,
                    audioExact = if (e.audioDone) e.audioExact else a != null && a.filesizeExact && a.filesize > 0,
                    videoChunk = v.chunkSize, audioChunk = a?.chunkSize ?: 0L,
                    totalBytes = vTotal + aTotal,
                    title = r.details?.title?.takeIf { it.isNotBlank() } ?: e.title,
                    status = DownloadStatus.DOWNLOADING, error = "", errorKind = "",
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
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
                val reason = when {
                    updateStopping.remove(id) -> STOP_UPDATE
                    networkStopping.remove(id) -> STOP_NETWORK
                    else -> e.lastStopReason
                }
                if (e.status.isActive) {
                    downloadsDao.update(e.copy(status = DownloadStatus.QUEUED, downloadedBytes = size,
                        speedBps = 0, etaSec = -1, lastStopReason = reason, updatedAt = System.currentTimeMillis()))
                }
                NoxLog.event("job-requeued", "id" to id, "part" to size, "reason" to reason.ifBlank { null })
            }
        }
        if (part == null) Unit
    }

    private suspend fun fail(id: Long, message: String, kind: String = "") {
        val e = downloadsDao.get(id) ?: return
        downloadsDao.update(e.copy(status = DownloadStatus.ERROR, error = message.take(400), errorKind = kind,
            downloadedBytes = partSize(e), speedBps = 0, etaSec = -1, updatedAt = System.currentTimeMillis()))
        NoxLog.event("job-error", "id" to id, "kind" to kind.ifBlank { null }, "stage" to e.stage.ifBlank { null },
            "error" to message.take(160))
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
        val container = e.container.ifBlank { MediaMerger.CONTAINER_MP4 }
        NoxLog.event("merge-start", "id" to id, "video" to v.length(), "audio" to a.length(), "container" to container)
        if (e.stage != "merge") downloadsDao.update(e.copy(stage = "merge", updatedAt = System.currentTimeMillis()))
        try {
            MediaMerger.merge(v, a, tmp, container)
            val problem = MediaMerger.verify(tmp, v, a)
            val probe = MediaMerger.probe(tmp)
            if (problem != null) {
                tmp.delete()
                NoxLog.event("merge-verify-failed", "id" to id, "problem" to problem)
                fail(id, "Проверка собранного файла не пройдена: $problem. Дорожки сохранены — можно повторить объединение.", "verify")
                return
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
            withContext(NonCancellable) {
                fail(id, "Объединение дорожек не удалось: ${t.message ?: t.javaClass.simpleName}. Дорожки сохранены — можно повторить.", "merge")
            }
        }
    }

    // ------------------------------------------------------------------
    //  Медиатека
    // ------------------------------------------------------------------

    private suspend fun finishIntoLibrary(e: DownloadEntity, final: File) {
        val cover = fetchCover(e, final)
        val mediaId = db.media().insert(
            MediaEntity(title = e.displayTitle.ifBlank { final.nameWithoutExtension }, filePath = final.absolutePath,
                sizeBytes = final.length(), quality = e.qualityLabel, height = e.height, durationSec = e.durationSec,
                coverPath = cover, pageUrl = e.pageUrl, videoId = e.videoId, createdAt = System.currentTimeMillis(),
                uploader = e.uploader,
                container = e.container.ifBlank { final.extension.lowercase() }, width = e.width, fps = e.fps,
                codecs = codecsOf(e), variantKey = e.variantKey,
                kind = if (e.audioOnly) com.nox.offline.data.db.MediaKind.AUDIO else com.nox.offline.data.db.MediaKind.VIDEO)
        )
        downloadsDao.update(e.copy(status = DownloadStatus.COMPLETED, fileName = final.name,
            downloadedBytes = final.length(), totalBytes = final.length(), error = "",
            updatedAt = System.currentTimeMillis()))
        NoxLog.event("library-added", "media" to mediaId, "download" to e.id, "cover" to cover.isNotEmpty(), "audio" to e.audioOnly)
        runCatching { libraryHook?.onMediaAdded(mediaId, e) }
            .onFailure { NoxLog.event("library-hook-error", "error" to it.javaClass.simpleName) }
        // Субтитры — отдельный шаг: их сбой не трогает уже готовое видео.
        if (com.nox.offline.downloader.catalog.SourceSubtitle.listFromJson(e.subtitleRequest).isNotEmpty()) {
            scope.launch { fetchSubtitles(e.id, mediaId) }
        }
        relocator?.let { r -> scope.launch { runCatching { r.afterDownload(mediaId) } } }
    }

    /**
     * Скачать выбранные субтитры для уже готового видео. Ошибка записывается
     * в задание (subtitleError) с кнопкой «Повторить субтитры»; видео при
     * этом не удаляется и не помечается ошибочным.
     */
    private suspend fun fetchSubtitles(downloadId: Long, mediaId: Long) {
        val store = subtitleStore ?: return
        val e = downloadsDao.get(downloadId) ?: return
        val wanted = com.nox.offline.downloader.catalog.SourceSubtitle.listFromJson(e.subtitleRequest)
        val have = db.subtitles().forMedia(mediaId)
        val failed = ArrayList<String>()
        for (s in wanted) {
            val origin = if (s.auto) "auto" else "source"
            if (have.any { it.language == s.lang && it.origin == origin && it.label == s.label }) continue
            when (val r = resolver.subtitle(e.pageUrl, s.key, s.auto)) {
                is YtDlpResolver.SubtitleResult.Ok -> runCatching { store.save(mediaId, r.text, s.label, s.lang, origin) }
                    .onFailure { failed.add("${s.label}: ${it.message ?: "файл не читается"}") }
                is YtDlpResolver.SubtitleResult.Failed -> failed.add("${s.label}: ${r.message}")
            }
        }
        val media = db.media().get(mediaId)
        if (media != null && media.subtitleId == 0L) {
            db.subtitles().forMedia(mediaId).firstOrNull { it.origin == "source" }?.let { db.media().setSubtitle(mediaId, it.id) }
        }
        val error = failed.joinToString("; ").take(400)
        downloadsDao.get(downloadId)?.let { downloadsDao.update(it.copy(subtitleError = error, updatedAt = System.currentTimeMillis())) }
        NoxLog.event("subtitles-done", "download" to downloadId, "media" to mediaId, "wanted" to wanted.size, "failed" to failed.size)
    }

    /** «Повторить субтитры» — только субтитры, видео не перекачивается. */
    suspend fun retrySubtitles(downloadId: Long): Boolean {
        val e = downloadsDao.get(downloadId) ?: return false
        if (e.status != DownloadStatus.COMPLETED) return false
        val media = db.media().byVideoId(e.videoId).firstOrNull { it.variantKey == e.variantKey }
            ?: db.media().byVideoId(e.videoId).firstOrNull() ?: return false
        fetchSubtitles(downloadId, media.id)
        return true
    }

    private fun codecsOf(e: DownloadEntity): String {
        if (e.vcodec.isBlank() && e.acodec.isBlank()) return ""
        return listOfNotNull(e.vcodec.takeIf { it.isNotBlank() }?.let { CodecNames.video(it) },
            e.acodec.takeIf { it.isNotBlank() }?.let { CodecNames.audio(it) }).joinToString(" + ")
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
