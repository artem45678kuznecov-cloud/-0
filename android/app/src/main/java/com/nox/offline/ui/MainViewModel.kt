package com.nox.offline.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nox.offline.BuildConfig
import com.nox.offline.NoxApp
import com.nox.offline.core.Format
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
import com.nox.offline.core.Storage
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.downloader.Quality
import com.nox.offline.downloader.TransferScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Состояние экранов. Всё, что показывается, читается из Room потоками. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val nox = NoxApp.get(app)
    private val coordinator = nox.coordinator

    val downloads: StateFlow<List<DownloadEntity>> = nox.db.downloads().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val media: StateFlow<List<MediaEntity>> = nox.db.media().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playback: StateFlow<List<PlaybackEntity>> = nox.db.playback().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val urlInput = MutableStateFlow("")
    val quality = MutableStateFlow(Quality.DEFAULT)
    val message = MutableStateFlow("")
    val space = MutableStateFlow<Storage.Space?>(null)
    val notificationsAllowed = MutableStateFlow(true)

    init {
        refreshSpace()
    }

    fun refreshSpace() {
        viewModelScope.launch(Dispatchers.IO) {
            space.value = nox.storage.space()
        }
    }

    fun setUrl(text: String) { urlInput.value = text }
    fun setQuality(q: Quality) { quality.value = q }

    /** «Скачать»: задание в очередь и запуск носителя. Только из видимого окна. */
    fun startDownload() {
        val url = urlInput.value.trim()
        if (!SafeUrl.looksLikeUrl(url)) {
            message.value = "Вставьте ссылку на видео"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val r = coordinator.enqueue(url, quality.value)
            r.onFailure { message.value = it.message ?: "Не удалось добавить" }
            r.onSuccess {
                urlInput.value = ""
                message.value = when (val s = TransferScheduler.ensureRunning(getApplication())) {
                    is TransferScheduler.Result.Failed -> "Добавлено, но фоновая загрузка не запустилась: ${s.reason}"
                    else -> "Добавлено в очередь"
                }
            }
        }
    }

    /** При возвращении в приложение: если есть очередь, а носителя нет — поднять. */
    fun ensureRunnerIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            if (coordinator.hasWork() && !coordinator.runnerAttached) {
                TransferScheduler.ensureRunning(getApplication())
            }
        }
    }

    fun pause(id: Long) = viewModelScope.launch(Dispatchers.IO) { coordinator.pause(id) }

    fun resume(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        coordinator.resume(id)
        TransferScheduler.ensureRunning(getApplication())
    }

    fun cancel(id: Long) = viewModelScope.launch(Dispatchers.IO) { coordinator.cancel(id) }

    fun remove(id: Long) = viewModelScope.launch(Dispatchers.IO) { coordinator.remove(id) }

    fun deleteMedia(m: MediaEntity) = viewModelScope.launch(Dispatchers.IO) {
        try { File(m.filePath).delete() } catch (_: Exception) { }
        if (m.coverPath.isNotBlank()) try { File(m.coverPath).delete() } catch (_: Exception) { }
        nox.db.playback().delete(m.id)
        nox.db.media().delete(m)
        NoxLog.event("media-deleted", "id" to m.id)
        refreshSpace()
    }

    fun clearMessage() { message.value = "" }

    /** Текст для кнопки «Скопировать диагностику». Без секретов. */
    suspend fun diagnostics(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("NOX Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Носитель: ${if (Build.VERSION.SDK_INT >= 34) "UIDT job" else "foreground service"}; подключён=${coordinator.runnerAttached}")
        sb.appendLine("Активных передач: ${coordinator.activeCount.value}")
        sb.appendLine("Последняя остановка носителя: ${coordinator.lastStopReason.ifBlank { "-" }}")
        sb.appendLine("yt-dlp: ${nox.resolver.version()}")
        sb.appendLine("Последняя ошибка резолвера: ${nox.resolver.lastError.ifBlank { "-" }}")
        val sp = nox.storage.space()
        sb.appendLine("Хранилище: ${nox.storage.root.absolutePath}")
        sb.appendLine("Свободно ${Format.bytes(sp.freeBytes)}, занято NOX ${Format.bytes(sp.usedByNox)}")
        sb.appendLine()
        sb.appendLine("Задания:")
        for (d in nox.db.downloads().getAll()) {
            sb.appendLine("  #${d.id} ${d.status} ${d.quality} ${Format.bytes(d.downloadedBytes)}/${Format.bytes(d.totalBytes)} " +
                "host=${SafeUrl.host(d.resolvedUrl)} retries=${d.retries}/${d.resolveRetries} " +
                "stop=${d.lastStopReason.ifBlank { "-" }} err=${d.error.take(80).ifBlank { "-" }}")
        }
        sb.appendLine()
        sb.appendLine("Журнал:")
        for (line in NoxLog.dump().takeLast(200)) sb.appendLine("  $line")
        sb.toString()
    }
}
