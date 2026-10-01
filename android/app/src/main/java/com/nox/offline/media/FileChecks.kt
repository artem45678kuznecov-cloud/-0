package com.nox.offline.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.DownloadMode
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.storage.MediaLocator
import java.io.File

/**
 * «Проверить файл» для записи медиатеки: структура контейнера + разбор тем же
 * экстрактором, что у плеера. Только чтение; файл не пересылается никуда.
 * Последний итог по каждому видео хранится в маленьком текстовом файле —
 * он попадает в отчёт об ошибке воспроизведения и в «Скопировать диагностику».
 */
@UnstableApi
object FileChecks {
    private fun dir(context: Context) = File(context.filesDir, "file-checks").apply { mkdirs() }

    /** Открыть файл видео только на чтение: и внутри NOX, и документ SAF. */
    fun open(context: Context, m: MediaEntity): ByteSource =
        if (!m.isExternal) ChannelByteSource.of(File(m.filePath))
        else NoxExtractorsFactory.openReadOnly(context, Uri.parse(m.contentUri))

    fun isMatroska(context: Context, m: MediaEntity): Boolean =
        runCatching { open(context, m).use { ContainerCheck.kindOf(it) == ContainerCheck.Kind.WEBM } }.getOrDefault(false)

    /**
     * Точный размер от источника — только если он действительно был точным:
     * цельный файл, чей полный размер назвал источник или сервер. Оценки не берутся.
     */
    suspend fun exactSize(app: NoxApp, m: MediaEntity): Long {
        val name = MediaLocator.fileName(m)
        val d = app.db.downloads().byStatus(DownloadStatus.COMPLETED)
            .firstOrNull { it.fileName == name && it.videoId == m.videoId && it.mode == DownloadMode.PROGRESSIVE }
        return if (d != null && d.videoExact && d.videoTotalBytes > 0) d.videoTotalBytes else -1
    }

    suspend fun check(app: NoxApp, m: MediaEntity, ctl: CheckControl): FileCheck {
        val exact = exactSize(app, m)
        val structural = open(app, m).use { src ->
            val c = ContainerCheck.check(src, exact, ctl)
            val kind = ContainerCheck.kindOf(src)
            c to kind
        }
        var result = structural.first
        val savedMs = app.db.playback().get(m.id)?.positionMs ?: 0
        val probe = Media3Probe.run(app, MediaLocator.playUri(m), structural.second, savedMs, ctl)
        result = result.copy(details = result.details + probe.lines)
        if (!probe.ok && result.verdict == FileCheck.Verdict.READABLE) {
            result = result.copy(summary = result.summary + " Но разборщик плеера на нём останавливается: ${probe.failure}",
                problemTimeMs = probe.failureAtMs)
        }
        if (probe.ok && result.verdict == FileCheck.Verdict.READABLE) {
            result = result.copy(summary = result.summary + " Разборщик плеера читает его в начале, середине и конце.")
        }
        store(app, m.id, result)
        NoxLog.event("file-check", "media" to m.id, "verdict" to result.verdict.name, "container" to result.container,
            "repair" to result.repair.name, "probe" to probe.ok)
        return result
    }

    fun store(context: Context, mediaId: Long, c: FileCheck) {
        runCatching { File(dir(context), "$mediaId.txt").writeText(c.report().take(16_000)) }
    }

    /** Последний итог проверки файла одной строкой — или "". */
    fun lastSummary(context: Context, mediaId: Long): String =
        runCatching { File(dir(context), "$mediaId.txt").takeIf { it.exists() }?.readLines()?.take(3)?.joinToString(" / ") }
            .getOrNull().orEmpty()

    fun lastReport(context: Context, mediaId: Long): String =
        runCatching { File(dir(context), "$mediaId.txt").takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
}
