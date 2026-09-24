package com.nox.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreTest {
    @Test
    fun `file names are safe and keep the NOX pattern`() {
        assertEquals("Повар-боец Сома [vid123].mp4", FileNames.targetName("Повар-боец Сома", "vid123", "mp4"))
        assertEquals("a_b_c_d [x].mp4", FileNames.targetName("a/b\\c:d", "x", "mp4"))
        assertEquals("video.mp4", FileNames.targetName("   ", "", "mp4"))
        assertEquals("t.mp4", FileNames.targetName("t", "", "..mp4"))
        assertEquals(120, FileNames.sanitize("x".repeat(500)).length)
    }

    @Test
    fun `unique adds a counter instead of overwriting`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "nox-names-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "v [1].mp4").writeText("a")
            assertEquals("v [1] (2).mp4", FileNames.unique(dir, "v [1].mp4").name)
            File(dir, "v [1] (2).mp4").writeText("b")
            assertEquals("v [1] (3).mp4", FileNames.unique(dir, "v [1].mp4").name)
            assertEquals("new.mp4", FileNames.unique(dir, "new.mp4").name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `redacted urls never carry the query string`() {
        val signed = "https://vkvd123.okcdn.ru/video.mp4?expires=1&sig=SECRET&ct=0"
        val safe = SafeUrl.redact(signed)
        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains("expires"))
        assertTrue(safe.startsWith("https://vkvd123.okcdn.ru/video.mp4"))
        assertEquals("vkvd123.okcdn.ru", SafeUrl.host(signed))
        assertEquals("-", SafeUrl.redact(""))
    }

    @Test
    fun `url detection and extraction`() {
        assertTrue(SafeUrl.looksLikeUrl("https://m.vkvideo.ru/video-123_456"))
        assertFalse(SafeUrl.looksLikeUrl("vkvideo.ru/video"))
        assertFalse(SafeUrl.looksLikeUrl("https://a b"))
        assertEquals("https://m.vkvideo.ru/video-1_2", SafeUrl.extract("смотри https://m.vkvideo.ru/video-1_2."))
        assertNull(SafeUrl.extract("без ссылки"))
    }

    @Test
    fun `formatting matches NOX style`() {
        assertEquals("1.4 ГБ", Format.bytes(1_503_238_553L))
        assertEquals("3.8 МБ/с", Format.speed(3_984_589L))
        assertEquals("28 мин", Format.eta(28 * 60L))
        assertEquals("1 ч 5 мин", Format.eta(65 * 60L))
        assertEquals("меньше минуты", Format.eta(30))
        assertEquals("—", Format.eta(-1))
        assertEquals("1:02:03", Format.duration(3723))
        assertEquals("2:05", Format.duration(125))
        assertEquals(50, Format.percent(50, 100))
        assertEquals(0, Format.percent(50, 0))
    }

    @Test
    fun `log keeps bounded history and formats fields`() {
        NoxLog.clear()
        NoxLog.event("transfer-start", "id" to 7, "host" to "cdn", "none" to null)
        val line = NoxLog.dump().last()
        assertTrue(line.contains("transfer-start id=7 host=cdn none=-"))
        repeat(1000) { NoxLog.event("x") }
        assertTrue(NoxLog.dump().size <= 600)
    }
}
