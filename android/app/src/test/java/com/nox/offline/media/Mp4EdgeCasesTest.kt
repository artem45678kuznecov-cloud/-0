@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.media

import androidx.media3.common.ParserException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Random

/**
 * Распознавание и разбор MP4 на необычных и повреждённых файлах — через ту
 * же цепочку, что у плеера (BundledExtractorsAdapter → DefaultExtractorInput →
 * sniff → read). Порог «большого индекса» здесь 1 МиБ, чтобы файлы были
 * маленькими: путь тот же, что у 217 МиБ.
 */
class Mp4EdgeCasesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val limit = 1L shl 20
    private val gen by lazy { SyntheticMarathon(SyntheticMarathon.clipFromResources()) }

    private fun factory(file: File) = MarathonProbe.Factory {
        NoxExtractorsFactory.forSource(ChainSupport.defaultExtractors(), { ChannelByteSource.of(file) }, limit)
    }

    private fun resource(name: String, as_: String = name): File {
        val f = File(tmp.root, as_)
        javaClass.getResourceAsStream("/media/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /** Открыть и прочитать до SeekMap и [samples] сэмплов; вернуть цепочку (закрыть — вызывающему). */
    private fun play(file: File, samples: Long = 50): MarathonProbe.Chain {
        val c = MarathonProbe.Chain(file, factory(file))
        try {
            c.open(0)
            c.read { c.out.seekMap != null && c.out.samples >= samples }
        } catch (t: Throwable) {
            c.close(); throw t
        }
        return c
    }

    private fun allocatedDuring(block: () -> Unit): Long {
        val a = ChainSupport.allocated()
        block()
        return ChainSupport.allocated() - a
    }

    // ------------------------------------------------------------------
    //  3. Большой вложенный бокс
    // ------------------------------------------------------------------

    /** udta на 50 МиБ внутри moov: необязательные метаданные пропускаются без чтения в память. */
    @Test(timeout = 120_000)
    fun hugeOptionalMetadataInsideMoovIsSkipped() {
        val f = File(tmp.root, "udta.mp4")
        gen.write(f, SyntheticMarathon.Options(durationSec = 3600, extraMoovBoxes = listOf(Triple("udta", 50L shl 20, 1))))
        var chain: MarathonProbe.Chain? = null
        val bytes = allocatedDuring { chain = play(f) }
        chain!!.use { c ->
            assertTrue(c.extractor() is LargeMp4Extractor)
            assertNull((c.extractor() as LargeMp4Extractor).fallbackReason)
            assertEquals(3_600_000_000L, c.out.seekMap!!.durationUs)
            assertTrue("на открытие выделено ${bytes shr 20} МиБ", bytes < 16L shl 20)
        }
    }

    /** stsd (обязательное описание кодека) на 2 МиБ — понятная ошибка разбора, без выделения 2 МиБ под бокс. */
    @Test(timeout = 120_000)
    fun oversizedMandatoryStsdIsAClearError() {
        val f = File(tmp.root, "stsd.mp4")
        gen.write(f, SyntheticMarathon.Options(durationSec = 3600, videoStsdPadding = 2L shl 20))
        try {
            play(f).close()
            fail("ожидалась ошибка разбора")
        } catch (e: ParserException) {
            assertTrue(e.message, e.message!!.contains("stsd"))
        }
    }

    // ------------------------------------------------------------------
    //  4. Множество мелких боксов
    // ------------------------------------------------------------------

    @Test(timeout = 120_000)
    fun manySmallBoxesInsideMoovPlayWithSmallMemory() {
        val f = File(tmp.root, "many.mp4")
        gen.write(f, SyntheticMarathon.Options(durationSec = 600, extraMoovBoxes = listOf(Triple("free", 64L, 200_000))))
        var chain: MarathonProbe.Chain? = null
        val bytes = allocatedDuring { chain = play(f) }
        chain!!.use { c ->
            assertTrue(c.extractor() is LargeMp4Extractor)
            assertTrue("на открытие выделено ${bytes shr 20} МиБ", bytes < 16L shl 20)
        }
    }

    /** Больше миллиона боксов в moov — распознавание отказывает (без зависания и без чтения moov в память). */
    @Test(timeout = 120_000)
    fun tooManyBoxesInsideMoovAreRefused() {
        val f = File(tmp.root, "toomany.mp4")
        gen.write(f, SyntheticMarathon.Options(durationSec = 60, extraMoovBoxes = listOf(Triple("free", 8L, 1_100_000))))
        val scan = ChannelByteSource.of(f).use { BoundedMp4Sniffer.scan(it) }
        assertFalse(scan.isMp4)
        assertTrue(scan.failure, scan.failure!!.contains("1000000"))
        val c = MarathonProbe.Chain(f, factory(f))
        try {
            c.open(0)
            fail("ни один разборщик не должен принять файл")
        } catch (e: UnrecognizedInputFormatException) {
            // Обычный Mp4Extractor не просмотрел moov 8,8 МБ: бюджет 1 МиБ.
            assertTrue("peekBuffer ${MarathonProbe.peekBufferBytes(c.input())}",
                MarathonProbe.peekBufferBytes(c.input()) <= GuardedMp4Extractor.sniffBudget(limit) + (576 shl 10))
        } finally {
            c.close()
        }
    }

    // ------------------------------------------------------------------
    //  6. Фрагментированный MP4
    // ------------------------------------------------------------------

    @Test(timeout = 60_000)
    fun fragmentedMp4GoesToFragmentedExtractor() {
        val f = resource("marathon-clip-frag.mp4")
        val scan = ChannelByteSource.of(f).use { BoundedMp4Sniffer.scan(it) }
        assertTrue(scan.isMp4 && scan.fragmented)
        assertNull(NoxExtractorsFactory.largeMp4({ ChannelByteSource.of(f) }, 0))
        // Выбор по sniff. Сами кадры FragmentedMp4Extractor на JVM не читает (android.util.Pair в заглушке
        // android.jar — null) — это проверяется на устройстве.
        MarathonProbe.Chain(f, factory(f)).use { c ->
            c.open(0)
            assertTrue(c.extractor() is GuardedMp4Extractor)
            assertTrue(c.extractor().underlyingImplementation is FragmentedMp4Extractor)
            assertEquals(65536, MarathonProbe.peekBufferBytes(c.input()))
        }
    }

    // ------------------------------------------------------------------
    //  7. Не MP4, хотя расширение .mp4
    // ------------------------------------------------------------------

    @Test(timeout = 60_000)
    fun webmNamedMp4IsReadByMatroska() {
        val f = resource("vp9-opus.webm", "really-webm.mp4")
        assertNull(NoxExtractorsFactory.largeMp4({ ChannelByteSource.of(f) }, 0))
        // Выбор по sniff (кадры MatroskaExtractor на JVM не читает: SparseArray в заглушке android.jar).
        MarathonProbe.Chain(f, factory(f)).use { c ->
            c.open(0)
            assertTrue(c.extractor().underlyingImplementation is MatroskaExtractor)
        }
    }

    @Test(timeout = 60_000)
    fun noiseNamedMp4IsRefusedWithSmallPeek() {
        val f = File(tmp.root, "noise.mp4")
        f.writeBytes(ByteArray(1 shl 20).also { Random(1).nextBytes(it) })
        assertNull(NoxExtractorsFactory.largeMp4({ ChannelByteSource.of(f) }, 0))
        val c = MarathonProbe.Chain(f, factory(f))
        try {
            c.open(0)
            fail("шум не должен распознаться")
        } catch (e: UnrecognizedInputFormatException) {
            assertTrue(MarathonProbe.peekBufferBytes(c.input()) <= 1 shl 20)
        } finally {
            c.close()
        }
    }

    @Test(timeout = 60_000)
    fun unsupportedBrandIsNotMp4() {
        val f = File(tmp.root, "heic.mp4")
        f.writeBytes(box("ftyp", bytes("heic", 0, "mif1", "heic")) + box("moov", box("mvhd", ByteArray(100))) + box("mdat", ByteArray(64)))
        val scan = ChannelByteSource.of(f).use { BoundedMp4Sniffer.scan(it) }
        assertFalse(scan.isMp4)
    }

    // ------------------------------------------------------------------
    //  8. Обрезанные и повреждённые заголовки
    // ------------------------------------------------------------------

    private fun ftypIsom() = box("ftyp", bytes("isom", 512, "isom", "mp41"))

    /** Экономный разбор напрямую (предел обычного пути 0: никакого ухода в Mp4Extractor). */
    private fun parseDirect(f: File): Throwable? {
        val c = MarathonProbe.Chain(f, MarathonProbe.Factory { arrayOf<Extractor>(LargeMp4Extractor(0) { ChannelByteSource.of(f) }) })
        return try {
            c.open(0)
            c.read { c.out.seekMap != null }
            null
        } catch (t: Throwable) {
            t
        } finally {
            c.close()
        }
    }

    @Test(timeout = 60_000)
    fun truncatedMoovIsRefusedAndAClearError() {
        val full = File(tmp.root, "full.mp4")
        val l = gen.write(full, SyntheticMarathon.Options(durationSec = 3600))
        val f = File(tmp.root, "truncated.mp4")
        f.writeBytes(full.readBytes().copyOf((l.moovPos + l.moovSize / 2).toInt()))
        val scan = ChannelByteSource.of(f).use { BoundedMp4Sniffer.scan(it) }
        assertFalse(scan.isMp4)
        assertTrue(scan.failure, scan.failure!!.contains("moov обрезан"))
        val e = parseDirect(f)
        assertTrue("$e", e is ParserException && e.message!!.contains("обрезан"))
        // Через цепочку плеера: экономный путь не выбран, обычный — с бюджетом; памяти под весь moov не берётся.
        val c = MarathonProbe.Chain(f, factory(f))
        try {
            c.open(0)
            c.read { c.out.seekMap != null }
            fail("обрезанный индекс не должен открыться")
        } catch (e: Exception) {
            assertTrue("peekBuffer ${MarathonProbe.peekBufferBytes(c.input())}",
                MarathonProbe.peekBufferBytes(c.input()) <= GuardedMp4Extractor.sniffBudget(limit) + (576 shl 10))
        } finally {
            c.close()
        }
    }

    @Test(timeout = 60_000)
    fun boxSmallerThanHeaderIsRefused() {
        val f = File(tmp.root, "small.mp4")
        f.writeBytes(ftypIsom() + int32(4) + "moov".toByteArray() + ByteArray(64))
        assertFalse(ChannelByteSource.of(f).use { BoundedMp4Sniffer.scan(it) }.isMp4)
        assertTrue(parseDirect(f) is ParserException)
    }

    @Test(timeout = 60_000)
    fun sixtyFourBitSizeWithTopBitIsRefused() {
        val f = File(tmp.root, "top.mp4")
        f.writeBytes(ftypIsom() + int32(1) + "moov".toByteArray() + int64(Long.MIN_VALUE + 5) + ByteArray(64))
        val scan = ChannelByteSource.of(f).use { BoundedMp4Sniffer.scan(it) }
        assertFalse(scan.isMp4)
        assertTrue(parseDirect(f) is ParserException)
    }

    @Test(timeout = 60_000)
    fun childLargerThanMoovIsRefused() {
        val f = File(tmp.root, "child.mp4")
        val trak = int32(1_000_000) + "trak".toByteArray() + ByteArray(32)
        f.writeBytes(ftypIsom() + box("moov", box("mvhd", ByteArray(100)) + trak) + box("mdat", ByteArray(64)))
        val scan = ChannelByteSource.of(f).use { BoundedMp4Sniffer.scan(it) }
        assertFalse(scan.isMp4)
        val e = parseDirect(f)
        assertTrue("$e", e is ParserException && e.message!!.contains("выходит за границы"))
    }

    @Test(timeout = 60_000)
    fun tooDeepNestingIsAClearError() {
        var inner = box("free", ByteArray(8))
        repeat(20) { inner = box("minf", inner) }
        val f = File(tmp.root, "deep.mp4")
        f.writeBytes(ftypIsom() + box("moov", box("mvhd", ByteArray(100)) + box("trak", inner)) + box("mdat", ByteArray(64)))
        val e = parseDirect(f)
        assertTrue("$e", e is ParserException && e.message!!.contains("вложенность"))
    }

    /** Таблица заявляет 2^31 записей в боксе на 20 байт: отказ до выделения памяти под записи. */
    @Test(timeout = 60_000)
    fun tableCountBeyondBoxIsAClearError() {
        val good = File(tmp.root, "good.mp4")
        val l = gen.write(good, SyntheticMarathon.Options(durationSec = 60, moovFirst = true))
        val bytes = good.readBytes()
        // Число сэмплов в stsz видео → 0x7FFFFFF0 (поле — через 8 байт после начала данных stsz).
        val stsz = indexOf(bytes, "stsz".toByteArray(), l.moovPos.toInt())
        writeInt(bytes, stsz + 4 + 8, 0x7FFFFFF0)
        val f = File(tmp.root, "count.mp4").apply { writeBytes(bytes) }
        var e: Throwable? = null
        val alloc = allocatedDuring { e = parseDirect(f) }
        assertTrue("$e", e is ParserException && e!!.message!!.contains("stsz"))
        assertTrue("выделено ${alloc shr 20} МиБ", alloc < 8L shl 20)
    }

    // ------------------------------------------------------------------
    //  9. Короткий MP4 — прежний путь Media3
    // ------------------------------------------------------------------

    @Test(timeout = 60_000)
    fun shortMp4UsesMedia3AsBefore() {
        val f = resource("h264-faststart.mp4")
        assertNull(NoxExtractorsFactory.largeMp4({ ChannelByteSource.of(f) }))
        play(f, samples = 100).use { c ->
            assertTrue(c.extractor() is GuardedMp4Extractor)
            assertTrue(c.extractor().underlyingImplementation is Mp4Extractor)
        }
        // Те же сэмплы, что у Mp4Extractor без обёртки.
        val plain = ExtractorHarness(f, Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)).apply { read() }
        val nox = ExtractorHarness(f, NoxExtractorsFactory.guarded(arrayOf(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)))[0]).apply { read() }
        assertEquals(plain.tracks.keys, nox.tracks.keys)
        for (id in plain.tracks.keys) assertEquals(plain.tracks[id]!!.digest, nox.tracks[id]!!.digest)
        plain.close(); nox.close()
    }

    @Test
    fun onlyStandardMp4ExtractorsAreGuardedAndOrderKept() {
        val base = ChainSupport.defaultExtractors()
        val g = NoxExtractorsFactory.guarded(base)
        assertEquals(base.size, g.size)
        for (i in base.indices) {
            val std = base[i] is Mp4Extractor || base[i] is FragmentedMp4Extractor
            assertEquals("позиция $i", std, g[i] is GuardedMp4Extractor)
            assertTrue(g[i].underlyingImplementation === base[i].underlyingImplementation)
        }
    }

    // ------------------------------------------------------------------
    //  10. Документ со смещением (как AssetFileDescriptor SAF)
    // ------------------------------------------------------------------

    @Test(timeout = 120_000)
    fun documentAtOffsetInsideDescriptorPlays() {
        val movie = File(tmp.root, "movie.mp4")
        gen.write(movie, SyntheticMarathon.Options(durationSec = 3600))
        val base = 12_345L
        val container = File(tmp.root, "container.bin")
        container.outputStream().use { o -> o.write(ByteArray(base.toInt()) { 7 }); movie.inputStream().use { it.copyTo(o) }; o.write(ByteArray(999) { 9 }) }
        val len = movie.length()
        run {
            // Каждое открытие — свой дескриптор (как openTypedAssetFileDescriptor), документ — с base на len байт.
            val open = { ChannelByteSource.of(FileInputStream(container).fd, base, len) }
            val chain = MarathonProbe.Chain(container, MarathonProbe.Factory {
                NoxExtractorsFactory.forSource(ChainSupport.defaultExtractors(), open, limit)
            }, base, len)
            chain.use { c ->
                c.open(0)
                c.read { c.out.seekMap != null && c.out.samples >= 50 }
                assertTrue(c.extractor() is LargeMp4Extractor)
                c.seek(c.out.seekMap!!.durationUs / 2)
                val before = c.out.samples
                c.read { c.out.samples >= before + 50 }
            }
            // Источник со сдвигом на байт читает другие данные: честная ошибка, не чтение чужих смещений.
            val wrong = MarathonProbe.Chain(container, MarathonProbe.Factory {
                arrayOf<Extractor>(LargeMp4Extractor(limit) { ChannelByteSource.of(FileInputStream(container).fd, base + 1, len - 1) },
                    Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0))
            }, base, len)
            try {
                wrong.open(0)
                fail("ожидалась ошибка источника")
            } catch (e: IOException) {
                assertTrue(e.message, e.message!!.contains("иначе"))
            } finally {
                wrong.close()
            }
        }
    }

    // ------------------------------------------------------------------
    //  Байты
    // ------------------------------------------------------------------

    private fun int32(v: Int) = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(v) }.toByteArray()
    private fun int64(v: Long) = ByteArrayOutputStream().also { DataOutputStream(it).writeLong(v) }.toByteArray()
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return int32(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }
    private fun bytes(major: String, minor: Int, vararg compatible: String) =
        major.toByteArray() + int32(minor) + compatible.fold(ByteArray(0)) { a, b -> a + b.toByteArray() }
    private fun indexOf(a: ByteArray, what: ByteArray, from: Int): Int {
        outer@ for (i in from..a.size - what.size) {
            for (j in what.indices) if (a[i + j] != what[j]) continue@outer
            return i
        }
        return -1
    }
    private fun writeInt(a: ByteArray, at: Int, v: Int) { for (k in 0 until 4) a[at + k] = (v ushr (24 - 8 * k)).toByte() }
}
