package com.nox.offline.player

import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.CollectionItemEntity
import com.nox.offline.library.Segments
import com.nox.offline.settings.AutoNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Очередь автоперехода, таймер сна и их приоритет. */
class PlaybackLogicTest {
    private fun item(id: Long, media: Long, season: Int, pos: Long, chapter: Long = 0) =
        CollectionItemEntity(id = id, collectionId = 1, mediaId = media, chapterId = chapter, season = season, position = pos, addedAt = 0)

    @Test fun `series queue follows the chosen season and skips not downloaded`() {
        val items = listOf(
            item(1, 10, 1, 1), item(2, 11, 1, 2), item(3, 0, 1, 3).copy(sourceKey = "youtube:x"),
            item(4, 12, 1, 4), item(5, 20, 2, 1),
        )
        val ctx = PlayContext.ofCollection(1, "Сериал", items, Playable(11), series = true)!!
        assertEquals(listOf(10L, 11L, 12L), ctx.items.map { it.mediaId })
        assertEquals(Playable(12), ctx.next)
        assertEquals(Playable(10), ctx.previous)
        // Не сериал — вся коллекция по порядку.
        val album = PlayContext.ofCollection(1, "Альбом", items, Playable(12), series = false)!!
        assertEquals(Playable(20), album.next)
    }

    @Test fun `virtual episodes of one file form a marathon queue`() {
        val segs = listOf(
            Segments.Segment(1, 5, "С1", 0, 1_440_000, ChapterKind.EPISODE, "user"),
            Segments.Segment(9, 5, "Опенинг", 0, 90_000, ChapterKind.CHAPTER, "user"),
            Segments.Segment(2, 5, "С2", 1_440_000, 2_880_000, ChapterKind.EPISODE, "user"),
        )
        val ctx = PlayContext.ofSegments("Марафон", 5, segs, Playable(5, 1))!!
        assertEquals(Playable(5, 2), ctx.next)
        assertNull(ctx.moveTo(Playable(5, 2)).next)
    }

    @Test fun `timer after episode beats autonext`() {
        val ctx = PlayContext("x", listOf(Playable(1), Playable(2)), 0)
        assertEquals(EndRule.Action.SleepStop, EndRule.decide(ctx, Playable(1), SleepTimer.Mode.AfterSegment, AutoNext.AUTO))
        assertEquals(EndRule.Action.SleepStop, EndRule.decide(ctx, Playable(1), SleepTimer.Mode.AfterFile, AutoNext.AUTO))
        assertEquals(EndRule.Action.Play(Playable(2)), EndRule.decide(ctx, Playable(1), null, AutoNext.AUTO))
        assertEquals(EndRule.Action.Offer(Playable(2)), EndRule.decide(ctx, Playable(1), null, AutoNext.ASK))
        assertEquals(EndRule.Action.Nothing, EndRule.decide(ctx, Playable(1), null, AutoNext.OFF))
    }

    @Test fun `after file lets episodes of the same file continue`() {
        val ctx = PlayContext("x", listOf(Playable(1, 1), Playable(1, 2), Playable(2)), 0)
        assertEquals(EndRule.Action.Offer(Playable(1, 2)), EndRule.decide(ctx, Playable(1, 1), SleepTimer.Mode.AfterFile, AutoNext.ASK))
        assertEquals(EndRule.Action.SleepStop,
            EndRule.decide(ctx.moveTo(Playable(1, 2)), Playable(1, 2), SleepTimer.Mode.AfterFile, AutoNext.ASK))
    }

    @Test fun `no next means no action and no network`() {
        assertEquals(EndRule.Action.Nothing, EndRule.decide(null, Playable(1), null, AutoNext.AUTO))
        val ctx = PlayContext("x", listOf(Playable(1)), 0)
        assertEquals(EndRule.Action.Nothing, EndRule.decide(ctx, Playable(1), null, AutoNext.AUTO))
    }

    @Test fun `fade uses only player volume and reaches zero at the end`() {
        assertEquals(1f, SleepTimer.volume(SleepTimer.FADE_MS + 1), 0f)
        assertEquals(0f, SleepTimer.volume(0), 0f)
        val mid = SleepTimer.volume(SleepTimer.FADE_MS / 2)
        assertTrue(mid in 0.1f..0.5f)
        assertEquals("1 ч", SleepTimer.Mode.At(0, 60).label)
        assertEquals("1 ч 30 мин", SleepTimer.Mode.At(0, 90).label)
        assertEquals(5_000L, SleepTimer.remaining(SleepTimer.Mode.At(10_000, 1), 5_000))
        assertNull(SleepTimer.remaining(SleepTimer.Mode.AfterFile, 0))
    }
}
