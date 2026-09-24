package com.nox.offline.downloader

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePolicyTest {
    @Test
    fun `three run and the fourth waits`() {
        assertEquals(3, DownloadCoordinator.MAX_CONCURRENT)
        val queued = listOf(1L, 2L, 3L, 4L)
        assertEquals(listOf(1L, 2L, 3L), QueuePolicy.pick(queued, emptySet()))
        // Три уже идут — четвёртая ждёт.
        assertEquals(emptyList<Long>(), QueuePolicy.pick(listOf(4L), setOf(1L, 2L, 3L)))
        // Одна закончилась — четвёртая занимает слот.
        assertEquals(listOf(4L), QueuePolicy.pick(listOf(4L), setOf(2L, 3L)))
    }

    @Test
    fun `order of adding is kept and blocked ids are skipped`() {
        assertEquals(listOf(5L, 7L), QueuePolicy.pick(listOf(5L, 6L, 7L), setOf(9L), blocked = setOf(6L), max = 3))
        assertEquals(listOf(6L), QueuePolicy.pick(listOf(5L, 6L), setOf(5L), max = 3))
    }

    @Test
    fun `never exceeds the maximum`() {
        val many = (1L..20L).toList()
        assertEquals(3, QueuePolicy.pick(many, emptySet()).size)
        assertEquals(1, QueuePolicy.pick(many, setOf(100L, 101L)).size)
        assertEquals(0, QueuePolicy.pick(many, setOf(100L, 101L, 102L, 103L)).size)
    }
}
