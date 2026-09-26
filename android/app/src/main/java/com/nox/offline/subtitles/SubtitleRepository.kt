package com.nox.offline.subtitles

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.NoxDatabase
import com.nox.offline.data.db.SubtitleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Субтитры офлайн: файлы хранятся внутри NOX (files/Subtitles), поэтому
 * доступны после перезапуска и не зависят от прав на исходную папку.
 * Разбор — вне главного потока, с пределами размера.
 */
class SubtitleRepository(private val db: NoxDatabase, private val dir: File) {
    private val cache = ConcurrentHashMap<Long, List<SubtitleParser.Cue>>()

    /** Свой SRT/VTT через системный выбор файла. */
    suspend fun importUri(mediaId: Long, uri: Uri, resolver: ContentResolver): Result<SubtitleEntity> = withContext(Dispatchers.IO) {
        runCatching {
            var name = "subtitles"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = if (c.isNull(1)) -1 else c.getLong(1)
                }
            }
            if (size > SubtitleParser.MAX_BYTES) error("Файл субтитров больше 8 МБ")
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(chunk); if (n < 0) break
                    total += n
                    if (total > SubtitleParser.MAX_BYTES) error("Файл субтитров больше 8 МБ")
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            } ?: error("Нет доступа к файлу")
            val label = name.substringBeforeLast('.').take(60).ifBlank { "Свои субтитры" }
            save(mediaId, decode(bytes), label, language = "", origin = "user")
        }
    }

    /** Сохранить текст субтитров (из загрузки или импорта) после проверки разбором. */
    suspend fun save(mediaId: Long, text: String, label: String, language: String, origin: String): SubtitleEntity =
        withContext(Dispatchers.IO) {
            val cues = SubtitleParser.parse(text)
            if (cues.isEmpty()) throw SubtitleParser.SubtitleException("В файле нет ни одной реплики")
            val format = SubtitleParser.detectFormat(text)
            dir.mkdirs()
            val f = File(dir, "media-$mediaId-${System.currentTimeMillis()}.$format")
            f.writeText(text, Charsets.UTF_8)
            val e = SubtitleEntity(mediaId = mediaId, language = language, label = label, origin = origin, format = format,
                filePath = f.absolutePath, sizeBytes = f.length(), createdAt = System.currentTimeMillis())
            val id = db.subtitles().insert(e)
            cache[id] = cues
            NoxLog.event("subtitles-saved", "media" to mediaId, "origin" to origin, "cues" to cues.size, "lang" to language.ifBlank { null })
            e.copy(id = id)
        }

    /** Реплики дорожки (кэш в памяти). null — файл пропал или испорчен. */
    suspend fun cues(subtitleId: Long): List<SubtitleParser.Cue>? {
        cache[subtitleId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val s = db.subtitles().get(subtitleId) ?: return@withContext null
            val f = File(s.filePath)
            if (!f.exists() || f.length() > SubtitleParser.MAX_BYTES) return@withContext null
            runCatching { SubtitleParser.parse(decode(f.readBytes())) }.getOrNull()?.also {
                if (cache.size > 8) cache.clear()
                cache[subtitleId] = it
            }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.subtitles().get(id)?.let { runCatching { File(it.filePath).delete() } }
        db.subtitles().delete(id)
        cache.remove(id)
    }

    suspend fun onMediaDeleted(mediaId: Long) = withContext(Dispatchers.IO) {
        for (s in db.subtitles().forMedia(mediaId)) runCatching { File(s.filePath).delete() }
        db.subtitles().deleteForMedia(mediaId)
    }

    companion object {
        /** UTF-8 по умолчанию; CP1251 для старых русских SRT без BOM, если UTF-8 не читается. */
        fun decode(bytes: ByteArray): String {
            val utf = String(bytes, Charsets.UTF_8)
            if (!utf.contains('�')) return utf
            return String(bytes, charset("windows-1251"))
        }
    }
}
