@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer

/**
 * Эксперимент с большими контейнерами: настоящие кадры 2160p многократно
 * пишутся системным MediaMuxer (им NOX склеивает дорожки) за границу 4 ГБ,
 * затем файл читают системный MediaExtractor и экстракторы Media3 (плеер NOX)
 * в начале и после перемотки к концу.
 *
 * Тяжёлый: запускается только с аргументом `nox.large=1` и исходниками в
 * `files/bigsrc/` приложения (v264.mp4, a.m4a, v9.webm, a.webm).
 */
@RunWith(AndroidJUnit4::class)
class LargeContainerDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun arg(key: String): String? = InstrumentationRegistry.getArguments().getString(key)
    private val dir get() = File(ctx.getExternalFilesDir(null), "bigsrc")
    private val report = StringBuilder()

    private fun say(s: String) {
        Log.i(TAG, s)
        report.append(s).append('\n')
    }

    @Before
    fun onlyOnRequest() {
        assumeTrue("большие файлы — только по запросу (nox.large=1)", arg("nox.large") == "1")
    }

    @Test
    fun mp4Over4GbFromMediaMuxer() = runCase("mp4", File(dir, "v264.mp4"), File(dir, "a.m4a"),
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) { Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0) }

    @Test
    fun webmOver4GbFromMediaMuxer() = runCase("webm", File(dir, "v9.webm"), File(dir, "a.webm"),
        MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) { MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0) }

    private fun runCase(name: String, video: File, audio: File, format: Int, extractor: () -> Extractor) {
        val target = (arg("nox.target") ?: "4400000000").toLong()
        val out = File(dir, "big-$name.${if (format == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) "webm" else "mp4"}")
        out.delete()
        say("== $name: цель ${target / 1_000_000} МБ, свободно ${dir.usableSpace / 1_000_000} МБ")
        val t0 = System.currentTimeMillis()
        val written = muxLoop(video, audio, out, format, target)
        say("запись: ${written.first} байт сэмплов, файл ${out.length()} байт, ошибка: ${written.second ?: "нет"}, " +
            "${(System.currentTimeMillis() - t0) / 1000} с")
        if (out.exists() && out.length() > 0) {
            val c = com.nox.offline.media.ChannelByteSource.of(out).use { com.nox.offline.media.ContainerCheck.check(it) }
            say("проверка NOX: ${c.verdict} — ${c.summary} | ${c.details.take(4).joinToString("; ")} | ${c.tracks.joinToString("; ")}")
            inspectPlatform(out)
            inspectMedia3(out, extractor)
            if (arg("nox.keep") != "1") out.delete()
        }
        File(dir, "report-$name.txt").writeText(report.toString())
    }

    private class Sample(val track: Int, val data: ByteArray, val timeUs: Long, val flags: Int)

    private fun readAll(file: File, prefix: String): Pair<MediaFormat, List<Sample>> {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }
        ex.selectTrack(t)
        val fmt = ex.getTrackFormat(t)
        val list = ArrayList<Sample>()
        val buf = ByteBuffer.allocateDirect(16 * 1024 * 1024)
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            val bytes = ByteArray(n)
            buf.get(bytes, 0, n)
            val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            list.add(Sample(0, bytes, ex.sampleTime, flags))
            ex.advance()
        }
        ex.release()
        return fmt to list
    }

    /** Повторять кадры исходников с растущим временем, пока файл не превысит [target]. */
    private fun muxLoop(video: File, audio: File, out: File, format: Int, target: Long): Pair<Long, String?> {
        val (vf, vs) = readAll(video, "video/")
        val (af, asm) = readAll(audio, "audio/")
        val period = maxOf(vs.last().timeUs, asm.last().timeUs) + 100_000
        val m = MediaMuxer(out.absolutePath, format)
        val tv = m.addTrack(vf)
        val ta = m.addTrack(af)
        m.start()
        val info = MediaCodec.BufferInfo()
        var written = 0L
        var loop = 0L
        var error: String? = null
        try {
            while (written < target) {
                val base = loop * period
                var i = 0
                var j = 0
                while (i < vs.size || j < asm.size) {
                    val useVideo = j >= asm.size || (i < vs.size && vs[i].timeUs <= asm[j].timeUs)
                    val s = if (useVideo) vs[i++] else asm[j++]
                    info.set(0, s.data.size, base + s.timeUs, s.flags)
                    m.writeSampleData(if (useVideo) tv else ta, ByteBuffer.wrap(s.data), info)
                    written += s.data.size
                }
                loop++
                // Писатель WebM копит кадры в очереди, пока диск не успевает: ждём, пока файл догонит.
                val until = System.currentTimeMillis() + 120_000
                while (written - out.length() > 128L * 1024 * 1024 && System.currentTimeMillis() < until) Thread.sleep(200)
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message} (на $written байтах)"
        }
        try { m.stop() } catch (t: Throwable) { error = (error ?: "") + " | stop: ${t.javaClass.simpleName}: ${t.message}" }
        try { m.release() } catch (_: Throwable) { }
        say("петель $loop, длительность ≈ ${loop * period / 1_000_000} с")
        return written to error
    }

    /** Как MediaMerger.probe/verify: дорожки и длительность по данным системного разборщика. */
    private fun inspectPlatform(file: File) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val parts = (0 until ex.trackCount).map { i ->
                val f = ex.getTrackFormat(i)
                "${f.getString(MediaFormat.KEY_MIME)} ${if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1_000_000 else -1} с"
            }
            say("MediaExtractor: ${parts.joinToString()}")
            ex.selectTrack(0)
            ex.seekTo(Long.MAX_VALUE / 4, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            say("MediaExtractor в конце: время ${ex.sampleTime / 1_000_000} с, чтение ${ex.readSampleData(buf, 0)} байт")
        } catch (t: Throwable) {
            say("MediaExtractor: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            ex.release()
        }
    }

    private class CountingTrack : TrackOutput {
        var format: Format? = null
        var samples = 0
        var lastTimeUs = C.TIME_UNSET
        private val scratch = ByteArray(1 shl 16)
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return n; throw EOFException() }
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples++
            lastTimeUs = timeUs
        }
    }

    private class CountingOutput : ExtractorOutput {
        val tracks = HashMap<Int, CountingTrack>()
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { CountingTrack() }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
        fun samples() = tracks.values.sumOf { it.samples }
    }

    /** Чтение экстрактором Media3: начало файла, затем перемотка к 95 % и чтение там. */
    private fun inspectMedia3(file: File, make: () -> Extractor) {
        val out = CountingOutput()
        val ex = make()
        val len = file.length()
        val uri = Uri.fromFile(file)
        fun open(pos: Long): Pair<FileDataSource, DefaultExtractorInput> {
            val ds = FileDataSource()
            ds.open(DataSpec.Builder().setUri(uri).setPosition(pos).build())
            return ds to DefaultExtractorInput(ds, pos, len)
        }
        var (ds, input) = open(0)
        try {
            say("Media3 sniff: ${ex.sniff(input)}")
            ds.close()
            val o = open(0); ds = o.first; input = o.second
            ex.init(out)
            val ph = PositionHolder()
            fun drive(limit: Int, phase: String) {
                val start = out.samples()
                while (out.samples() - start < limit) {
                    val r = ex.read(input, ph)
                    if (r == Extractor.RESULT_SEEK) {
                        ds.close()
                        val n = open(ph.position); ds = n.first; input = n.second
                    } else if (r == Extractor.RESULT_END_OF_INPUT) {
                        say("Media3 $phase: конец файла на позиции ${input.position}")
                        break
                    }
                }
                val tr = out.tracks.values.joinToString { "${it.format?.sampleMimeType} ${it.samples} сэмплов до ${it.lastTimeUs / 1_000_000} с" }
                say("Media3 $phase: позиция ${input.position}, $tr")
            }
            drive(300, "начало")
            val sm = out.seekMap
            say("Media3 SeekMap: seekable=${sm?.isSeekable} длительность=${sm?.durationUs?.div(1_000_000)} с")
            if (sm != null && sm.isSeekable && sm.durationUs > 0) {
                val t = sm.durationUs * 95 / 100
                val sp = sm.getSeekPoints(t).first
                say("Media3 перемотка к ${t / 1_000_000} с -> смещение ${sp.position} (${if (sp.position > 0xFFFFFFFFL) "за 4 ГБ" else "до 4 ГБ"})")
                ex.seek(sp.position, sp.timeUs)
                ds.close()
                val n = open(sp.position); ds = n.first; input = n.second
                drive(300, "после перемотки")
            }
        } catch (t: Throwable) {
            val chain = generateSequence(t) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            say("Media3 ОШИБКА на позиции ${input.position}: $chain")
        } finally {
            runCatching { ds.close() }
            ex.release()
        }
    }

    companion object {
        private const val TAG = "NOX-LARGE"
    }
}
