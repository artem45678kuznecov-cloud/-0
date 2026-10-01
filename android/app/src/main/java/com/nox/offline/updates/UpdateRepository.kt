@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.updates

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.nox.offline.BuildConfig
import com.nox.offline.R
import com.nox.offline.core.NoxLog
import com.nox.offline.downloader.DownloadCoordinator
import com.nox.offline.downloader.DownloadNotifications
import com.nox.offline.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Что сейчас мешает или происходит при скачивании APK. */
enum class DownloadPhase { CONNECTING, DOWNLOADING, STALLED, RETRY_WAIT, WAITING_NETWORK, PAUSED }

/** Состояние обновления для интерфейса. */
sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(
        val manifest: UpdateManifest,
        val done: Long,
        val total: Long,
        val bytesPerSecond: Long = 0,
        val etaSeconds: Long = -1,
        val phase: DownloadPhase = DownloadPhase.CONNECTING,
        val retryInSeconds: Int = 0,
        val note: String = "",
    ) : UpdateState
    data class Verifying(val manifest: UpdateManifest) : UpdateState
    /** Скачано и проверено, пока NOX был свёрнут: установка — по нажатию. */
    data class ReadyToInstall(val manifest: UpdateManifest) : UpdateState
    data class NeedsPermission(val manifest: UpdateManifest, val apk: File) : UpdateState
    data class Installing(val manifest: UpdateManifest) : UpdateState
    data class Failed(val message: String, val manifest: UpdateManifest?, val retryable: Boolean) : UpdateState
    data class JustUpdated(val versionName: String) : UpdateState
}

/**
 * Подсистема обновлений целиком: проверка, скачивание, проверка файла,
 * установка. С видео и yt-dlp не пересекается ничем, кроме одного вызова:
 * непосредственно перед установкой — контрольная точка координатора.
 *
 * Скачивание APK — задание на диске ([UpdateJobStore]) с собственным
 * носителем ([UpdateScheduler]): оно не зависит от открытого экрана, идёт
 * в свёрнутом NOX, переживает перезапуск процесса и продолжается с
 * сохранённой части. Передача и её замеры — [UpdateTransfer].
 */
