package com.nox.offline.ui

import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.ui.components.LibraryItem
import com.nox.offline.ui.library.LibraryFilter
import com.nox.offline.ui.library.LibraryQuery
import com.nox.offline.ui.library.QualityFilter
import com.nox.offline.ui.library.SortKey
import com.nox.offline.ui.library.WatchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryQueryTest {
    private fun item(id: Long, title: String, h: Int, size: Long, dur: Long, at: Long, pos: Long? = null, done: Boolean = false, upd: Long = 0) =
        LibraryItem(
            MediaEntity(id = id, title = title, filePath = "/m/$id.mp4", sizeBytes = size, quality = "${h}p", height = h, durationSec = dur, createdAt = at, uploader = "Автор $id"),
            pos?.let { PlaybackEntity(id, it, dur * 1000, upd, completed = done) },
        )

    private val items = listOf(
        item(1, "Байкал", 480, 300, 600, 10),
        item(2, "архив", 720, 900, 60, 30, pos = 30_000, upd = 5),
        item(3, "Звёзды", 1080, 500, 1200, 20, pos = 1_200_000, done = true),
        item(4, "Горы", 360, 100, 300, 40, pos = 100_000, upd = 9),
    )

    private fun ids(q: String = "", f: LibraryFilter = LibraryFilter()) = LibraryQuery.apply(items, q, f).map { it.id }

    @Test fun defaultIsNewestFirst() = assertEquals(listOf(4L, 2L, 3L, 1L), ids())

    @Test fun searchByTitleAndAuthorIgnoringCase() {
        assertEquals(listOf(1L), ids("байк"))
        assertEquals(listOf(2L), ids("АРХИВ"))
        assertEquals(listOf(3L), ids("автор 3"))
    }

    @Test fun sorts() {
        assertEquals(listOf(2L, 1L, 4L, 3L), ids(f = LibraryFilter(SortKey.TITLE, descending = false)))
        assertEquals(listOf(2L, 3L, 1L, 4L), ids(f = LibraryFilter(SortKey.SIZE)))
        assertEquals(listOf(2L, 4L, 1L, 3L), ids(f = LibraryFilter(SortKey.DURATION, descending = false)))
        assertEquals(listOf(3L, 2L, 1L, 4L), ids(f = LibraryFilter(SortKey.QUALITY)))
    }

    @Test fun watchAndQualityFilters() {
        assertEquals(setOf(2L, 4L), ids(f = LibraryFilter(watch = WatchFilter.IN_PROGRESS)).toSet())
        assertEquals(listOf(3L), ids(f = LibraryFilter(watch = WatchFilter.WATCHED)))
        assertEquals(listOf(1L), ids(f = LibraryFilter(watch = WatchFilter.UNWATCHED)))
        assertEquals(setOf(1L, 4L), ids(f = LibraryFilter(quality = QualityFilter.SD)).toSet())
        assertEquals(listOf(2L), ids(f = LibraryFilter(quality = QualityFilter.HD)))
        assertEquals(listOf(3L), ids(f = LibraryFilter(quality = QualityFilter.FULL_HD)))
    }

    @Test fun continueWatchingIsLastOpenedUnfinished() {
        assertEquals(4L, LibraryQuery.continueWatching(items)?.id)
        // Нет реально начатого видео — карточки нет.
        assertNull(LibraryQuery.continueWatching(items.filter { it.id == 1L || it.id == 3L }))
    }
}
