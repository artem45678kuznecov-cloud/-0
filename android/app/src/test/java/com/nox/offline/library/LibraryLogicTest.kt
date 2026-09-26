package com.nox.offline.library

import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.CollectionItemEntity
import com.nox.offline.data.db.CollectionSummary
import com.nox.offline.data.db.CollectionType
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.subtitles.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Главы и виртуальные серии, нумерация серий, субтитры, рекомендации, очистка. */
class LibraryLogicTest {
    private fun ch(id: Long, start: Long, end: Long = -1, kind: String = ChapterKind.EPISODE, title: String = "c$id") =
        ChapterEntity(id = id, mediaId = 1, title = title, startMs = start, endMs = end, kind = kind, createdAt = 0)

    private val day = 24L * 3600 * 1000

    @Test fun `episodes end at the next episode or the end of file`() {
        val s = Segments.resolve(listOf(ch(2, 1_500_000), ch(1, 0), ch(3, 2_900_000)), 3_600_000)
        assertEquals(listOf(1L, 2L, 3L), s.map { it.id })
        assertEquals(1_500_000L, s[0].endMs)
        assertEquals(3_600_000L, s[2].endMs)
    }

    @Test fun `chapters of another kind do not cut an episode`() {
        val s = Segments.resolve(listOf(ch(1, 0), ch(2, 60_000, kind = ChapterKind.CHAPTER), ch(3, 120_000)), 200_000)
        assertEquals(120_000L, s.first { it.id == 1L }.endMs)
    }

    @Test fun `invalid bounds are rejected, not silently fixed`() {
        assertNotNull(Segments.validate(-1, -1, 1000_000))
        assertNotNull(Segments.validate(5000, 4000, 1000_000))
        assertNotNull(Segments.validate(2_000_000, -1, 1_000_000))
        assertNotNull(Segments.validate(0, 2_000_000, 1_000_000))
        assertNotNull(Segments.validate(0, 500, 1_000_000))
        assertNull(Segments.validate(0, 60_000, 1_000_000))
        // Записи за пределами файла отбрасываются при разборе.
        assertTrue(Segments.resolve(listOf(ch(1, 5_000_000)), 1_000_000).isEmpty())
    }

    @Test fun `multi day marathon keeps exact times`() {
        val s = Segments.resolve(listOf(ch(1, 0), ch(2, day + 3_600_000)), 3 * day)
        assertEquals(day + 3_600_000, s[0].endMs)
        assertEquals(2 * day - 3_600_000, s[1].lengthMs)
        assertEquals("1 д 01:00:00", Segments.clock(day + 3_600_000))
        assertEquals(2 * day + 5_000, Segments.parseClock("2:00:00:05"))
        assertEquals(30L * 3600 * 1000, Segments.parseClock("30:00:00"))
    }

    @Test fun `relative and absolute time map inside the interval`() {
        val seg = Segments.resolve(listOf(ch(1, 600_000), ch(2, 1_800_000)), 3_600_000)[0]
        assertEquals(60_000L, seg.toRelative(660_000))
        assertEquals(0L, seg.toRelative(100))
        assertEquals(seg.lengthMs, seg.toRelative(9_999_999))
        assertEquals(660_000L, seg.toAbsolute(60_000))
    }

    @Test fun `position survives a bounds edit when still inside`() {
        val old = Segments.Segment(1, 1, "a", 600_000, 1_800_000, ChapterKind.EPISODE, "user")
        val new = old.copy(startMs = 540_000)
        assertEquals(120_000L, Segments.reconcile(60_000, old, new))   // то же место в файле: 660 000
        val shorter = old.copy(endMs = 640_000)
        assertEquals(shorter.lengthMs, Segments.reconcile(300_000, old, shorter))
    }

    @Test fun `next episode follows the same kind`() {
        val s = Segments.resolve(listOf(ch(1, 0), ch(2, 60_000, kind = ChapterKind.CHAPTER), ch(3, 120_000)), 300_000)
        assertEquals(3L, Segments.next(s, s.first { it.id == 1L })!!.id)
        assertNull(Segments.next(s, s.first { it.id == 3L }))
    }