class UpdateRepository(
    private val context: Context,
    private val settings: AppSettings,
    private val coordinator: DownloadCoordinator,
    private val client: OkHttpClient,
) {
    companion object {
        const val REPO = "artem45678kuznecov-cloud/-0"
        const val MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/nox-update.json"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        private const val HOLD_TIMEOUT_MS = 10L * 60 * 1000
        private const val READY_NOTIFICATION = 44
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val verifier = ApkVerifier(context)
    val installer = UpdateInstaller(context)
    private var holdJob: Job? = null
    private var sessionId = -1

    /** Файлы обновления — в filesDir: кэш система вправе очистить посреди скачивания. */
    private val dir: File get() = File(context.filesDir, "updates").apply { mkdirs() }
    private val jobs = UpdateJobStore(File(context.filesDir, "updates"))
    private val runLock = Mutex()
    @Volatile private var running = false
    /** Передача без носителя (если система не дала его поднять) — только пока жив процесс. */
    private var inProcess: Job? = null
    /** После перезапуска процесса задание продолжится, когда NOX окажется на экране. */
    @Volatile private var resumeWhenVisible = false
    @Volatile private var lastTransfer: UpdateTransfer? = null
    @Volatile private var userCancelled = false

    /** При запуске: не новая ли это версия после нашей же установки; что с незаконченным скачиванием. */
    fun onAppStart() {
        val prefs = settings.updates.value
        val current = BuildConfig.VERSION_CODE
        if (prefs.lastRunVersionCode in 1 until current) {
            _state.value = UpdateState.JustUpdated(BuildConfig.VERSION_NAME)
            NoxLog.event("update-installed", "from" to prefs.lastRunVersionCode, "to" to current)
        }
        if (prefs.lastRunVersionCode != current || prefs.pendingInstallVersionCode != 0) {
            settings.updateUpdates { it.copy(lastRunVersionCode = current, pendingInstallVersionCode = 0) }
        }
        // Файлы прошлых версий больше не нужны; 0.4.2 и раньше хранили их в кэше.
        File(context.cacheDir, "updates").deleteRecursively()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("NOX-") && versionOf(f) <= current) f.delete()
        }
        val job = jobs.load()
        if (job == null || job.manifest.versionCode <= current) { jobs.clear(); return }
        val m = job.manifest
        if (_state.value is UpdateState.JustUpdated) return
        if (jobs.apk(m).exists()) {
            _state.value = UpdateState.ReadyToInstall(m)
        } else {
            val part = jobs.part(m)
            NoxLog.event("update-job-restored", "version" to m.versionCode, "part" to part.length(), "byUser" to job.stoppedByUser)
            if (job.stoppedByUser) {
                _state.value = UpdateState.Available(m)
            } else {
                _state.value = UpdateState.Downloading(m, part.length(), m.apkSize, phase = DownloadPhase.PAUSED,
                    note = "скачивание прервалось — продолжится с ${com.nox.offline.core.Format.bytes(part.length())}")
                resumeWhenVisible = true
            }
        }
    }

    private fun versionOf(f: File): Int = f.name.substringAfter("NOX-").substringBefore('.').substringBefore('-').toIntOrNull() ?: 0

    /** Автопроверка при открытии: не чаще раза в 12 часов, без сети — тихо. */
    fun autoCheck() {
        val p = settings.updates.value
        if (!p.autoCheck) return
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.UpToDate && _state.value !is UpdateState.JustUpdated) return
        if (!UpdatePolicy.autoCheckDue(p.lastCheckAt, System.currentTimeMillis())) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Verifying || s is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        scope.launch {
            val result = fetchManifest()
            settings.updateUpdates { it.copy(lastCheckAt = System.currentTimeMillis()) }
            result.onFailure { e ->
                NoxLog.event("update-check-error", "error" to e.message?.take(100))
                _state.value = if (manual) UpdateState.Failed(e.message ?: "не удалось проверить", null, true)
                else UpdateState.Idle
                return@launch
            }
            val m = result.getOrThrow()
            val decision = UpdatePolicy.decide(m, context.packageName, BuildConfig.VERSION_CODE,
                settings.updates.value.skippedVersionCode, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(), manual)
            NoxLog.event("update-check", "remote" to m.versionCode, "local" to BuildConfig.VERSION_CODE,
                "decision" to decision.javaClass.simpleName)
            _state.value = when (decision) {
                UpdateDecision.UpToDate -> UpdateState.UpToDate(System.currentTimeMillis())
                is UpdateDecision.Skipped -> UpdateState.Idle
                is UpdateDecision.Available -> UpdateState.Available(decision.manifest)
                is UpdateDecision.Incompatible -> if (manual) UpdateState.Failed(decision.reason, m, false) else UpdateState.Idle
            }
        }
    }

    private fun fetchManifest(): Result<UpdateManifest> = try {
        val req = Request.Builder().url(MANIFEST_URL).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> Result.failure(IOException("Опубликованного обновления пока нет"))
                resp.code == 403 || resp.code == 429 -> {
                    val retry = resp.header("Retry-After")
                    Result.failure(IOException("GitHub временно ограничил запросы" + (retry?.let { ", повторите через $it с" } ?: ", попробуйте позже")))
                }
                !resp.isSuccessful -> Result.failure(IOException("Сервер обновлений ответил ${resp.code}"))
                else -> {
                    val body = resp.body ?: return Result.failure(IOException("пустой ответ"))
                    if (body.contentLength() > 256 * 1024) return Result.failure(IOException("манифест слишком большой"))
                    Result.success(UpdateManifest.parse(body.string()))
                }
            }
        }
    } catch (e: IOException) {
        Result.failure(IOException("Нет соединения с GitHub"))
    } catch (e: Exception) {
        Result.failure(IOException("Манифест обновления повреждён: ${e.message}"))
    }

    fun later() {
        val s = _state.value
        if (s is UpdateState.Available || s is UpdateState.Failed || s is UpdateState.JustUpdated || s is UpdateState.ReadyToInstall) {
            _state.value = UpdateState.Idle
        }
    }

    fun skip(m: UpdateManifest) {
        settings.updateUpdates { it.copy(skippedVersionCode = m.versionCode) }
        _state.value = UpdateState.Idle
    }

    // ------------------------------------------------------------------
    //  Скачивание
    // ------------------------------------------------------------------

    /** «Обновить» (и «Повторить», «Установить»): одно задание на этот выпуск и его носитель. */
    fun download(m: UpdateManifest) {
        if (running) return
        val existing = jobs.load()
        val job = if (existing != null && existing.manifest.sameRelease(m)) {
            existing.copy(manifest = m, stoppedByUser = false, lastError = "")
        } else {
            existing?.let { old -> jobs.part(old.manifest).delete(); jobs.apk(old.manifest).delete() }
            UpdateJobStore.Job(m, System.currentTimeMillis())
        }
        jobs.save(job)
        resumeWhenVisible = false
        val part = jobs.part(m)
        _state.value = if (jobs.apk(m).exists()) UpdateState.Verifying(m)
        else UpdateState.Downloading(m, part.length(), m.apkSize, phase = DownloadPhase.CONNECTING)
        if (jobs.apk(m).exists() || !UpdateScheduler.start(context, m)) {
            // Готовый файл только проверить; или носитель не поднялся — передача в процессе NOX.
            inProcess = scope.launch { runJob(carrier = "process") }
        }
    }

    fun cancelDownload() {
        userCancelled = true
        val job = jobs.load()
        if (job != null) jobs.save(job.copy(stoppedByUser = true, events = job.events + "остановлено пользователем"))
        UpdateScheduler.stop(context)
        inProcess?.cancel()
        val m = job?.manifest ?: (_state.value as? UpdateState.Downloading)?.manifest
        _state.value = if (m != null) UpdateState.Available(m) else UpdateState.Idle
        NoxLog.event("update-download-cancelled", "part" to (m?.let { jobs.part(it).length() } ?: 0))
    }

    /** Носитель остановлен системой (или пользователем в диспетчере задач). Часть файла остаётся. */
    fun onCarrierStopped(reason: String, byUser: Boolean) {
        val job = jobs.load() ?: return
        jobs.save(job.copy(stoppedByUser = job.stoppedByUser || (byUser && reason == "user"),
            events = job.events + "носитель остановлен: $reason"))
        val s = _state.value
        if (s is UpdateState.Downloading && !(byUser && reason == "cancelled-by-app")) {
            _state.value = s.copy(phase = if (reason == "connectivity") DownloadPhase.WAITING_NETWORK else DownloadPhase.PAUSED,
                bytesPerSecond = 0, etaSeconds = -1,
                note = if (byUser) "остановлено в диспетчере задач" else "система приостановила передачу ($reason) — продолжится сама")
        }
    }

    /**
     * Работа носителя: продолжить передачу с сохранённой части, проверить
     * SHA-256 и подпись, дальше — установка (если NOX на экране) или
     * уведомление «готово к установке». Два носителя в один файл не пишут.
     */
    suspend fun runJob(carrier: String, onUpdate: (UpdateState) -> Unit = {}): UpdateTransfer.Result? = runLock.withLock {
        val job = jobs.load() ?: return@withLock null
        val m = job.manifest
        if (m.versionCode <= BuildConfig.VERSION_CODE || job.stoppedByUser) return@withLock null
        running = true
        userCancelled = false
        // После «Отменить» поздние события передачи не перекрывают «Доступна версия».
        fun publish(s: UpdateState) { if (!userCancelled) { _state.value = s; onUpdate(s) } }
        try {
            val apk = jobs.apk(m)
            val part = jobs.part(m)
            NoxLog.event("update-job-start", "carrier" to carrier, "version" to m.versionCode, "part" to part.length(), "apk" to apk.exists())
            var saved = job
            if (!apk.exists()) {
                val base = job
                val transfer = UpdateTransfer(client,
                    awaitNetwork = {
                        if (carrier == "uidt") true
                        else UpdateScheduler.awaitNetwork(context) {
                            publish(UpdateState.Downloading(m, part.length(), m.apkSize, phase = DownloadPhase.WAITING_NETWORK))
                        }
                    },
                    log = { NoxLog.event("update-http", "msg" to it) })
                lastTransfer = transfer
                var lastSave = 0L
                val result = transfer.run(m.apkUrl, part, m.apkSize, refresh = { refresh(m) }) { p ->
                    publish(UpdateState.Downloading(m, p.received, p.total, p.bytesPerSecond, p.etaSeconds, p.phase.toUi(),
                        p.retryInSeconds, p.note))
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 5_000) { lastSave = now; saved = saveStats(base, transfer, null) }
                }
                saved = saveStats(base, transfer, result)
                when (result) {
                    UpdateTransfer.Result.Done -> if (!part.renameTo(apk)) {
                        publish(UpdateState.Failed("не удалось сохранить скачанный файл", m, true)); return@withLock result
                    }
                    UpdateTransfer.Result.Cancelled -> return@withLock result
                    UpdateTransfer.Result.Obsolete -> {
                        part.delete(); jobs.clear()
                        NoxLog.event("update-job-obsolete", "version" to m.versionCode)
                        _state.value = UpdateState.Idle
                        check(manual = true)
                        return@withLock result
                    }
                    UpdateTransfer.Result.NoNetwork -> {
                        publish(UpdateState.Downloading(m, part.length(), m.apkSize, phase = DownloadPhase.WAITING_NETWORK))
                        return@withLock result
                    }
                    is UpdateTransfer.Result.Failed -> {
                        publish(UpdateState.Failed("Скачивание не удалось: ${result.message}", m, result.retryable))
                        return@withLock result
                    }
                }
            }
            publish(UpdateState.Verifying(m))
            val t0 = System.nanoTime()
            val v = verifier.verify(apk, m, BuildConfig.VERSION_CODE)
            val verifyMs = (System.nanoTime() - t0) / 1_000_000
            NoxLog.event("update-verify", "ok" to v.ok, "ms" to verifyMs, "reason" to v.reason.ifBlank { null })
            jobs.save(saved.copy(verifyMs = verifyMs))
            if (!v.ok) {
                apk.delete(); part.delete(); jobs.clear()
                publish(UpdateState.Failed("Проверка APK не пройдена: ${v.reason}", m, false))
                return@withLock UpdateTransfer.Result.Failed(v.reason, false)
            }
            if (visible()) {
                proceedToInstall(m, apk)
            } else {
                publish(UpdateState.ReadyToInstall(m))
                notifyReady(m)
            }
            UpdateTransfer.Result.Done
        } finally {
            running = false
        }
    }

    private fun UpdateTransfer.Phase.toUi() = when (this) {
        UpdateTransfer.Phase.CONNECTING -> DownloadPhase.CONNECTING
        UpdateTransfer.Phase.DOWNLOADING -> DownloadPhase.DOWNLOADING
        UpdateTransfer.Phase.STALLED -> DownloadPhase.STALLED
        UpdateTransfer.Phase.RETRY_WAIT -> DownloadPhase.RETRY_WAIT
        UpdateTransfer.Phase.WAITING_NETWORK -> DownloadPhase.WAITING_NETWORK
    }

    /** Задание = счётчики прошлых носителей ([base]) + этой передачи; пишется по ходу и в конце. */
    private fun saveStats(base: UpdateJobStore.Job, t: UpdateTransfer, result: UpdateTransfer.Result?): UpdateJobStore.Job {
        val st = t.currentStats
        val ev = if (result == null) base.events else base.events + "итог попыток: ${result.javaClass.simpleName}" +
            ((result as? UpdateTransfer.Result.Failed)?.let { " — ${it.message}" } ?: "")
        val updated = base.copy(attempts = base.attempts + st.attempts, networkBytes = base.networkBytes + st.networkBytes,
            transferMs = base.transferMs + st.transferMs, pauseMs = base.pauseMs + st.pauseMs,
            lastError = (result as? UpdateTransfer.Result.Failed)?.message ?: base.lastError, events = ev)
        jobs.save(updated)
        return updated
    }

    /** Ссылка перестала работать: тот ли ещё выпуск опубликован. */
    private fun refresh(m: UpdateManifest): UpdateTransfer.Refresh {
        val fresh = fetchManifest().getOrElse { return UpdateTransfer.Refresh.Unavailable(it.message ?: "нет ответа") }
        return when {
            fresh.sameRelease(m) -> UpdateTransfer.Refresh.Same(fresh.apkUrl)
            else -> UpdateTransfer.Refresh.Changed
        }
    }

    private fun visible(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)

    private fun notifyReady(m: UpdateManifest) {
        val open = android.app.PendingIntent.getActivity(context, 10,
            android.content.Intent(context, com.nox.offline.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(com.nox.offline.MainActivity.EXTRA_TAB, com.nox.offline.MainActivity.TAB_SETTINGS),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NOX ${m.versionName} скачан и проверен")
            .setContentText("Нажмите, чтобы установить. Медиатека и настройки сохранятся.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        postUpdateNotification(context, READY_NOTIFICATION, n)
    }

    /** Замеры последнего скачивания — для «Скопировать диагностику». */
    fun diagnostics(): String = buildString {
        val job = jobs.load() ?: return@buildString
        val m = job.manifest
        appendLine("Скачивание обновления ${m.versionName} (${m.versionCode}): размер ${m.apkSize} байт")
        appendLine("  на диске: часть ${jobs.part(m).length()} байт, готовый APK ${if (jobs.apk(m).exists()) "есть" else "нет"}")
        appendLine("  попыток ${job.attempts}, из сети ${job.networkBytes} байт, передача ${job.transferMs / 1000} с, паузы ${job.pauseMs / 1000} с" +
            (if (job.verifyMs >= 0) ", проверка ${job.verifyMs} мс" else ""))
        if (job.lastError.isNotBlank()) appendLine("  последняя ошибка: ${job.lastError}")
        job.events.takeLast(8).forEach { appendLine("  событие: $it") }
        lastTransfer?.currentStats?.requests?.toList()?.takeLast(10)?.forEach { appendLine("  запрос: $it") }
    }

    // ------------------------------------------------------------------
    //  Установка
    // ------------------------------------------------------------------

    private suspend fun proceedToInstall(m: UpdateManifest, apk: File) {
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(m, apk)
            return
        }
        // Контрольная точка — только здесь, непосредственно перед установкой.
        _state.value = UpdateState.Installing(m)
        coordinator.checkpointForUpdate()
        // Позиция просмотра — на диск до установки; после обновления звук сам не включится.
        runCatching { com.nox.offline.NoxApp.get(context).playback.checkpointForUpdate() }
        settings.updateUpdates { it.copy(pendingInstallVersionCode = m.versionCode) }
        try {
            sessionId = installer.install(apk, m.versionCode)
            // Если за 10 минут ничего не произошло — считаем установку отложенной.
            holdJob?.cancel()
            holdJob = scope.launch {
                delay(HOLD_TIMEOUT_MS)
                if (_state.value is UpdateState.Installing) onInstallFinished(false, null)
            }
        } catch (t: Throwable) {
            NoxLog.event("update-install-error", "error" to "${t.javaClass.simpleName}: ${t.message?.take(80)}")
            onInstallFinished(false, t.message ?: "установщик недоступен")
        }
    }

    /** Пользователь вернулся в NOX (или из системных настроек). */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && installer.canInstall()) {
            scope.launch { proceedToInstall(s.manifest, s.apk) }
        }
        if (s is UpdateState.Installing && sessionId > 0 && !installer.sessionAlive(sessionId)) {
            // Окно подтверждения закрыли без ответа — сессии больше нет.
            onInstallFinished(false, null)
        }
        // Скачивание прервалось вместе с процессом: NOX снова на экране — продолжаем (носитель можно поднять только сейчас).
        if (resumeWhenVisible && !running && !UpdateScheduler.isScheduled(context)) {
            val job = jobs.load()
            resumeWhenVisible = false
            if (job != null && !job.stoppedByUser) {
                NoxLog.event("update-job-resume", "part" to jobs.part(job.manifest).length())
                download(job.manifest)
            }
        }
    }

    fun retryInstall() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission) scope.launch { proceedToInstall(s.manifest, s.apk) }
    }

    /**
     * Итог установки. Успех здесь почти не наблюдается: при замене пакета
     * процесс завершается раньше. Настоящее подтверждение — запуск новой
     * версии ([onAppStart]) и PackageReplacedReceiver.
     */
    fun onInstallFinished(success: Boolean, error: String?) {
        holdJob?.cancel()
        sessionId = -1
        if (success) return
        settings.updateUpdates { it.copy(pendingInstallVersionCode = 0) }
        coordinator.releaseUpdateHold()
        val current = _state.value
        val m = when (current) {
            is UpdateState.Installing -> current.manifest
            is UpdateState.NeedsPermission -> current.manifest
            else -> null
        }
        // Отказ от установки: проверенный файл остаётся, второй раз он не скачивается.
        _state.value = if (error == null) {
            if (m != null) UpdateState.Available(m) else UpdateState.Idle
        } else UpdateState.Failed("Установка не выполнена: $error", m, true)
        NoxLog.event("update-install-finished", "success" to false, "error" to error?.take(80))
    }
}
