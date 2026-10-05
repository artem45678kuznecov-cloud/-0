@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.system.exitProcess

/**
 * Отдельный процесс JVM для замеров на длинном MP4 — с заданным пределом кучи
 * (-Xmx256m, как у приложения на телефоне пользователя). Цепочка та же, что у
 * ProgressiveMediaPeriod: BundledExtractorsAdapter → настоящий
 * DefaultExtractorInput → выбор экстрактора по sniff → read() с переоткрытием
 * источника по RESULT_SEEK → SeekMap → seek().
 *
 * Итоги печатаются строками `R ключ=значение` — их разбирает [MarathonMp4Test].
 */
object MarathonProbe {
    private fun r(key: String, value: Any?) = println("R $key=$value")

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args[0]) {
                "old" -> old(File(args[1]))
                "new" -> new(File(args[1]), args.drop(2).associate { it.substringBefore('=') to it.substringAfter('=') })
                "media3" -> media3Reference(File(args[1]), args[2].toInt())
                else -> error("неизвестный сценарий ${args[0]}")
            }
            r("done", true)
            System.out.flush()
            exitProcess(0)
        } catch (t: Throwable) {
            r("fatal", "${t.javaClass.name}: ${t.message}")
            t.printStackTrace(System.out)
            System.out.flush()
            exitProcess(3)
        }
    }

    // ------------------------------------------------------------------
    //  Память
    // ------------------------------------------------------------------

    private fun resetPeak() = ChainSupport.resetPeak()
    /** Сумма пиков пулов кучи (верхняя оценка: вместе с ещё не собранным мусором). */
    private fun peak() = ChainSupport.peak()
    private fun retained(): Long {
        repeat(3) { System.gc(); Thread.sleep(40) }
        return ChainSupport.used()
    }
    private fun allocated() = ChainSupport.allocated()
    private fun mib(b: Long) = String.format(java.util.Locale.US, "%.1f", b / 1_048_576.0)
    /** Открытые дескрипторы этого процесса на [file] (по /proc/self/fd). */
    private fun fileFds(file: File): Int {
        val target = file.canonicalPath
        return File("/proc/self/fd").listFiles()?.count { fd ->
            runCatching { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString() == target }.getOrDefault(false)
        } ?: -1
    }

    // ------------------------------------------------------------------
    //  Вход и выход экстрактора
    // ------------------------------------------------------------------

    /** Источник для DefaultExtractorInput: чтение файла с [position] подряд, как DataSource. */
    internal class Reader(private val raf: RandomAccessFile, var pos: Long, private val end: Long, private val base: Long = 0) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (pos >= end) return C.RESULT_END_OF_INPUT
            raf.seek(base + pos)
            val n = raf.read(buffer, offset, minOf(length.toLong(), end - pos).toInt())
            if (n < 0) return C.RESULT_END_OF_INPUT
            pos += n
            return n
        }
    }

    class Sample(val track: Int, val timeUs: Long, val flags: Int, val size: Int, val crc: Long)

    internal class Out : ExtractorOutput {
        val tracks = sortedMapOf<Int, T>()
        var seekMap: SeekMap? = null
        var ended = false
        var onSample: ((Sample) -> Unit)? = null
        var samples = 0L
        var maxTimeUs = 0L

        inner class T(val id: Int) : TrackOutput {
            var format: Format? = null
            private val crc = CRC32()
            private val scratch = ByteArray(64 * 1024)
            override fun format(format: Format) { this.format = format }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val n = input.read(scratch, 0, minOf(length, scratch.size))
                if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return n; throw EOFException() }
                crc.update(scratch, 0, n)
                return n
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                crc.update(data.data, data.position, length)
                data.skipBytes(length)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                samples++
                maxTimeUs = maxOf(maxTimeUs, timeUs)
                onSample?.invoke(Sample(id, timeUs, flags, size, crc.value))
                crc.reset()
            }
        }

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { T(id) }
        override fun endTracks() { ended = true }
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
    }

    /** ExtractorsFactory для BundledExtractorsAdapter: адрес на JVM не нужен (android.net.Uri здесь не создать). */
    @Suppress("FunctionName")
    internal fun Factory(make: () -> Array<Extractor>): ExtractorsFactory = ChainSupport.factory { make() }

    /** Прогон как у ProgressiveMediaPeriod: init → read → RESULT_SEEK → init с новой позиции. */
    /** [base] и [len] — документ внутри файла (как AssetFileDescriptor со смещением). */
    internal class Chain(val file: File, factory: ExtractorsFactory, private val base: Long = 0, len: Long = -1) : AutoCloseable {
        val raf = RandomAccessFile(file, "r")
        val length = if (len >= 0) len else raf.length() - base
        val adapter = BundledExtractorsAdapter(factory)
        val out = Out()
        val ph = PositionHolder()
        var reloads = 0

        // Как ExtractingLoadable: LoadControl спрашивается, только когда вход ушёл на 1 МиБ дальше
        // позиции последнего открытия. Сколько секунд содержимого читается между такими проверками.
        private var checkPos = 0L
        private var checkTimeUs = -1L
        var maxUsBetweenLoadChecksClosed = 0L
        /** Наибольший отрезок содержимого без проверки, включая текущий, ещё не закрытый. */
        val maxUsBetweenLoadChecks: Long get() = maxOf(maxUsBetweenLoadChecksClosed, if (checkTimeUs < 0) 0 else out.maxTimeUs - checkTimeUs)

        fun open(position: Long) {
            checkPos = position
            ChainSupport.init(adapter, Reader(raf, position, length, base), position, length, out)
        }

        fun input(): ExtractorInput = BundledExtractorsAdapter::class.java.getDeclaredField("extractorInput")
            .apply { isAccessible = true }.get(adapter) as ExtractorInput

        fun extractor(): Extractor = BundledExtractorsAdapter::class.java.getDeclaredField("extractor")
            .apply { isAccessible = true }.get(adapter) as Extractor

        /** Читать, пока [until] не скажет «хватит»; false — конец файла. */
        fun read(until: () -> Boolean): Boolean {
            while (!until()) {
                val r = adapter.read(ph)
                val p = adapter.currentInputPosition
                if (checkTimeUs < 0) checkTimeUs = out.maxTimeUs
                if (p > checkPos + LOAD_CHECK_INTERVAL_BYTES) {
                    maxUsBetweenLoadChecksClosed = maxOf(maxUsBetweenLoadChecksClosed, out.maxTimeUs - checkTimeUs)
                    checkPos = p
                    checkTimeUs = out.maxTimeUs
                }
                when (r) {
                    Extractor.RESULT_SEEK -> { reloads++; open(ph.position) }
                    Extractor.RESULT_END_OF_INPUT -> return false
                }
            }
            return true
        }

        fun seek(timeUs: Long): SeekMap.SeekPoints {
            val points = out.seekMap!!.getSeekPoints(timeUs)
            adapter.seek(points.first.position, timeUs)
            open(points.first.position)
            return points
        }

        override fun close() {
            adapter.release()
            raf.close()
        }
    }

    /** ProgressiveMediaSource.Factory: DEFAULT_LOADING_CHECK_INTERVAL_BYTES. */
    private const val LOAD_CHECK_INTERVAL_BYTES = 1024 * 1024L

    internal fun peekBufferBytes(input: ExtractorInput): Int =
        (DefaultExtractorInput::class.java.getDeclaredField("peekBuffer").apply { isAccessible = true }.get(input) as ByteArray).size

    // ------------------------------------------------------------------
    //  Старая цепочка 0.4.3
    // ------------------------------------------------------------------

    /**
     * sniff экстрактора больших MP4 из 0.4.3 — дословно: обычный sniff Media3
     * (LargeMp4Extractor.sniff = Mp4Extractor(UNSUPPORTED, 0).sniff(input)).
     */
    class Sniff043 : Extractor by Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0) {
        override fun sniff(input: ExtractorInput): Boolean = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0).sniff(input)
    }

    /** 0.4.3: [экстрактор больших MP4] + DefaultExtractorsFactory — sniff на настоящем DefaultExtractorInput. */
    private fun old(file: File) {
        r("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1_048_576)
        val chain = Chain(file, Factory { arrayOf<Extractor>(Sniff043()) + ChainSupport.defaultExtractors() })
        resetPeak()
        try {
            chain.open(0)
            r("oom", false)
            r("peekBufferBytes", peekBufferBytes(chain.input()))
        } catch (e: OutOfMemoryError) {
            r("oom", true)
            val input = chain.input()
            val f = { n: String -> DefaultExtractorInput::class.java.getDeclaredField(n).apply { isAccessible = true } }
            val held = f("peekBufferLength").getInt(input)
            r("peekBufferBytesAtOom", (f("peekBuffer").get(input) as ByteArray).size)
            r("peekedBytesAtOom", held)
            r("stack", e.stackTrace.take(12).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" })
        } finally {
            r("peakHeapMiB", mib(peak()))
            chain.close()
        }
    }

    // ------------------------------------------------------------------
    //  Новая цепочка
    // ------------------------------------------------------------------

    private fun new(file: File, opt: Map<String, String>) {
        val rt = Runtime.getRuntime()
        r("maxHeapMiB", rt.maxMemory() / 1_048_576)
        val moovLimit = NoxExtractorsFactory.thresholdBytes()
        r("moovLimitMiB", mib(moovLimit))
        val repUs = opt["repUs"]?.toLong() ?: 8_000_000
        val base = retained()
        r("baseHeapMiB", mib(base))
        val factory = Factory { NoxExtractorsFactory.forSource(ChainSupport.defaultExtractors(), { ChannelByteSource.of(file) }, moovLimit) }

        // 1. Выбор экстрактора (sniff)
        resetPeak()
        var a0 = allocated()
        var t0 = System.nanoTime()
        val chain = Chain(file, factory)
        chain.open(0)
        r("sniffMs", (System.nanoTime() - t0) / 1_000_000)
        r("sniffPeakHeapMiB", mib(peak()))
        r("sniffAllocatedKiB", (allocated() - a0) / 1024)
        r("extractor", chain.extractor().javaClass.simpleName)
        r("peekBufferAfterSniff", peekBufferBytes(chain.input()))
        (chain.extractor() as? LargeMp4Extractor)?.lastScan?.let { r("scan", it) }
        // Собственная цена распознавания (после прогрева, без загрузки классов): BoundedMp4Sniffer.scan
        // и весь LargeMp4Extractor.sniff на свежем DefaultExtractorInput — выделено байт за вызов.
        ChannelByteSource.of(file).use { src ->
            repeat(3) { BoundedMp4Sniffer.scan(src) }
            val before = allocated()
            BoundedMp4Sniffer.scan(src)
            r("boundedScanAllocatedBytes", allocated() - before)
        }
        RandomAccessFile(file, "r").use { raf ->
            val sniffer = LargeMp4Extractor(moovLimit) { ChannelByteSource.of(file) }
            repeat(3) { sniffer.sniff(DefaultExtractorInput(Reader(raf, 0, raf.length()), 0, raf.length())) }
            val input = DefaultExtractorInput(Reader(raf, 0, raf.length()), 0, raf.length())
            val before = allocated()
            val ok = sniffer.sniff(input)
            r("largeSniffAllocatedBytes", allocated() - before)
            r("largeSniffResult", ok)
            r("largeSniffPeekBuffer", peekBufferBytes(input))
        }

        // 2. Подготовка дорожек: moov → таблицы → SeekMap
        resetPeak()
        a0 = allocated()
        t0 = System.nanoTime()
        chain.read { chain.out.seekMap != null && chain.out.ended }
        val prepMs = (System.nanoTime() - t0) / 1_000_000
        r("prepMs", prepMs)
        r("prepPeakHeapMiB", mib(peak()))
        r("prepAllocatedMiB", mib(allocated() - a0))
        r("prepRetainedMiB", mib(retained() - base))
        r("durationUs", chain.out.seekMap!!.durationUs)
        r("tracks", chain.out.tracks.values.joinToString(";") { "${it.id}:${it.format?.sampleMimeType}:${it.format?.width}x${it.format?.height}:max${it.format?.maxInputSize}" })
        (chain.extractor() as? LargeMp4Extractor)?.let { r("fallback", it.fallbackReason) }

        // 3. Первые сэмплы обеих дорожек (первый кадр — ключевой), эталон данных повтора 0
        val ref = HashMap<String, Long>()
        val firsts = HashMap<Int, Sample>()
        resetPeak()
        t0 = System.nanoTime()
        chain.out.onSample = { s ->
            firsts.putIfAbsent(s.track, s)
            ref["${s.track}@${Math.floorMod(s.timeUs, repUs)}"] = s.crc
        }
        // Весь первый повтор + немного: эталон CRC каждого сэмпла по времени внутри повтора.
        chain.read { chain.out.tracks.keys.all { id -> firsts[id] != null } && chain.out.samples > 0 &&
            ref.size >= opt.getOrDefault("refSamples", "575").toInt() }
        r("firstSamplesMs", (System.nanoTime() - t0) / 1_000_000)
        r("firstSamples", firsts.values.sortedBy { it.track }.joinToString(";") { "${it.track}:${it.timeUs}:key=${it.flags and C.BUFFER_FLAG_KEY_FRAME != 0}" })
        r("refSamples", ref.size)
        // Ещё 3 минуты подряд: как часто ExoPlayer смог бы спросить LoadControl (иначе буфер не ограничен).
        chain.out.onSample = null
        val until = chain.out.maxTimeUs + 180_000_000
        chain.read { chain.out.maxTimeUs >= until }
        r("maxContentBetweenLoadChecksS", chain.maxUsBetweenLoadChecks / 1_000_000)
        r("fdsWhileOpen", fileFds(file))

        // 4. Перемотки: начало, сохранённая позиция, середина, после 24 ч и 48 ч, 99 %, назад
        val d = chain.out.seekMap!!.durationUs
        val targets = listOf(
            "saved270s" to 270_000_000L, "middle" to d / 2, "after24h" to 24 * 3_600_000_000L + 3_700_000,
            "after48h" to 48 * 3_600_000_000L + 1_300_000, "p99" to d * 99 / 100, "back1h" to 3_600_000_000L, "start" to 0L,
        ).filter { it.second < d }
        var mismatches = 0
        var checked = 0
        for ((label, target) in targets) {
            resetPeak()
            t0 = System.nanoTime()
            val pts = chain.seek(target)
            val got = HashMap<Int, MutableList<Sample>>()
            chain.out.onSample = { s -> got.getOrPut(s.track) { ArrayList() }.add(s) }
            chain.read { chain.out.tracks.keys.all { (got[it]?.size ?: 0) >= 30 } }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val videoId = chain.out.tracks.values.first { (it.format?.width ?: 0) > 0 }.id
            val v = got[videoId]?.first()
            val au = got.entries.firstOrNull { it.key != videoId }?.value?.first()
            for (l in got.values) for (s in l) {
                val e = ref["${s.track}@${Math.floorMod(s.timeUs, repUs)}"]
                if (e != null) { checked++; if (e != s.crc) mismatches++ }
            }
            r("seek.$label", "target=$target point=${pts.first.timeUs}@${pts.first.position} firstVideo=${v?.timeUs} " +
                "key=${v?.let { it.flags and C.BUFFER_FLAG_KEY_FRAME != 0 }} firstAudio=${au?.timeUs} ms=$ms peakHeapMiB=${mib(peak())}")
        }
        r("crcChecked", checked)
        r("crcMismatches", mismatches)
        r("reloads", chain.reloads)
        chain.close()
        r("closedRetainedMiB", mib(retained() - base))
        r("fdsAfterClose", fileFds(file))

        // 5. Повторные открытия: удержанное после каждого закрытия
        val reopen = ArrayList<String>()
        repeat(opt["reopen"]?.toInt() ?: 3) {
            Chain(file, factory).use { c ->
                c.open(0)
                c.read { c.out.seekMap != null && c.out.samples >= 10 }
                c.seek(d / 3)
                c.read { c.out.samples >= 40 }
            }
            reopen += mib(retained() - base)
        }
        r("reopenRetainedMiB", reopen.joinToString(","))
        r("fdsAfterReopen", fileFds(file))

        // 6. Отмена подготовки: разбор индекса прерван на середине — дескрипторы и буферы освобождаются
        Chain(file, factory).use { c ->
            c.open(0)
            var calls = 0
            c.read { ++calls > 3 || c.out.seekMap != null }
        }
        r("cancelRetainedMiB", mib(retained() - base))
        r("fdsAfterCancel", fileFds(file))
        r("finalPeakHeapMiB", mib(peak()))
    }

    // ------------------------------------------------------------------
    //  Эталон Media3 (только для сравнения, с большой кучей)
    // ------------------------------------------------------------------

    /** Точки перемотки и первые сэмплы после них: Media3 Mp4Extractor против LargeMp4Extractor на всём индексе. */
    private fun media3Reference(file: File, points: Int) {
        fun run(make: () -> Extractor): List<String> {
            val chain = Chain(file, Factory { arrayOf(make()) })
            chain.open(0)
            chain.read { chain.out.seekMap != null && chain.out.ended }
            val d = chain.out.seekMap!!.durationUs
            val res = ArrayList<String>()
            res += "duration=$d formats=" + chain.out.tracks.values.joinToString { "${it.format}" }
            val rnd = java.util.Random(7)
            val times = (0 until points).map { (rnd.nextDouble() * d).toLong() } + listOf(0L, d / 2, d - 1, 86_400_000_000L, 172_800_000_000L)
            for (t in times) {
                val p = chain.seek(t)
                val got = ArrayList<String>()
                val per = HashMap<Int, Int>()
                chain.out.onSample = { s -> if ((per.merge(s.track, 1, Int::plus) ?: 0) <= 12) got += "${s.track}:${s.timeUs}:${s.flags}:${s.size}:${s.crc}" }
                chain.read { chain.out.tracks.keys.all { (per[it] ?: 0) >= 12 } }
                res += "t=$t p=${p.first.timeUs}@${p.first.position},${p.second.timeUs}@${p.second.position} " + got.sorted().joinToString(" ")
            }
            chain.close()
            return res
        }
        val t0 = System.nanoTime()
        val a = run { Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0) }
        r("media3Ms", (System.nanoTime() - t0) / 1_000_000)
        val t1 = System.nanoTime()
        // Предел обычного пути 0: любой уход в Mp4Extractor был бы ошибкой, а не тихой подменой эталона.
        val b = run { LargeMp4Extractor(0) { ChannelByteSource.of(file) } }
        r("largeMs", (System.nanoTime() - t1) / 1_000_000)
        val diff = a.indices.filter { a[it] != b.getOrNull(it) }
        r("compared", a.size)
        r("differences", diff.size)
        diff.take(3).forEach { r("diff", "media3: ${a[it].take(300)} | large: ${b.getOrNull(it)?.take(300)}") }
    }
}
