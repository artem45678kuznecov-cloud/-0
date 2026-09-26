package com.nox.offline.storage

import com.nox.offline.data.db.BookmarkEntity
import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.CollectionEntity
import com.nox.offline.data.db.CollectionItemEntity
import com.nox.offline.data.db.CollectionType
import com.nox.offline.data.db.SeasonEntity
import com.nox.offline.data.db.SegmentProgressEntity
import com.nox.offline.data.db.SubtitleEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Коллекции и разметка в резервной копии: восстановление без дублей и без перезаписи чужого. */
class LibraryBackupTest {
    /** База в памяти с теми же правилами уникальности, что и Room. */
    private class MemStore : LibraryBackup.Store {
        val cols = ArrayList<CollectionEntity>()
        val items = ArrayList<CollectionItemEntity>()
        val seasons = ArrayList<SeasonEntity>()
        val chapters = ArrayList<ChapterEntity>()
        val progress = HashMap<Long, SegmentProgressEntity>()
        val bookmarks = ArrayList<BookmarkEntity>()
        val subs = ArrayList<SubtitleEntity>()
        var nextId = 100L
        override suspend fun collections() = cols.toList()
        override suspend fun insertCollection(c: CollectionEntity): Long = (nextId++).also { cols.add(c.copy(id = it)) }
        override suspend fun insertItem(i: CollectionItemEntity): Long {
            if (items.any { it.collectionId == i.collectionId && it.mediaId == i.mediaId && it.chapterId == i.chapterId &&
                    it.sourceKey == i.sourceKey }) return -1
            return (nextId++).also { items.add(i.copy(id = it)) }
        }
        override suspend fun seasons() = seasons.toList()
        override suspend fun insertSeason(s: SeasonEntity) { seasons.add(s) }
        override suspend fun chaptersFor(mediaId: Long) = chapters.filter { it.mediaId == mediaId }
        override suspend fun insertChapter(c: ChapterEntity): Long = (nextId++).also { chapters.add(c.copy(id = it)) }
        override suspend fun progress(chapterId: Long) = progress[chapterId]
        override suspend fun upsertProgress(p: SegmentProgressEntity) { progress[p.chapterId] = p }
        override suspend fun bookmarksFor(mediaId: Long) = bookmarks.filter { it.mediaId == mediaId }
        override suspend fun insertBookmark(b: BookmarkEntity): Long = (nextId++).also { bookmarks.add(b.copy(id = it)) }
        override suspend fun subtitlesFor(mediaId: Long) = subs.filter { it.mediaId == mediaId }
        override suspend fun restoreSubtitleFile(name: String, mediaId: Long): Pair<String, Long>? =
            if (name.isBlank()) null else "/nox/Subtitles/$name" to 1234L
        override suspend fun insertSubtitle(s: SubtitleEntity): Long = (nextId++).also { subs.add(s.copy(id = it)) }
        override suspend fun restoreCoverFile(name: String) = "/nox/Covers/$name"
    }

    private fun source(): JSONObject {
        val fav = CollectionEntity(id = 1, title = "Избранное", type = CollectionType.FAVORITES, systemKey = CollectionType.FAVORITES,
            createdAt = 10, updatedAt = 10)
        val series = CollectionEntity(id = 2, title = "Аниме-марафон", type = CollectionType.SERIES, createdAt = 500, updatedAt = 900,
            pinned = true)
        val chapter = ChapterEntity(id = 7, mediaId = 11, title = "Серия 2", startMs = 1_440_000, kind = ChapterKind.EPISODE, createdAt = 1)
        return LibraryBackup.toJson(LibraryBackup.Snapshot(
            collections = listOf(fav, series),
            items = listOf(
                CollectionItemEntity(collectionId = 1, mediaId = 11, addedAt = 1),
                CollectionItemEntity(collectionId = 2, mediaId = 11, chapterId = 7, season = 1, episode = 2, addedAt = 1),
                CollectionItemEntity(collectionId = 2, mediaId = 0, sourceKey = "youtube:abc", sourceUrl = "https://y/abc",
                    season = 1, episode = 3, addedAt = 1),
                // Локальный файл без источника, которого нет на новом устройстве, — пропадает, а не превращается в мусор.
                CollectionItemEntity(collectionId = 2, mediaId = 99, addedAt = 1),
            ),
            seasons = listOf(SeasonEntity(collectionId = 2, number = 1, title = "Первый сезон")),
            chapters = listOf(chapter),
            progress = listOf(SegmentProgressEntity(7, 60_000, false, 50)),
            bookmarks = listOf(BookmarkEntity(mediaId = 11, positionMs = 30 * 3600_000L, title = "Сутки+", note = "n", createdAt = 1)),
            subtitles = listOf(SubtitleEntity(id = 3, mediaId = 11, language = "ru", label = "Русский", origin = "source",
                format = "vtt", filePath = "/old/a.vtt", sizeBytes = 1234, createdAt = 1) to "a.vtt"),
        ))
    }

