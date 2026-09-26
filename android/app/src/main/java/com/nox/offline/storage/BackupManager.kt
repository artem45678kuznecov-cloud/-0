package com.nox.offline.storage

import com.nox.offline.core.MediaTypes
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nox.offline.BuildConfig
import com.nox.offline.core.FileNames
import com.nox.offline.core.NoxLog
import com.nox.offline.core.Storage
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadMode
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.NoxDatabase
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Резервная копия данных NOX в папку пользователя (SAF) и восстановление.
 *
 * Всегда сохраняются: manifest.json, настройки, медиатека (записи),
 * позиции просмотра, очередь, обложки, а с 0.4.0 — коллекции, сериалы,
 * главы, закладки и субтитры ([LibraryBackup]). По выбору — сами видео и
 * незавершённые .part. Большие файлы копируются потоком.
 *
 * В копию никогда не попадают ключ подписи, токены и cookies: их у NOX нет
 * в настройках, а служебные ключи обновлений и прав на папки исключены.
 *
 * Честно про границы: v0.1.0 такой копии делать не умеет, поэтому для
 * уже установленной v0.1.0 этот механизм ничем не помогает. Он для
 * переходов между будущими версиями и устройствами.
 */
class BackupManager(
    private val context: Context,
    private val db: NoxDatabase,
    private val storage: Storage,
    private val settings: AppSettings,
    private val saf: SafStore,
    private val partOf: (DownloadEntity) -> List<File>,
) {
    companion object {
        const val FORMAT = "nox-backup"
        const val FORMAT_VERSION = 1

        /** Имя из копии допустимо, только если это простое имя файла. */
        /**
         * То же видео в медиатеке. Скачанные узнаются по videoId и ссылке;
         * импортированные (без videoId) — по названию и точному размеру,
         * чтобы повторное восстановление не создавало вторую запись.
         */
        fun findSame(existing: List<MediaEntity>, videoId: String, pageUrl: String, title: String, sizeBytes: Long): MediaEntity? =
            if (videoId.isNotBlank()) existing.firstOrNull { it.videoId == videoId && it.pageUrl == pageUrl }
            else existing.firstOrNull { it.videoId.isBlank() && sizeBytes > 0 && it.sizeBytes == sizeBytes && it.title == title }

        fun isSafeName(name: String): Boolean =
            name.isNotBlank() && name == FileOps.safeName(name, "") && !name.contains('/') && !name.contains('\\') &&
                name != "." && name != ".." && name.length <= 150
    }

    data class ImportReport(val media: Int, val skipped: Int, val positions: Int, val downloads: Int,
                            val library: LibraryBackup.Report? = null)

    /**
     * [prefix] — «NOX-backup» для ручной копии, «NOX-auto» для автокопии
     * (только записи и настройки, без видео).
     */
    suspend fun export(tree: Uri, includeMedia: Boolean, includeParts: Boolean, prefix: String = "NOX-backup",
                       progress: (String, Long, Long) -> Unit): String {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Папка недоступна")
        if (!root.canWrite()) throw IOException("Нет права записи в папку — выберите её заново")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = root.createDirectory("$prefix-$stamp") ?: throw IOException("Не удалось создать папку копии")
        val media = db.media().getAll()
        val playback = db.playback().getAll()
        val downloads = db.downloads().getAll()

        // Место: считаем то, что реально будем копировать.
        val need = (if (includeMedia) media.filter { !it.isExternal }.sumOf { File(it.filePath).length() } else 0L) +
            (if (includeParts) downloads.flatMap(partOf).sumOf { if (it.exists()) it.length() else 0L } else 0L)
        FileOps.requireSpace(need, saf.freeBytes(tree.toString()))

        val covers = dir.createDirectory("covers")!!
        val mediaDir = if (includeMedia) dir.createDirectory("media") else null
        val partsDir = if (includeParts) dir.createDirectory("parts") else null
        var copied = 0L

        val mediaJson = JSONArray()
        for (m in media) {
            val o = JSONObject()
                .put("id", m.id).put("title", m.title).put("sizeBytes", m.sizeBytes).put("quality", m.quality)
                .put("height", m.height).put("durationSec", m.durationSec).put("pageUrl", m.pageUrl)
                .put("videoId", m.videoId).put("createdAt", m.createdAt).put("uploader", m.uploader)
                .put("imported", m.imported).put("fileName", MediaLocator.fileName(m))
                .put("container", m.container).put("width", m.width).put("fps", m.fps)
                .put("codecs", m.codecs).put("variantKey", m.variantKey)
                .put("kind", m.kind).put("protectedFromCleanup", m.protectedFromCleanup)
                .put("subtitleId", m.subtitleId).put("subtitleOffsetMs", m.subtitleOffsetMs)
            if (m.coverPath.isNotBlank() && File(m.coverPath).exists()) {
                val cf = File(m.coverPath)
                copyFile(cf, covers, cf.name, "image/*") { _, _ -> }
                o.put("cover", cf.name)
            }
            if (mediaDir != null && !m.isExternal) {
                val f = File(m.filePath)
                if (f.exists()) {
                    progress("Видео: ${m.title}", copied, need)
                    val base = copied
                    copyFile(f, mediaDir, f.name, MediaTypes.mimeForName(f.name)) { d, _ -> progress("Видео: ${m.title}", base + d, need) }
                    copied += f.length()
                    o.put("mediaFile", f.name)
                }
            }
            mediaJson.put(o)
        }
        val playJson = JSONArray()
        for (p in playback) playJson.put(JSONObject().put("mediaId", p.mediaId).put("positionMs", p.positionMs)
            .put("durationMs", p.durationMs).put("updatedAt", p.updatedAt).put("completed", p.completed))
        val dlJson = JSONArray()
        for (d in downloads.filter { it.status != DownloadStatus.COMPLETED }) {
            val o = JSONObject().put("pageUrl", d.pageUrl).put("quality", d.quality).put("title", d.title)
                .put("customTitle", d.customTitle).put("videoId", d.videoId).put("formatId", d.formatId)
                .put("audioFormatId", d.audioFormatId).put("fileName", d.fileName).put("ext", d.ext)
                .put("height", d.height).put("totalBytes", d.totalBytes).put("mode", d.mode).put("allowSplit", d.allowSplit)
                .put("uploader", d.uploader).put("thumbnailUrl", d.thumbnailUrl).put("durationSec", d.durationSec)
                .put("videoDone", d.videoDone).put("audioDone", d.audioDone)
                .put("videoTotalBytes", d.videoTotalBytes).put("audioTotalBytes", d.audioTotalBytes)
                // 0.3.0: точный план (без прямых адресов — они всё равно протухнут).
                .put("planVersion", d.planVersion).put("extractorKey", d.extractorKey).put("variantKey", d.variantKey)
                .put("width", d.width).put("fps", d.fps).put("vcodec", d.vcodec).put("acodec", d.acodec)
                .put("container", d.container).put("dynamicRange", d.dynamicRange).put("audioLang", d.audioLang)
                .put("videoExact", d.videoExact).put("audioExact", d.audioExact)
                .put("videoChunk", d.videoChunk).put("audioChunk", d.audioChunk)
                // 0.4.0
                .put("audioOnly", d.audioOnly).put("subtitleRequest", d.subtitleRequest)
                .put("collectionId", d.collectionId).put("collectionPosition", d.collectionPosition)
                .put("queueOrder", d.queueOrder)
            if (partsDir != null) {
                val names = JSONArray()
                for (f in partOf(d)) if (f.exists()) {
                    val base = copied
                    copyFile(f, partsDir, f.name, "application/octet-stream") { x, _ -> progress("Часть: ${d.displayTitle}", base + x, need) }
                    copied += f.length()
                    names.put(f.name)
                }
                o.put("parts", names)
            }
            dlJson.put(o)
        }
        val settingsJson = JSONObject()
        for ((k, v) in settings.exportAll()) {
            if (!AppSettings.isBackupKey(k)) continue
            settingsJson.put(k, v)
        }
        val libraryJson = exportLibrary(dir)
        writeText(dir, "library.json", JSONObject().put("media", mediaJson).put("playback", playJson).put("downloads", dlJson)
            .put(LibraryBackup.KEY, libraryJson).toString(2))
        writeText(dir, "settings.json", settingsJson.toString(2))
        writeText(dir, "manifest.json", JSONObject()
            .put("format", FORMAT).put("formatVersion", FORMAT_VERSION)
            .put("appVersion", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("schemaVersion", NoxDatabase.VERSION).put("createdAt", System.currentTimeMillis())
            .put("includesMedia", includeMedia).put("includesParts", includeParts)
            .put("counts", JSONObject().put("media", media.size).put("playback", playback.size).put("downloads", dlJson.length())
                .put("collections", libraryJson.optJSONArray("collections")?.length() ?: 0)
                .put("chapters", libraryJson.optJSONArray("chapters")?.length() ?: 0)
                .put("bookmarks", libraryJson.optJSONArray("bookmarks")?.length() ?: 0)
                .put("subtitles", libraryJson.optJSONArray("subtitles")?.length() ?: 0))
            .toString(2))
        NoxLog.event("backup-export", "media" to media.size, "withMedia" to includeMedia, "withParts" to includeParts, "prefix" to prefix)
        return "Резервная копия сохранена: ${dir.name}"
    }

    /** Коллекции и разметка; файлы субтитров и свои обложки — рядом, в папках копии. */
    private suspend fun exportLibrary(dir: DocumentFile): JSONObject {
        val cols = db.collections().getAll()
        val subs = db.subtitles().getAll()
        val subDir = if (subs.isNotEmpty()) dir.createDirectory("subtitles") else null
        val subFiles = ArrayList<Pair<com.nox.offline.data.db.SubtitleEntity, String>>()
        for (s in subs) {
            val f = File(s.filePath)
            if (subDir == null || !f.exists()) continue
            copyFile(f, subDir, f.name, "text/plain") { _, _ -> }
            subFiles.add(s to f.name)
        }
        val coverMap = HashMap<Long, String>()
        val ownCovers = cols.filter { it.coverPath.isNotBlank() && File(it.coverPath).exists() }
        if (ownCovers.isNotEmpty()) {
            val cdir = dir.findFile("covers") ?: dir.createDirectory("covers")!!
            for (c in ownCovers) {
                val f = File(c.coverPath)
                copyFile(f, cdir, f.name, "image/jpeg") { _, _ -> }
                coverMap[c.id] = f.name
            }
        }
        return LibraryBackup.toJson(LibraryBackup.Snapshot(
            collections = cols, items = db.collections().allItems(), seasons = db.collections().allSeasons(),
            chapters = db.chapters().getAll(), progress = db.chapters().allProgress(), bookmarks = db.chapters().allBookmarks(),
            subtitles = subFiles, covers = coverMap,
        ))
    }

    suspend fun import(tree: Uri, progress: (String, Long, Long) -> Unit): ImportReport {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Папка недоступна")
        val manifest = JSONObject(readText(dir, "manifest.json") ?: throw IOException("Это не резервная копия NOX: нет manifest.json"))
        if (manifest.optString("format") != FORMAT) throw IOException("Неизвестный формат копии")
        if (manifest.optInt("formatVersion") > FORMAT_VERSION) throw IOException("Копия сделана более новой версией NOX — обновите приложение")
        val lib = JSONObject(readText(dir, "library.json") ?: "{}")
        val coversDir = dir.findFile("covers")
        val mediaDir = dir.findFile("media")
        val partsDir = dir.findFile("parts")

        val existing = db.media().getAll()
        val idMap = HashMap<Long, Long>()
        val subtitleChoice = HashMap<Long, Long>()
        var imported = 0
        var skipped = 0
        val mediaArr = lib.optJSONArray("media") ?: JSONArray()
        for (i in 0 until mediaArr.length()) {
            val o = mediaArr.getJSONObject(i)
            val oldId = o.optLong("id")
            val videoId = o.optString("videoId")
            val pageUrl = o.optString("pageUrl")
            // Уже есть в медиатеке — не дублируем, только сопоставляем позицию.
            val same = findSame(existing, videoId, pageUrl, o.optString("title"), o.optLong("sizeBytes"))
            if (same != null) { idMap[oldId] = same.id; skipped++; continue }
            val mediaFileName = o.optString("mediaFile")
            val fileName = o.optString("fileName")
            val target: File? = when {
                mediaFileName.isNotBlank() && isSafeName(mediaFileName) && mediaDir != null -> {
                    val src = mediaDir.findFile(mediaFileName)
                    if (src == null) null else {
                        FileOps.requireSpace(src.length(), storage.space().freeBytes)
                        val dst = FileNames.unique(storage.media, mediaFileName)
                        progress("Видео: ${o.optString("title")}", 0, src.length())
                        copyIn(src, dst) { d, t -> progress("Видео: ${o.optString("title")}", d, t) }
                        dst
                    }
                }
                fileName.isNotBlank() && isSafeName(fileName) -> File(storage.media, fileName).takeIf { it.exists() }
                else -> null
            }
            if (target == null) { skipped++; continue }
            val coverName = o.optString("cover")
            val cover = if (coverName.isNotBlank() && isSafeName(coverName) && coversDir != null) {
                coversDir.findFile(coverName)?.let { src ->
                    val dst = FileNames.unique(storage.covers, coverName)
                    copyIn(src, dst) { _, _ -> }
                    dst.absolutePath
                } ?: ""
            } else ""
            val newId = db.media().insert(MediaEntity(
                title = o.optString("title", target.nameWithoutExtension), filePath = target.absolutePath,
                sizeBytes = target.length(), quality = o.optString("quality"), height = o.optInt("height"),
                durationSec = o.optLong("durationSec"), coverPath = cover, pageUrl = pageUrl, videoId = videoId,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()), uploader = o.optString("uploader"),
                imported = o.optBoolean("imported"),
                container = o.optString("container").ifBlank { target.extension.lowercase() },
                width = o.optInt("width"), fps = o.optInt("fps"), codecs = o.optString("codecs"),
                variantKey = o.optString("variantKey"),
                kind = o.optString("kind", "video").ifBlank { "video" },
                protectedFromCleanup = o.optBoolean("protectedFromCleanup"),
                subtitleOffsetMs = o.optLong("subtitleOffsetMs"),
            ))
            idMap[oldId] = newId
            subtitleChoice[newId] = o.optLong("subtitleId")
            imported++
        }
        var positions = 0
        val playArr = lib.optJSONArray("playback") ?: JSONArray()
        for (i in 0 until playArr.length()) {
            val o = playArr.getJSONObject(i)
            val newId = idMap[o.optLong("mediaId")] ?: continue
            val current = db.playback().get(newId)
            if (current != null && current.updatedAt >= o.optLong("updatedAt")) continue
            db.playback().upsert(PlaybackEntity(newId, o.optLong("positionMs"), o.optLong("durationMs"),
                o.optLong("updatedAt"), o.optBoolean("completed")))
            positions++
        }
        // 0.4.0: коллекции и разметка. Старые копии без этого раздела восстанавливаются как раньше.
        var libReport: LibraryBackup.Report? = null
        var colMap: Map<Long, Long> = emptyMap()
        lib.optJSONObject(LibraryBackup.KEY)?.let { o ->
            val restored = LibraryBackup.restore(o, idMap, RoomStore(dir.findFile("subtitles"), coversDir))
            libReport = restored.report
            colMap = restored.collections
            for ((mediaId, oldSub) in subtitleChoice) {
                val sub = restored.subtitles[oldSub] ?: continue
                db.media().setSubtitle(mediaId, sub)
            }
        }
        var downloads = 0
        val dlArr = lib.optJSONArray("downloads") ?: JSONArray()
        for (i in 0 until dlArr.length()) {
            val o = dlArr.getJSONObject(i)
            val pageUrl = o.optString("pageUrl")
            if (pageUrl.isBlank() || db.downloads().countLiveFor(pageUrl) > 0) continue
            val fileName = o.optString("fileName").takeIf { isSafeName(it) } ?: ""
            val now = System.currentTimeMillis()
            var entity = DownloadEntity(
                pageUrl = pageUrl, quality = o.optString("quality", "480"), title = o.optString("title"),
                customTitle = o.optString("customTitle"), videoId = o.optString("videoId"),
                formatId = o.optString("formatId"), audioFormatId = o.optString("audioFormatId"),
                fileName = fileName, ext = o.optString("ext", "mp4"), height = o.optInt("height"),
                totalBytes = o.optLong("totalBytes"), mode = o.optString("mode", DownloadMode.PROGRESSIVE),
                allowSplit = o.optBoolean("allowSplit"), uploader = o.optString("uploader"),
                thumbnailUrl = o.optString("thumbnailUrl"), durationSec = o.optLong("durationSec"),
                videoDone = o.optBoolean("videoDone"), audioDone = o.optBoolean("audioDone"),
                videoTotalBytes = o.optLong("videoTotalBytes"), audioTotalBytes = o.optLong("audioTotalBytes"),
                planVersion = o.optInt("planVersion"), extractorKey = o.optString("extractorKey"),
                variantKey = o.optString("variantKey"), width = o.optInt("width"), fps = o.optInt("fps"),
                vcodec = o.optString("vcodec"), acodec = o.optString("acodec"), container = o.optString("container"),
                dynamicRange = o.optString("dynamicRange"), audioLang = o.optString("audioLang"),
                videoExact = o.optBoolean("videoExact"), audioExact = o.optBoolean("audioExact"),
                videoChunk = o.optLong("videoChunk"), audioChunk = o.optLong("audioChunk"),
                audioOnly = o.optBoolean("audioOnly"), subtitleRequest = o.optString("subtitleRequest", "[]").ifBlank { "[]" },
                collectionId = colMap[o.optLong("collectionId")] ?: 0, collectionPosition = o.optLong("collectionPosition"),
                queueOrder = o.optLong("queueOrder", now).takeIf { it > 0 } ?: now,
                // Прямые адреса протухают: восстановленное задание стоит на паузе и разберёт ссылку заново.
                status = DownloadStatus.PAUSED, createdAt = now, updatedAt = now,
            )
            val parts = o.optJSONArray("parts")
            if (parts != null && partsDir != null && fileName.isNotBlank()) {
                for (j in 0 until parts.length()) {
                    val n = parts.getString(j)
                    if (!isSafeName(n) || !n.startsWith(fileName)) continue
                    val src = partsDir.findFile(n) ?: continue
                    FileOps.requireSpace(src.length(), storage.space().freeBytes)
                    copyIn(src, File(storage.downloads, n)) { d, t -> progress("Часть: ${entity.displayTitle}", d, t) }
                }
            } else {
                entity = entity.copy(videoDone = false, audioDone = false)
            }
            val id = db.downloads().insert(entity)
            val size = partOf(entity.copy(id = id)).sumOf { if (it.exists()) it.length() else 0L }
            db.downloads().update(entity.copy(id = id, downloadedBytes = size))
            downloads++
        }
        readText(dir, "settings.json")?.let { txt ->
            val o = JSONObject(txt)
            val map = HashMap<String, Any?>()
            for (k in o.keys()) map[k] = o.get(k)
            settings.importAll(map)
        }
        NoxLog.event("backup-import", "media" to imported, "skipped" to skipped, "positions" to positions, "downloads" to downloads,
            "collections" to libReport?.collections, "chapters" to libReport?.chapters)
        return ImportReport(imported, skipped, positions, downloads, libReport)
    }

    /** Восстановление разметки в Room; файлы субтитров и обложек копируются внутрь NOX. */
    private inner class RoomStore(private val subDir: DocumentFile?, private val backupCovers: DocumentFile?) : LibraryBackup.Store {
        private val c get() = db.collections()
        private val ch get() = db.chapters()
        override suspend fun collections() = c.getAll()
        override suspend fun insertCollection(c: com.nox.offline.data.db.CollectionEntity) = this.c.insert(c)
        override suspend fun insertItem(i: com.nox.offline.data.db.CollectionItemEntity) = c.insertItem(i)
        override suspend fun seasons() = c.allSeasons()
        override suspend fun insertSeason(s: com.nox.offline.data.db.SeasonEntity) { c.upsertSeason(s) }
        override suspend fun chaptersFor(mediaId: Long) = ch.forMedia(mediaId)
        override suspend fun insertChapter(c: com.nox.offline.data.db.ChapterEntity) = ch.insert(c)
        override suspend fun progress(chapterId: Long) = ch.progress(chapterId)
        override suspend fun upsertProgress(p: com.nox.offline.data.db.SegmentProgressEntity) = ch.upsertProgress(p)
        override suspend fun bookmarksFor(mediaId: Long) = ch.allBookmarks().filter { it.mediaId == mediaId }
        override suspend fun insertBookmark(b: com.nox.offline.data.db.BookmarkEntity) = ch.insertBookmark(b)
        override suspend fun subtitlesFor(mediaId: Long) = db.subtitles().forMedia(mediaId)
        override suspend fun insertSubtitle(s: com.nox.offline.data.db.SubtitleEntity) = db.subtitles().insert(s)
        override suspend fun restoreSubtitleFile(name: String, mediaId: Long): Pair<String, Long>? {
            if (!isSafeName(name) || subDir == null) return null
            val src = subDir.findFile(name) ?: return null
            if (src.length() > com.nox.offline.subtitles.SubtitleParser.MAX_BYTES) return null
            val dst = FileNames.unique(storage.subtitles, name)
            copyIn(src, dst) { _, _ -> }
            return dst.absolutePath to dst.length()
        }
        override suspend fun restoreCoverFile(name: String): String {
            if (!isSafeName(name) || backupCovers == null) return ""
            val src = backupCovers.findFile(name) ?: return ""
            val dst = FileNames.unique(storage.covers, name)
            copyIn(src, dst) { _, _ -> }
            return dst.absolutePath
        }
    }

    // ------------------------------------------------------------------

    private suspend fun copyFile(src: File, dir: DocumentFile, name: String, mime: String, onProgress: (Long, Long) -> Unit) {
        dir.findFile(name)?.delete()
        val doc = dir.createFile(mime, name) ?: throw IOException("Не удалось создать $name")
        FileInputStream(src).use { input ->
            (context.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("Нет потока записи")).use { out ->
                FileOps.copy(input, out, src.length(), onProgress)
            }
        }
    }

    private suspend fun copyIn(src: DocumentFile, dst: File, onProgress: (Long, Long) -> Unit) {
        val tmp = File(dst.parentFile, dst.name + ".restoring")
        try {
            (context.contentResolver.openInputStream(src.uri) ?: throw IOException("Нет доступа к файлу копии")).use { input ->
                tmp.outputStream().use { out -> FileOps.copy(input, out, src.length(), onProgress) }
            }
            if (src.length() > 0 && tmp.length() != src.length()) throw IOException("Файл копии прочитан не полностью")
            if (!tmp.renameTo(dst)) throw IOException("Не удалось сохранить ${dst.name}")
        } catch (t: Throwable) {
            tmp.delete(); throw t
        }
    }

    private fun writeText(dir: DocumentFile, name: String, text: String) {
        dir.findFile(name)?.delete()
        val doc = dir.createFile("application/json", name) ?: throw IOException("Не удалось создать $name")
        (context.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("Нет потока записи")).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun readText(dir: DocumentFile, name: String): String? {
        val doc = dir.findFile(name) ?: return null
        if (doc.length() > 32L * 1024 * 1024) throw IOException("$name слишком большой")
        return context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
