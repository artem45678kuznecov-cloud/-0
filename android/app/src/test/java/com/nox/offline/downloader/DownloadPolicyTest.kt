package com.nox.offline.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.0: общий предел скорости, место на пике, правило «Только Wi-Fi», слоты 1/2/3. */
class DownloadPolicyTest {
    @Test fun `no limit means no waiting at all`() {
        val l = SpeedLimiter { 0 }
        repeat(1000) { assertEquals(0L, l.reserve(256 * 1024, 1000L + it)) }
    }

    @Test fun `limit is shared by all transfers`() {
        var rate = 1024L * 1024     // 1 МБ/с на всех
        val l = SpeedLimiter { rate }
        val now = 10_000L
        // Три дорожки одновременно принесли по 1 МБ: вместе это 3 секунды при 1 МБ/с.
        val waits = (0 until 3).map { l.reserve(1024 * 1024, now) }
        assertTrue(waits[0] in 0..1000)
        assertTrue(waits[2] in 2_500..3_000)
        // Предел сменили на «без ограничения» — ожидания пропадают сразу.
        rate = 0
        assertEquals(0L, l.reserve(1024 * 1024, now))
    }

    @Test fun `average rate converges to the limit`() {
        val rate = 512L * 1024
        val l = SpeedLimiter { rate }
        var t = 0L
        var bytes = 0L
        repeat(200) {
            t += l.reserve(64 * 1024, t)
            bytes += 64 * 1024
        }
        val actual = bytes * 1000 / t
        assertTrue("rate $actual", actual in (rate * 95 / 100)..(rate * 110 / 100))
    }

    @Test fun `peak space counts parts, merge, queue and destination`() {
        val gb = 1024L * 1024 * 1024
        val n = SpaceEstimate.need(sizeBytes = 2 * gb, approx = false, needsMerge = true, alreadyOnDisk = 0,
            queuedRemaining = gb, externalTarget = true)
        assertEquals(2 * gb + 2 * gb + gb + SpaceEstimate.MARGIN, n.internal)
        assertEquals(2 * gb + SpaceEstimate.MARGIN, n.external)
        assertFalse(n.uncertain)
        val fmt: (Long) -> String = { "${it / gb} ГБ" }
        assertFalse(SpaceEstimate.check(n, freeInternal = 4 * gb, freeExternal = 10 * gb, fmt).ok)
        assertFalse(SpaceEstimate.check(n, freeInternal = 10 * gb, freeExternal = gb, fmt).ok)
        assertTrue(SpaceEstimate.check(n, freeInternal = 10 * gb, freeExternal = 10 * gb, fmt).ok)
        // Внутреннее хранилище: папки назначения отдельно нет.
        assertEquals(0L, SpaceEstimate.need(gb, false, false, 0, 0, false).external)
    }

    @Test fun `approximate and unknown sizes are marked uncertain`() {
        val approx = SpaceEstimate.need(1000_000_000, approx = true, needsMerge = false, alreadyOnDisk = 0, queuedRemaining = 0,
            externalTarget = false)
        assertTrue(approx.uncertain)
        assertEquals(1_100_000_000 + SpaceEstimate.MARGIN, approx.internal)
        assertTrue(SpaceEstimate.need(0, false, true, 0, 0, false).uncertain)
    }

    @Test fun `wifi only waits on mobile data`() {
        assertEquals(NetworkGate.State.OK, NetworkGate.decide(wifiOnly = false, wifi = false))
        assertEquals(NetworkGate.State.OK, NetworkGate.decide(wifiOnly = true, wifi = true))
        assertEquals(NetworkGate.State.METERED, NetworkGate.decide(wifiOnly = true, wifi = false))
    }

    @Test fun `concurrency 1 2 3 limits new starts but never stops running ones`() {
        val queued = listOf(1L, 2L, 3L, 4L)
        assertEquals(listOf(1L), QueuePolicy.pick(queued, emptySet(), max = 1))
        assertEquals(listOf(1L, 2L), QueuePolicy.pick(queued, emptySet(), max = 2))
        assertEquals(listOf(1L, 2L, 3L), QueuePolicy.pick(queued, emptySet(), max = 3))
        // Было 3 активных, лимит уменьшили до 1: новых не стартует, идущие не трогаются.
        assertEquals(emptyList<Long>(), QueuePolicy.pick(listOf(4L), setOf(1L, 2L, 3L), max = 1))
    }
}