    @Test fun `episode numbers are suggested from names`() {
        assertEquals(SeriesNumbering.Guess(2, 5), SeriesNumbering.guess("Demon Slayer S02E05 [1080p]"))
        assertEquals(SeriesNumbering.Guess(2, 5), SeriesNumbering.guess("Истребитель демонов 2 сезон 5 серия"))
        assertEquals(SeriesNumbering.Guess(0, 12), SeriesNumbering.guess("Серия 12 — Финал"))
        assertEquals("Тренировка в деревне", SeriesNumbering.episodeName("Клинки рассвета S02E05 — Тренировка в деревне", "Клинки рассвета"))
        assertEquals("Финал", SeriesNumbering.episodeName("Серия 12 — Финал", "Другой сериал"))
        assertEquals("Возвращение...", SeriesNumbering.episodeName("Сериал: 3 серия — Возвращение...", "сериал"))
        // Ничего, кроме названия сериала и номера, — показываем как есть.
        assertEquals("Клинки рассвета — 2 сезон, 9 серия", SeriesNumbering.episodeName("Клинки рассвета — 2 сезон, 9 серия", "Клинки рассвета"))
        assertEquals(SeriesNumbering.Guess(0, 3), SeriesNumbering.guess("Episode 3"))
        assertEquals(SeriesNumbering.Guess(1, 7), SeriesNumbering.guess("show.1x07.webm"))
        assertNull(SeriesNumbering.guess("Твоё имя (2016)"))
    }

    @Test fun `srt and vtt are parsed with long hours and offsets`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nПривет\r\n\r\n2\r\n30:00:00,000 --> 30:00:02,000\r\n<i>Позже</i>\r\n"
        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(30L * 3600 * 1000, cues[1].startMs)
        assertEquals("Позже", cues[1].text)
        assertEquals("Привет", SubtitleParser.at(cues, 1_500).single().text)
        // +2 с: реплика показывается на 2 секунды позже.
        assertTrue(SubtitleParser.at(cues, 1_500, 2_000).isEmpty())
        assertEquals("Привет", SubtitleParser.at(cues, 3_200, 2_000).single().text)
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000 align:start\nраз\n\n01:00:00.000 --> 01:00:01.000\nдва\n"
        assertEquals("vtt", SubtitleParser.detectFormat(vtt))
        assertEquals(listOf(1_000L, 3_600_000L), SubtitleParser.parse(vtt).map { it.startMs })
    }

    @Test fun `broken subtitle lines are skipped, not crashing`() {
        val bad = "1\n00:00:05,000 --> 00:00:01,000\nназад\n\nмусор\n--> -->\n\n2\n00:00:06,000 --> 00:00:07,000\nнорма\n"
        assertEquals(listOf("норма"), SubtitleParser.parse(bad).map { it.text })
    }

    private fun summary(id: Long, pinned: Boolean = false, local: Int = 2, type: String = CollectionType.ALBUM, updated: Long = 0) =
        CollectionSummary(id, "c$id", "", type, 0, "", pinned, 0, "", "", "", 0, updated, local, local, 0)

    @Test fun `featured comes from the user's own unfinished collection`() {
        val items = listOf(
            CollectionItemEntity(1, collectionId = 1, mediaId = 10, addedAt = 0),
            CollectionItemEntity(2, collectionId = 2, mediaId = 20, addedAt = 0),
        )
        val pb = mapOf(20L to PlaybackEntity(20, 60_000, 600_000, 500))
        val f = LibraryRules.featured(listOf(summary(1, updated = 900), summary(2, updated = 100)), items, pb)
        assertEquals(2L, f!!.collectionId)
        assertNull(LibraryRules.featured(emptyList(), emptyList(), emptyMap()))
        // Пустые категории не рекомендуются.
        assertNull(LibraryRules.featured(listOf(summary(3, local = 0, type = CollectionType.CATEGORY)), emptyList(), emptyMap()))
    }

    private fun media(id: Long, size: Long, protected: Boolean = false) = MediaEntity(id = id, title = "m$id", filePath = "/x",
        sizeBytes = size, quality = "720p", createdAt = 0, protectedFromCleanup = protected)

    @Test fun `cleanup suggests only watched, unprotected, not playing`() {
        val media = listOf(media(1, 100), media(2, 300), media(3, 500, protected = true), media(4, 900))
        val pb = mapOf(
            1L to PlaybackEntity(1, 0, 1, 0, completed = true),
            2L to PlaybackEntity(2, 0, 1, 0, completed = true),
            3L to PlaybackEntity(3, 0, 1, 0, completed = true),
            4L to PlaybackEntity(4, 0, 1, 0, completed = true),
        )
        val c = LibraryRules.cleanup(media, pb, playingMediaId = 4)
        assertEquals(listOf(2L, 1L), c.map { it.media.id })
    }
}
