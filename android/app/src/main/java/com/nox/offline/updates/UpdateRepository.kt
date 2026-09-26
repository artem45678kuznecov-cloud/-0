@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.updates

import android.content.Context
import android.os.Build
import com.nox.offline.BuildConfig
import com.nox.offline.core.NoxLog
import com.nox.offline.downloader.DownloadCoordinator
import com.nox.offline.downloader.HttpDownloader
import com.nox.offline.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Состояние обновления для интерфейса. */
sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val done: Long, val total: Long) : UpdateState
    data class NeedsPermission(val manifest: UpdateManifest, val apk: File) : UpdateState
    data class Installing(val manifest: UpdateManifest) : UpdateState
    data class Failed(val message: String, val manifest: UpdateManifest?, val retryable: Boolean) : UpdateState
    data class JustUpdated(val versionName: String) : UpdateState
}

/**
 * Подсистема обновлений целиком: проверка, скачивание, проверка файла,
 * установка. С видео и yt-dlp не пересекается ничем, кроме одного вызова:
 * непосредственно перед установкой — контрольная точка координатора.
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val http = HttpDownloader(client) { NoxLog.event("update-http", "msg" to it) }
    private val verifier = ApkVerifier(context)
    val installer = UpdateInstaller(context)
    private var downloadJob: Job? = null
    private var holdJob: Job? = null
    private var sessionId = -1

    private val dir: File get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** При запуске: не новая ли это версия после нашей же установки. */
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
        // Старые скачанные APK больше не нужны.
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("NOX-") && versionOf(f) <= current) f.delete()
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
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
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
        if (_state.value is UpdateState.Available || _state.value is UpdateState.Failed || _state.value is UpdateState.JustUpdated) {
            _state.value = UpdateState.Idle
        }
    }

    fun skip(m: UpdateManifest) {
        settings.updateUpdates { it.copy(skippedVersionCode = m.versionCode) }
        _state.value = UpdateState.Idle
    }

    fun download(m: UpdateManifest) {
        if (downloadJob?.isActive == true) return
        val part = File(dir, "NOX-${m.versionCode}.apk.part")
        val apk = File(dir, "NOX-${m.versionCode}.apk")
        _state.value = UpdateState.Downloading(m, part.length(), m.apkSize)
        downloadJob = scope.launch {
            if (!apk.exists()) {
                val out = http.download(m.apkUrl, mapOf("Accept" to "application/vnd.android.package-archive"), part) { d, t ->
                    _state.value = UpdateState.Downloading(m, d, if (t > 0) t else m.apkSize)
                }
                when (out) {
                    is HttpDownloader.Outcome.Completed -> if (!part.renameTo(apk)) {
                        _state.value = UpdateState.Failed("не удалось сохранить файл", m, true); return@launch
                    }
                    HttpDownloader.Outcome.Cancelled -> { _state.value = UpdateState.Available(m); return@launch }
                    is HttpDownloader.Outcome.Expired -> { part.delete(); _state.value = UpdateState.Failed("файл релиза недоступен (HTTP ${out.code})", m, true); return@launch }
                    is HttpDownloader.Outcome.Failed -> { _state.value = UpdateState.Failed("загрузка прервалась: ${out.message}", m, true); return@launch }
                }
            }
            val v = verifier.verify(apk, m, BuildConfig.VERSION_CODE)
            NoxLog.event("update-verify", "ok" to v.ok, "reason" to v.reason.ifBlank { null })
            if (!v.ok) {
                apk.delete(); part.delete()
                _state.value = UpdateState.Failed("Проверка APK не пройдена: ${v.reason}", m, false)
                return@launch
            }
            proceedToInstall(m, apk)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

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

    /** Пользователь вернулся из системных настроек. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && installer.canInstall()) {
            scope.launch { proceedToInstall(s.manifest, s.apk) }
        }
        if (s is UpdateState.Installing && sessionId > 0 && !installer.sessionAlive(sessionId)) {
            // Окно подтверждения закрыли без ответа — сессии больше нет.
            onInstallFinished(false, null)
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
        _state.value = if (error == null) {
            if (m != null) UpdateState.Available(m) else UpdateState.Idle
        } else UpdateState.Failed("Установка не выполнена: $error", m, true)
        NoxLog.event("update-install-finished", "success" to false, "error" to error?.take(80))
    }
}
