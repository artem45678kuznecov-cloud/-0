package com.nox.offline.storage

import com.nox.offline.data.db.BookmarkEntity
import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.CollectionEntity
import com.nox.offline.data.db.CollectionItemEntity
import com.nox.offline.data.db.SeasonEntity
import com.nox.offline.data.db.SegmentProgressEntity
import com.nox.offline.data.db.SubtitleEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Коллекции, сериалы, главы, закладки и субтитры в резервной копии
 * (library.json → "library04"). Только записи и разметка — не видео.
 *
 * Восстановление не создаёт дублей при повторе и не перезаписывает чужое:
 *  - встроенные списки сопоставляются по systemKey;
 *  - своя коллекция — по названию, виду и времени создания вместе, поэтому
 *    другая коллекция с тем же названием остаётся отдельной;
 *  - главы — по видео, началу и виду; закладки — по видео, времени и названию;
 *  - элементы коллекций защищены уникальным индексом (INSERT OR IGNORE).
 *
 * Чистый код без Android — проверяется JVM-тестами через [Store].
 */
object LibraryBackup {
    const val KEY = "library04"

    data class Snapshot(
        val collections: List<CollectionEntity>,
        val items: List<CollectionItemEntity>,
        val seasons: List<SeasonEntity>,
        val chapters: List<ChapterEntity>,
        val progress: List<SegmentProgressEntity>,
        val bookmarks: List<BookmarkEntity>,
        /** Субтитры и имя файла в папке копии. */
        val subtitles: List<Pair<SubtitleEntity, String>>,
        /** Своя обложка коллекции → имя файла в папке копии. */
        val covers: Map<Long, String> = emptyMap(),
    )

