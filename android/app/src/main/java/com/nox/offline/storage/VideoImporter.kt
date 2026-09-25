package com.nox.offline.storage

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.nox.offline.core.FileNames
import com.nox.offline.core.NoxLog
import com.nox.offline.core.Storage
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.NoxDatabase
import java.io.File
import java.io.IOException

/**
 * Импорт своих видео в медиатеку.
 *
 * Файл копируется потоком в Media/ под временным именем, проверяется
 * (есть изображение и длительность) и только тогда переименовывается и
 * попадает в базу. Место проверяется заранее; исходник не трогается.
 */
class VideoImporter(
    private val context: Context,
    private val db: NoxDatabase,
    private val storage: Storage,
) {
    data class Source(val uri: Uri, val name: String, val size: Long)

    fun describe(uri: Uri): Source {
        var name = "video.mp4"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        return Source(uri, FileOps.safeName(name), size)
    }

    suspend fun import(src: Source, onProgress: (Long, Long) -> Unit): MediaEntity {
        FileOps.requireSpace(src.size, storage.space().freeBytes)
        val ext = src.name.substringAfterLast('.', "mp4").lowercase().take(5).ifBlank { "mp4" }
        val stem = src.name.substringBeforeLast('.')
        val target = FileNames.unique(storage.media, FileNames.targetName(stem, "", ext))
        val tmp = File(storage.downloads, target.name + ".importing")
        try {
            (context.contentResolver.openInputStream(src.uri) ?: throw IOException("Файл недоступен")).use { input ->
                tmp.outputStream().use { out -> FileOps.copy(input, out, src.size, onProgress) }
            }
            if (src.size > 0 && tmp.length() != src.size) throw IOException("Копия неполная: ${tmp.length()} из ${src.size}")
            val meta = inspect(tmp) ?: throw IOException("Это не видео или формат не поддерживается")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = false); tmp.delete()
            }
            val cover = meta.frame?.let { saveCover(it, target) } ?: ""
            meta.frame?.recycle()
            val entity = MediaEntity(
                title = stem.ifBlank { target.nameWithoutExtension },
                filePath = target.absolutePath,
                sizeBytes = target.length(),
                quality = if (meta.height > 0) "${meta.height}" else ext.uppercase(),
                height = meta.height,
                durationSec = meta.durationMs / 1000,
                coverPath = cover,
                createdAt = System.currentTimeMillis(),
                imported = true,
                container = ext,
            )
            val id = db.media().insert(entity)
            NoxLog.event("import-done", "media" to id, "size" to target.length())
            return entity.copy(id = id)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private class Meta(val durationMs: Long, val height: Int, val frame: Bitmap?)

    private fun inspect(file: File): Meta? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (!hasVideo || dur <= 0) return null
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            // «1080p» — по короткой стороне кадра, независимо от поворота.
            val shortSide = minOf(w, h)
            val frame = try {
                r.getFrameAtTime(minOf(dur * 1000 / 5, 5_000_000L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            }
            Meta(dur, shortSide, frame)
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    private fun saveCover(frame: Bitmap, video: File): String = try {
        val scale = 640f / maxOf(frame.width, frame.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
        val out = FileNames.unique(storage.covers, video.nameWithoutExtension + ".jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (bmp !== frame) bmp.recycle()
        out.absolutePath
    } catch (_: Exception) {
        ""
    }
}