    @Test fun `restore maps ids, keeps pending with known source, and is idempotent`() = runBlocking {
        val store = MemStore()
        // На устройстве уже есть встроенное «Избранное» и чужая коллекция с тем же названием.
        store.cols.add(CollectionEntity(id = 50, title = "Избранное", type = CollectionType.FAVORITES,
            systemKey = CollectionType.FAVORITES, createdAt = 1, updatedAt = 1))
        store.cols.add(CollectionEntity(id = 51, title = "Аниме-марафон", type = CollectionType.SERIES, createdAt = 777, updatedAt = 777))
        val json = JSONObject(source().toString())    // как после записи и чтения файла
        val mediaMap = mapOf(11L to 211L)

        val r1 = LibraryBackup.restore(json, mediaMap, store)
        assertEquals(1, r1.report.collections)                 // только сериал; «Избранное» сопоставлено
        assertEquals(50L, r1.collections[1L])
        val seriesId = r1.collections[2L]!!
        assertTrue(seriesId != 51L)                            // одноимённая чужая коллекция не перезаписана
        assertEquals(3, r1.report.items)
        assertEquals(1, r1.report.chapters)
        val newChapter = store.chapters.single().id
        assertTrue(store.items.any { it.collectionId == seriesId && it.mediaId == 211L && it.chapterId == newChapter && it.episode == 2 })
        assertTrue(store.items.any { it.collectionId == seriesId && it.mediaId == 0L && it.sourceKey == "youtube:abc" })
        assertTrue(store.items.none { it.mediaId == 99L })
        assertEquals(60_000L, store.progress[newChapter]!!.positionMs)
        assertEquals(30 * 3600_000L, store.bookmarks.single().positionMs)
        assertEquals(store.subs.single().id, r1.subtitles[3L])
        assertEquals("Первый сезон", store.seasons.single().title)

        // Повторное восстановление той же копии ничего не удваивает.
        val before = listOf(store.cols.size, store.items.size, store.chapters.size, store.bookmarks.size, store.subs.size, store.seasons.size)
        val r2 = LibraryBackup.restore(json, mediaMap, store)
        assertEquals(before, listOf(store.cols.size, store.items.size, store.chapters.size, store.bookmarks.size, store.subs.size,
            store.seasons.size))
        assertEquals(0, r2.report.collections + r2.report.items + r2.report.chapters + r2.report.bookmarks + r2.report.subtitles)
        assertEquals(seriesId, r2.collections[2L])
    }

    @Test fun `old backups without the library section restore nothing extra`() = runBlocking {
        val store = MemStore()
        val r = LibraryBackup.restore(JSONObject(), emptyMap(), store)
        assertEquals(0, r.report.collections)
        assertTrue(store.items.isEmpty())
    }

    @Test fun `newer progress on the device is not overwritten by an older backup`() = runBlocking {
        val store = MemStore()
        val json = JSONObject(source().toString())
        LibraryBackup.restore(json, mapOf(11L to 211L), store)
        val ch = store.chapters.single().id
        store.progress[ch] = SegmentProgressEntity(ch, 999_000, true, 10_000)
        LibraryBackup.restore(json, mapOf(11L to 211L), store)
        assertEquals(999_000L, store.progress[ch]!!.positionMs)
    }
}
