package com.nox.offline.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Сколько байт читает проверка настоящего 4K-файла: она не должна читать фильм
 * целиком. Файл берётся из NOX_PERF_FILE (в CI не задан — тест пропускается).
 */
class CheckCostTest {
    private class Counting(private val s: ByteSource) : ByteSource by s {
        var bytes = 0L
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = s.read(pos, buf, off, len).also { bytes += it }
    }

    @Test
    fun checkReadsOnlyASmallPartOfA4kFile() {
        val path = System.getenv("NOX_PERF_FILE")
        assumeTrue(path != null && File(path).exists())
        val f = File(path!!)
        ChannelByteSource.of(f).use { raw ->
            val c = Counting(raw)
            val t0 = System.nanoTime()
            val r = ContainerCheck.check(c)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("проверка: ${r.verdict} ${r.container}, прочитано ${c.bytes} из ${f.length()} байт (${c.bytes * 100 / f.length()} %), $ms мс")
            assertEquals(r.report(), FileCheck.Verdict.READABLE, r.verdict)
            assertTrue("прочитано ${c.bytes} из ${f.length()}", c.bytes < f.length() / 20)
        }
    }
}
