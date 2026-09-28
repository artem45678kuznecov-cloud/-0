package com.nox.offline.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Проверка файла на настоящих роликах (ffmpeg: H.264+AAC в MP4, VP9+Opus в WebM)
 * и на их испорченных копиях. Оригинал каждого образца сверяется по SHA-256:
 * проверка и пересборка его не меняют.
 */
class ContainerCheckTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val f = tmp.newFile(name)
        javaClass.getResourceAsStream("/media/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun check(f: File, exact: Long = -1): FileCheck = ChannelByteSource.of(f).use { ContainerCheck.check(it, exact) }

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun intactFilesAreReadable() {
        for (n in listOf("h264-faststart.mp4", "h264-moov-end.mp4", "vp9-opus.webm")) {
            val f = fixture(n)
            val before = sha(f)
            val c = check(f)
            assertEquals(n + ": " + c.report(), FileCheck.Verdict.READABLE, c.verdict)
            assertEquals(2, c.tracks.size)
            assertTrue(n, c.durationMs in 2500..3500)
            assertEquals("проверка не меняет файл", before, sha(f))
        }
    }

    @Test
    fun containerIsDetectedByContentNotExtension() {
        val f = fixture("vp9-opus.webm")
        val renamed = File(f.parentFile, "film.mp4")
        f.copyTo(renamed)
        assertEquals("WebM/Matroska", check(renamed).container)
    }

    @Test
    fun mp4WithoutIndexAtEndIsIncomplete() {
        val f = fixture("h264-moov-end.mp4")
        RandomAccessFile(f, "rw").use { it.setLength(it.length() * 6 / 10) }
        val c = check(f)
        assertEquals(c.report(), FileCheck.Verdict.INCOMPLETE, c.verdict)
        assertTrue(c.summary, c.summary.contains("moov"))
    }

    @Test
    fun truncatedFaststartMp4ReportsMissingFrames() {
        val f = fixture("h264-faststart.mp4")
        RandomAccessFile(f, "rw").use { it.setLength(it.length() * 7 / 10) }
        val c = check(f)
        assertEquals(c.report(), FileCheck.Verdict.INCOMPLETE, c.verdict)
        assertTrue(c.problemTimeMs in 1000..3000)
    }

    @Test
    fun brokenNalLengthIsFoundLikeMedia3() {
        val f = fixture("h264-faststart.mp4")
        val offset = ChannelByteSource.of(f).use { s ->
            val insp = Mp4Inspector(CachedReader(s), CheckControl.NONE)
            insp.inspect(-1)
            val video = insp.lastIndex!!.tracks.first { it.isVideo }
            val w = Mp4Inspector.SampleWalk(video, insp.lastIndex!!.dataRegions)
            repeat(30) { w.next() }
            w.offset
        }
        RandomAccessFile(f, "rw").use { it.seek(offset); it.write(ByteArray(4)) }
        val c = check(f)
        assertEquals(c.report(), FileCheck.Verdict.STRUCTURE, c.verdict)
        assertTrue(c.summary, c.summary.contains("Invalid NAL length"))
        assertEquals(offset, c.problemOffset)
    }

    @Test
    fun smallerThanExactSourceSizeIsIncomplete() {
        val f = fixture("h264-faststart.mp4")
        val c = check(f, exact = f.length() + 1000)
        assertEquals(FileCheck.Verdict.INCOMPLETE, c.verdict)
    }

    @Test
    fun truncatedWebmIsIncomplete() {
        val f = fixture("vp9-opus.webm")
        RandomAccessFile(f, "rw").use { it.setLength(it.length() * 8 / 10) }
        val c = check(f)
        assertEquals(c.report(), FileCheck.Verdict.INCOMPLETE, c.verdict)
    }

    @Test
    fun webmSeekHeadPointingElsewhereIsStructure() {
        val f = fixture("vp9-opus.webm")
        val bytes = f.readBytes()
        // SeekPosition (53 AC) первой записи оглавления: сдвигаем на 1 байт — ссылка ведёт мимо элемента.
        val i = (0 until bytes.size - 2).first { bytes[it] == 0x53.toByte() && bytes[it + 1] == 0xAC.toByte() }
        val len = bytes[i + 2].toInt() and 0x7F
        bytes[i + 2 + len] = (bytes[i + 2 + len] + 1).toByte()
        f.writeBytes(bytes)
        val c = check(f)
        assertEquals(c.report(), FileCheck.Verdict.STRUCTURE, c.verdict)
        assertTrue(c.summary, c.summary.contains("Оглавление"))
    }

    @Test
    fun notAVideoIsUnknown() {
        val f = tmp.newFile("x.mp4")
        f.writeText("<html>ошибка 403</html>")
        val c = check(f)
        assertEquals(FileCheck.Verdict.UNKNOWN, c.verdict)
        assertTrue(c.details.first().startsWith("первые байты: 3C 68 74"))
    }

    @Test
    fun rebuildIndexKeepsEveryFrameByteForByte() {
        val f = fixture("h264-moov-end.mp4")
        val before = sha(f)
        val out = tmp.newFile("rebuilt.mp4")
        ChannelByteSource.of(f).use { s ->
            val insp = Mp4Inspector(CachedReader(s), CheckControl.NONE)
            insp.inspect(-1)
            val rb = Mp4Rebuild(s, insp.lastIndex!!)
            out.outputStream().use { rb.write(it, CheckControl.NONE) }
            assertEquals(rb.outputSize(), out.length())
        }
        assertEquals("оригинал не изменился", before, sha(f))
        val c = check(out)
        assertEquals(c.report(), FileCheck.Verdict.READABLE, c.verdict)
        assertTrue(c.tracks.all { it.contains("64-битные") })
        assertSameFrames(f, out)
    }

    /** Каждый кадр каждой дорожки результата совпадает с кадром оригинала байт в байт. */
    private fun assertSameFrames(a: File, b: File) {
        ChannelByteSource.of(a).use { sa ->
            ChannelByteSource.of(b).use { sb ->
                val ia = Mp4Inspector(CachedReader(sa), CheckControl.NONE).also { it.inspect(-1) }.lastIndex!!
                val ib = Mp4Inspector(CachedReader(sb), CheckControl.NONE).also { it.inspect(-1) }.lastIndex!!
                assertEquals(ia.tracks.size, ib.tracks.size)
                for (t in ia.tracks.indices) {
                    val wa = Mp4Inspector.SampleWalk(ia.tracks[t], ia.dataRegions)
                    val wb = Mp4Inspector.SampleWalk(ib.tracks[t], ib.dataRegions)
                    var n = 0
                    while (wa.next()) {
                        assertTrue(wb.next())
                        assertEquals(wa.size, wb.size)
                        val x = ByteArray(wa.size); sa.read(wa.offset, x)
                        val y = ByteArray(wb.size); sb.read(wb.offset, y)
                        assertTrue("кадр $n дорожки $t", x.contentEquals(y))
                        n++
                    }
                    assertEquals(ia.tracks[t].sampleCount, n)
                }
            }
        }
    }
}