    fun toJson(s: Snapshot): JSONObject {
        val cols = JSONArray()
        for (c in s.collections) cols.put(JSONObject().put("id", c.id).put("title", c.title).put("description", c.description)
            .put("type", c.type).put("coverMediaId", c.coverMediaId).put("pinned", c.pinned).put("sortOrder", c.sortOrder)
            .put("tags", c.tags).put("sourceUrl", c.sourceUrl).put("systemKey", c.systemKey)
            .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt).put("coverFile", s.covers[c.id] ?: ""))
        val items = JSONArray()
        for (i in s.items) items.put(JSONObject().put("collectionId", i.collectionId).put("mediaId", i.mediaId)
            .put("chapterId", i.chapterId).put("position", i.position).put("season", i.season).put("episode", i.episode)
            .put("title", i.title).put("sourceUrl", i.sourceUrl).put("sourceKey", i.sourceKey)
            .put("unavailableReason", i.unavailableReason).put("addedAt", i.addedAt))
        val seasons = JSONArray()
        for (x in s.seasons) seasons.put(JSONObject().put("collectionId", x.collectionId).put("number", x.number).put("title", x.title))
        val chapters = JSONArray()
        for (c in s.chapters) chapters.put(JSONObject().put("id", c.id).put("mediaId", c.mediaId).put("title", c.title)
            .put("startMs", c.startMs).put("endMs", c.endMs).put("kind", c.kind).put("origin", c.origin).put("createdAt", c.createdAt))
        val progress = JSONArray()
        for (p in s.progress) progress.put(JSONObject().put("chapterId", p.chapterId).put("positionMs", p.positionMs)
            .put("completed", p.completed).put("updatedAt", p.updatedAt))
        val bookmarks = JSONArray()
        for (b in s.bookmarks) bookmarks.put(JSONObject().put("mediaId", b.mediaId).put("positionMs", b.positionMs)
            .put("title", b.title).put("note", b.note).put("createdAt", b.createdAt))
        val subs = JSONArray()
        for ((x, file) in s.subtitles) subs.put(JSONObject().put("id", x.id).put("mediaId", x.mediaId).put("language", x.language)
            .put("label", x.label).put("origin", x.origin).put("format", x.format).put("file", file)
            .put("sizeBytes", x.sizeBytes).put("createdAt", x.createdAt))
        return JSONObject().put("collections", cols).put("items", items).put("seasons", seasons).put("chapters", chapters)
            .put("progress", progress).put("bookmarks", bookmarks).put("subtitles", subs)
    }

    /** Доступ к базе при восстановлении (в приложении — Room, в тестах — память). */
    interface Store {
        suspend fun collections(): List<CollectionEntity>
        suspend fun insertCollection(c: CollectionEntity): Long
        suspend fun insertItem(i: CollectionItemEntity): Long
        suspend fun seasons(): List<SeasonEntity>
        suspend fun insertSeason(s: SeasonEntity)
        suspend fun chaptersFor(mediaId: Long): List<ChapterEntity>
        suspend fun insertChapter(c: ChapterEntity): Long
        suspend fun progress(chapterId: Long): SegmentProgressEntity?
        suspend fun upsertProgress(p: SegmentProgressEntity)
        suspend fun bookmarksFor(mediaId: Long): List<BookmarkEntity>
        suspend fun insertBookmark(b: BookmarkEntity): Long
        suspend fun subtitlesFor(mediaId: Long): List<SubtitleEntity>
        /** Скопировать файл субтитров из копии; null — файла нет или имя небезопасно. */
        suspend fun restoreSubtitleFile(name: String, mediaId: Long): Pair<String, Long>?
        suspend fun insertSubtitle(s: SubtitleEntity): Long
        /** Скопировать свою обложку коллекции; '' — нет. */
        suspend fun restoreCoverFile(name: String): String
    }

    data class Report(val collections: Int, val items: Int, val chapters: Int, val bookmarks: Int, val subtitles: Int)

    /** Итог и карты «старый id → новый» для полей, которые на них ссылаются. */
    data class Restored(val report: Report, val subtitles: Map<Long, Long>, val collections: Map<Long, Long>)

    /**
     * [mediaMap] — старый id видео → id в этой медиатеке (новый или уже
     * существовавший).
     */
    suspend fun restore(o: JSONObject, mediaMap: Map<Long, Long>, store: Store): Restored {
        val colMap = HashMap<Long, Long>()
        var nCol = 0
        val existing = store.collections().toMutableList()
        val cols = o.optJSONArray("collections") ?: JSONArray()
        for (k in 0 until cols.length()) {
            val c = cols.getJSONObject(k)
            val oldId = c.optLong("id")
            val systemKey = c.optString("systemKey")
            val title = c.optString("title")
            val type = c.optString("type", "album")
            val createdAt = c.optLong("createdAt")
            val same = if (systemKey.isNotBlank()) existing.firstOrNull { it.systemKey == systemKey }
            else existing.firstOrNull { it.systemKey.isBlank() && it.title == title && it.type == type && it.createdAt == createdAt }
            if (same != null) { colMap[oldId] = same.id; continue }
            val e = CollectionEntity(title = title.ifBlank { "Коллекция" }, description = c.optString("description"), type = type,
                coverMediaId = mediaMap[c.optLong("coverMediaId")] ?: 0, pinned = c.optBoolean("pinned"),
                sortOrder = c.optLong("sortOrder"), tags = c.optString("tags"), sourceUrl = c.optString("sourceUrl"),
                systemKey = systemKey, createdAt = createdAt, updatedAt = c.optLong("updatedAt", createdAt),
                coverPath = c.optString("coverFile").let { if (it.isBlank()) "" else store.restoreCoverFile(it) })
            val id = store.insertCollection(e)
            existing.add(e.copy(id = id))
            colMap[oldId] = id
            nCol++
        }

        // Главы раньше элементов: виртуальные серии ссылаются на них.
        val chMap = HashMap<Long, Long>()
        var nCh = 0
        val chapters = o.optJSONArray("chapters") ?: JSONArray()
        for (k in 0 until chapters.length()) {
            val c = chapters.getJSONObject(k)
            val mediaId = mediaMap[c.optLong("mediaId")] ?: continue
            val start = c.optLong("startMs")
            val kind = c.optString("kind", "chapter")
            val same = store.chaptersFor(mediaId).firstOrNull { it.startMs == start && it.kind == kind }
            if (same != null) { chMap[c.optLong("id")] = same.id; continue }
            chMap[c.optLong("id")] = store.insertChapter(ChapterEntity(mediaId = mediaId, title = c.optString("title"),
                startMs = start, endMs = c.optLong("endMs", -1), kind = kind, origin = c.optString("origin", "user"),
                createdAt = c.optLong("createdAt")))
            nCh++
        }

        var nItems = 0
        val items = o.optJSONArray("items") ?: JSONArray()
        for (k in 0 until items.length()) {
            val i = items.getJSONObject(k)
            val colId = colMap[i.optLong("collectionId")] ?: continue
            val oldMedia = i.optLong("mediaId")
            val oldChapter = i.optLong("chapterId")
            val mediaId = if (oldMedia > 0) mediaMap[oldMedia] ?: 0 else 0
            var chapterId = if (oldChapter > 0) chMap[oldChapter] ?: 0 else 0
            val sourceKey = i.optString("sourceKey")
            // Видео нет в этой медиатеке: остаётся «не скачано», только если источник известен.
            if (mediaId == 0L) {
                if (sourceKey.isBlank()) continue
                chapterId = 0
            } else if (oldChapter > 0 && chapterId == 0L) continue
            if (mediaId > 0 && oldChapter == 0L) chapterId = 0
            val id = store.insertItem(CollectionItemEntity(collectionId = colId, mediaId = mediaId, chapterId = chapterId,
                position = i.optLong("position"), season = i.optInt("season"), episode = i.optInt("episode"),
                title = i.optString("title"), sourceUrl = i.optString("sourceUrl"), sourceKey = sourceKey,
                unavailableReason = i.optString("unavailableReason"), addedAt = i.optLong("addedAt")))
            if (id > 0) nItems++
        }

        val seasons = o.optJSONArray("seasons") ?: JSONArray()
        val haveSeasons = store.seasons()
        for (k in 0 until seasons.length()) {
            val s = seasons.getJSONObject(k)
            val colId = colMap[s.optLong("collectionId")] ?: continue
            val n = s.optInt("number")
            if (haveSeasons.any { it.collectionId == colId && it.number == n }) continue
            store.insertSeason(SeasonEntity(collectionId = colId, number = n, title = s.optString("title")))
        }

        val progress = o.optJSONArray("progress") ?: JSONArray()
        for (k in 0 until progress.length()) {
            val p = progress.getJSONObject(k)
            val chId = chMap[p.optLong("chapterId")] ?: continue
            val cur = store.progress(chId)
            val upd = p.optLong("updatedAt")
            if (cur != null && cur.updatedAt >= upd) continue
            store.upsertProgress(SegmentProgressEntity(chId, p.optLong("positionMs"), p.optBoolean("completed"), upd))
        }

        var nBm = 0
        val bms = o.optJSONArray("bookmarks") ?: JSONArray()
        for (k in 0 until bms.length()) {
            val b = bms.getJSONObject(k)
            val mediaId = mediaMap[b.optLong("mediaId")] ?: continue
            val pos = b.optLong("positionMs")
            val title = b.optString("title")
            if (store.bookmarksFor(mediaId).any { it.positionMs == pos && it.title == title }) continue
            store.insertBookmark(BookmarkEntity(mediaId = mediaId, positionMs = pos, title = title, note = b.optString("note"),
                createdAt = b.optLong("createdAt")))
            nBm++
        }

        val subMap = HashMap<Long, Long>()
        var nSub = 0
        val subs = o.optJSONArray("subtitles") ?: JSONArray()
        for (k in 0 until subs.length()) {
            val s = subs.getJSONObject(k)
            val mediaId = mediaMap[s.optLong("mediaId")] ?: continue
            val label = s.optString("label")
            val origin = s.optString("origin", "user")
            val language = s.optString("language")
            val size = s.optLong("sizeBytes")
            val same = store.subtitlesFor(mediaId).firstOrNull {
                it.label == label && it.origin == origin && it.language == language && it.sizeBytes == size
            }
            if (same != null) { subMap[s.optLong("id")] = same.id; continue }
            val (path, bytes) = store.restoreSubtitleFile(s.optString("file"), mediaId) ?: continue
            subMap[s.optLong("id")] = store.insertSubtitle(SubtitleEntity(mediaId = mediaId, language = language, label = label,
                origin = origin, format = s.optString("format", "srt"), filePath = path, sizeBytes = bytes,
                createdAt = s.optLong("createdAt")))
            nSub++
        }
        return Restored(Report(nCol, nItems, nCh, nBm, nSub), subMap, colMap)
    }
}
