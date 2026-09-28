package com.nox.offline.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * MP4 больше 4 ГБ с 32-битными смещениями кадров (stco) — так пишет MP4-писатель
 * без 64-битных смещений. Файл разреженный: настоящие кадры маленького ролика
 * лежат поперёк границы 4 ГБ, остальное — дыра, которая не занимает диск.
 */
class LargeMp4Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val four = 1L shl 32

    /**
     * [wrapMdatSize] — размер mdat записан 32-битным полем (обернулся), иначе 64-битный.
     * Возвращает файл и смещение, с которого начинаются кадры.
     */
    private fun bigFile(wrapMdatSize: Boolean): Pair<File, Long> {
        val small = tmp.newFile("small.mp4")
        javaClass.getResourceAsStream("/media/h264-moov-end.mp4")!!.use { i -> small.outputStream().use { i.copyTo(it) } }
        val src = ChannelByteSource.of(small)
        val insp = Mp4Inspector(CachedReader(src), CheckControl.NONE)
        insp.inspect(-1)
        val idx = insp.lastIndex!!
        val ftyp = idx.top.first { it.type == "ftyp" }
        val region = idx.dataRegions.single()
        val payload = ByteArray((region.last + 1 - region.first).toInt()).also { src.read(region.first, it) }
        val ftypBytes = ByteArray(ftyp.size.toInt()).also { src.read(ftyp.pos, it) }
        src.close()

        val x = four - payload.size / 2              // кадры поперёк границы 4 ГБ
        val moov = idx.moovBytes.copyOf()
        val m = Bytes(moov)
        for (t in idx.tracks) {
            val box = t.chunkBoxOffsetInMoov
            val count = m.u32(box + 12).toInt()
            for (i in 0 until count) {
                val old = m.u32(box + 16 + 4 * i)
                val v = (old - region.first + x) and 0xFFFFFFFFL
                for (k in 0 until 4) moov[box + 16 + 4 * i + k] = (v shr (24 - 8 * k)).toByte()
            }
        }
        val out = tmp.newFile(if (wrapMdatSize) "big-wrapped-mdat.mp4" else "big.mp4")
        RandomAccessFile(out, "rw").use { f ->
            f.write(ftypBytes)
            val mdatPos = ftypBytes.size.toLong()
            if (wrapMdatSize) {
                f.writeInt(((x + payload.size - mdatPos) and 0xFFFFFFFFL).toInt()); f.writeBytes("mdat")
            } else {
                f.writeInt(1); f.writeBytes("mdat"); f.writeLong(x + payload.size - mdatPos)
            }
            f.seek(x); f.write(payload)
            f.write(moov)
        }
        return out to x
    }

    @Test
    fun wrappedChunkOffsetsAreFoundAndRebuildIsOffered() {
        val (f, _) = bigFile(wrapMdatSize = false)
        assertTrue(f.length() > four)
        val c = ChannelByteSource.of(f).use { ContainerCheck.check(it) }
        assertEquals(c.report(), FileCheck.Verdict.STRUCTURE, c.verdict)
        assertEquals(FileCheck.Repair.MP4_REBUILD_INDEX, c.repair)
        assertTrue(c.report(), c.details.any { it.contains("32-битными") })
    }

    @Test
    fun wrappedMdatSizeStillFindsIndexAndOffersRebuild() {
        val (f, _) = bigFile(wrapMdatSize = true)
        val c = ChannelByteSource.of(f).use { ContainerCheck.check(it) }
        assertEquals(c.report(), FileCheck.Verdict.STRUCTURE, c.verdict)
        assertEquals(FileCheck.Repair.MP4_REBUILD_INDEX, c.repair)
        assertTrue(c.report(), c.details.any { it.contains("moov найден в конце") })
    }

    /** Полная пересборка 4+ ГБ — только по запросу: NOX_LARGE=1. */
    @Test
    fun rebuildOfFileOver4GbPlaysFramesFromTheRightPlaces() {
        assumeTrue(System.getenv("NOX_LARGE") == "1")
        val (f, x) = bigFile(wrapMdatSize = true)
        val out = File(tmp.root, "rebuilt.mp4")
        ChannelByteSource.of(f).use { s ->
            val insp = Mp4Inspector(CachedReader(s), CheckControl.NONE)
            insp.inspect(-1)
            out.outputStream().buffered(1 shl 20).use { Mp4Rebuild(s, insp.lastIndex!!).write(it, CheckControl.NONE) }
        }
        val c = ChannelByteSource.of(out).use { ContainerCheck.check(it) }
        assertEquals(c.report(), FileCheck.Verdict.READABLE, c.verdict)
        assertTrue("размер ${out.length()}", out.length() > four)
        // Каждый кадр результата — те же байты, что в оригинале по настоящему (развёрнутому) смещению.
        ChannelByteSource.of(f).use { so ->
            ChannelByteSource.of(out).use { sr ->
                val io = Mp4Inspector(CachedReader(so), CheckControl.NONE).also { it.inspect(-1) }.lastIndex!!
                val ir = Mp4Inspector(CachedReader(sr), CheckControl.NONE).also { it.inspect(-1) }.lastIndex!!
                var frames = 0
                var crossed = false
                for (t in io.tracks.indices) {
                    val wo = Mp4Inspector.SampleWalk(io.tracks[t], io.dataRegions)
                    val wr = Mp4Inspector.SampleWalk(ir.tracks[t], ir.dataRegions)
                    while (wo.next()) {
                        assertTrue(wr.next())
                        if (wo.offset >= four) crossed = true
                        val a = ByteArray(wo.size); so.read(wo.offset, a)
                        val b = ByteArray(wr.size); sr.read(wr.offset, b)
                        assertTrue("кадр $frames дорожки $t (оригинал ${wo.offset})", a.contentEquals(b))
                        frames++
                    }
                }
                assertTrue("кадры должны лежать и за границей 4 ГБ", crossed)
                assertTrue(frames > 100)
                assertTrue(x > 0)
            }
        }
    }
}
