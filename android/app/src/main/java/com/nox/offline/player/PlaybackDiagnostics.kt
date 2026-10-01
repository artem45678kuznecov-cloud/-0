package com.nox.offline.player

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import com.nox.offline.core.MediaTypes
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.media.ChannelByteSource
import com.nox.offline.media.ContainerCheck
import com.nox.offline.storage.MediaLocator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Подробный отчёт об ошибке воспроизведения для «Скопировать диагностику».
 *
 * Ограничен по размеру; без адресов, cookies и токенов (адрес страницы — только
 * имя сайта). Собирается вне главного потока: он открывает файл на чтение,
 * чтобы узнать фактический размер и контейнер по первым байтам.
 */
@UnstableApi
object PlaybackDiagnostics {
    /** Снимок плеера в момент ошибки — берётся на главном потоке. */
    data class Moment(
        val stage: String,
        val requestedStartMs: Long,
        val positionMs: Long,
        val bufferedMs: Long,
        val playerDurationMs: Long,
        val playWhenReady: Boolean,
        val listen: Boolean,
        val segmentStartMs: Long,
        val segmentEndMs: Long,
        val attempt: String,
    )

    private const val MAX_CHARS = 12_000

    fun build(context: Context, error: PlaybackException, media: MediaEntity?, moment: Moment, lastCheck: String): String {
        val sb = StringBuilder()
        sb.appendLine("Ошибка воспроизведения, ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}")
        sb.appendLine("Код: ${error.errorCode} ${error.errorCodeName}")
        sb.appendLine("Вид: ${kind(error.errorCode)}")
        if (isOutOfMemory(error)) {
            sb.appendLine("Причина — нехватка памяти процесса (OutOfMemoryError) при разборе, а не повреждение файла.")
        }
        (error as? ExoPlaybackException)?.let { e ->
            sb.appendLine("Тип ExoPlayer: ${when (e.type) {
                ExoPlaybackException.TYPE_SOURCE -> "источник (чтение/разбор файла)"
                ExoPlaybackException.TYPE_RENDERER -> "рендерер (декодер ${e.rendererName ?: "-"}, формат ${e.rendererFormat?.sampleMimeType ?: "-"} " +
                    "${e.rendererFormat?.codecs ?: ""} ${e.rendererFormat?.width ?: 0}×${e.rendererFormat?.height ?: 0})"
                ExoPlaybackException.TYPE_UNEXPECTED -> "внутренняя ошибка плеера"
                ExoPlaybackException.TYPE_REMOTE -> "удалённый источник"
                else -> e.type.toString()
            }}")
            if (e.type == ExoPlaybackException.TYPE_RENDERER) {
                val track = e.rendererFormat?.sampleMimeType.orEmpty()
                sb.appendLine("Дорожка: ${when {
                    track.startsWith("video/") -> "видео"
                    track.startsWith("audio/") -> "звук"
                    track.isNotEmpty() -> "текст/прочее ($track)"
                    else -> "неизвестно"
                }}")
            }
        }
        sb.appendLine("Этап: ${moment.stage}; попытка: ${moment.attempt}")
        sb.appendLine("Причины:")
        generateSequence(error as Throwable) { it.cause }.take(8).forEach { t ->
            sb.appendLine("  ${t.javaClass.name}: ${scrub(t.message)}")
        }
        sb.appendLine("Стек (информативные строки):")
        generateSequence(error as Throwable) { it.cause }.take(8)
            .flatMap { it.stackTrace.asSequence() }
            .filter { it.className.startsWith("androidx.media3.extractor") || it.className.startsWith("androidx.media3.exoplayer.source") ||
                it.className.startsWith("androidx.media3.container") || it.className.startsWith("androidx.media3.datasource") ||
                it.className.startsWith("com.nox") }
            .distinct().take(16)
            .forEach { sb.appendLine("  at $it") }
        sb.appendLine("Позиция: запрошено ${moment.requestedStartMs} мс, плеер ${moment.positionMs} мс, буфер до ${moment.bufferedMs} мс, " +
            "длительность по плееру ${moment.playerDurationMs} мс; playWhenReady=${moment.playWhenReady}; «Только звук»=${moment.listen}")
        if (moment.segmentStartMs >= 0) sb.appendLine("Серия/глава: ${moment.segmentStartMs}…${moment.segmentEndMs} мс (ClippingConfiguration)")
        if (media != null) source(context, media, sb)
        sb.appendLine("Внешние субтитры NOX рисует сам, в разборе файла плеером они не участвуют.")
        if (lastCheck.isNotBlank()) sb.appendLine("Последняя проверка файла: $lastCheck")
        val rt = Runtime.getRuntime()
        sb.appendLine("Память сейчас: лимит Java-кучи ${rt.maxMemory() / 1_048_576} МиБ, занято ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} МиБ, " +
            "нативно ${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} МиБ")
        NoxLog.dump().lastOrNull { it.contains(" mp4-large") }?.let { sb.appendLine("Разбор MP4: $it") }
        sb.appendLine("Media3 ${MediaLibraryInfo.VERSION}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
            "${Build.MANUFACTURER} ${Build.MODEL}; ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        return if (sb.length > MAX_CHARS) sb.substring(0, MAX_CHARS) + "\n…(обрезано)" else sb.toString()
    }

    private fun source(context: Context, m: MediaEntity, sb: StringBuilder) {
        sb.appendLine("Видео №${m.id}: «${m.title.take(80)}»")
        sb.appendLine("По базе: контейнер=${m.container.ifBlank { "-" }} кодеки=${m.codecs.ifBlank { "-" }} качество=${m.quality.ifBlank { "-" }} " +
            "${m.width}×${m.height} ${m.fps} к/с, размер ${m.sizeBytes} байт, длительность ${m.durationSec} с, вариант=${m.variantKey.ifBlank { "-" }}, " +
            "источник=${SafeUrl.host(m.pageUrl).ifBlank { "-" }}")
        val name = MediaLocator.fileName(m)
        sb.appendLine("Файл: ${if (m.isExternal) "документ в папке пользователя (SAF, content://)" else "внутри NOX (file://)"}, имя «$name», " +
            "расширение «${name.substringAfterLast('.', "")}», MIME по имени ${MediaTypes.mimeForName(name)}")
        try {
            val src = if (m.isExternal) {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(m.contentUri), "r")
                    ?: throw java.io.IOException("провайдер не открыл документ")
                pfd.use { ChannelByteSource.of(it.fileDescriptor).use { s -> describe(s) } }
            } else {
                val f = File(m.filePath)
                if (!f.exists()) "файла нет по пути" else ChannelByteSource.of(f).use { s -> describe(s) }
            }
            sb.appendLine("Чтение: $src")
        } catch (t: Throwable) {
            sb.appendLine("Чтение: не удалось открыть на чтение — ${t.javaClass.simpleName}: ${scrub(t.message)}")
        }
    }

    private fun describe(s: ChannelByteSource): String {
        val head = ByteArray(16)
        val n = s.read(0, head)
        val tail = ByteArray(8)
        val t = if (s.size >= 8) s.read(s.size - 8, tail) else 0
        return "размер ${s.size} байт, контейнер по содержимому ${ContainerCheck.kindOf(s)}, начало ${hex(head, n)}, конец ${hex(tail, t)}, " +
            "произвольное чтение работает"
    }

    private fun hex(b: ByteArray, n: Int) = b.take(n).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** В цепочке причин есть OutOfMemoryError (Media3 заворачивает её в UnexpectedLoaderException). */
    fun isOutOfMemory(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any { it is OutOfMemoryError }

    private fun kind(code: Int): String = when (code) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "разбор контейнера: структура файла не совпала с ожидаемой (не декодер и не устройство)"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "разбор контейнера: особенность контейнера не поддерживается"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "разбор манифеста"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "чтение файла"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "декодирование (возможности устройства или данные кадра)"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "вывод звука"
        else -> "прочее"
    }

    /** В сообщениях исключений бывают адреса: оставляем только имя сайта. */
    private fun scrub(s: String?): String = (s ?: "").replace(Regex("(https?://[^/\\s]+)[^\\s]*")) { it.groupValues[1] + "/…" }.take(400)
}
