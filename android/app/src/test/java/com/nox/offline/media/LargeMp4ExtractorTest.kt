@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.media

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * Экономный разбор MP4 против Mp4Extractor из Media3 на одних и тех же файлах:
 * форматы дорожек, длительность, каждый сэмпл (время, флаги, размер, CRC32
 * данных), точки перемотки и сэмплы после перемотки должны совпасть.
 * Порядок чтения между дорожками может отличаться — сравнивается по дорожкам.
 */
class LargeMp4ExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): File {
        val f = tmp.newFile(name)
        javaClass.getResourceAsStream("/media/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun media3(): Extractor = Mp4Extractor(DefaultSubtitleParserFactory(), 0)

    private class Run(val harness: ExtractorHarness, val perTrack: Map<Int, MutableList<ExtractorHarness.Sample>>)

    private fun start(file: File, extractor: Extractor, keepPerTrack: Int): Run {
        val per = sortedMapOf<Int, MutableList<ExtractorHarness.Sample>>()
        val h = ExtractorHarness(file, extractor)
        h.onSample = { s -> per.getOrPut(s.track) { ArrayList() }.let { if (it.size < keepPerTrack) it.add(s) } }
        return Run(h, per)
    }

    /** Полное сравнение файла; возвращает число сравненных сэмплов. */
    private fun compare(file: File, keepPerTrack: Int = 400, randomSeeks: Int = 6, afterSeek: Int = 80): Long {
        val large = LargeMp4Extractor { ChannelByteSource.of(file) }
        val a = start(file, media3(), keepPerTrack)
        val b = start(file, large, keepPerTrack)
        a.harness.read()
        b.harness.read()
        assertNull("экономный путь не должен уходить в Media3: ${large.fallbackReason}", large.fallbackReason)
        val ta = a.harness.tracks
        val tb = b.harness.tracks
        assertEquals("${file.name}: дорожки", ta.keys, tb.keys)
        for (id in ta.keys) {
            assertEquals("${file.name}: формат дорожки $id", ta[id]!!.format, tb[id]!!.format)
            assertEquals("${file.name}: число сэмплов дорожки $id", ta[id]!!.count, tb[id]!!.count)
            assertEquals("${file.name}: первые сэмплы дорожки $id", a.perTrack[id], b.perTrack[id])
            assertEquals("${file.name}: отпечаток всех сэмплов дорожки $id", ta[id]!!.digest, tb[id]!!.digest)
        }
        val sa = a.harness.seekMap!!
        val sb = b.harness.seekMap!!
        assertEquals("${file.name}: длительность", sa.durationUs, sb.durationUs)
        assertEquals(sa.isSeekable, sb.isSeekable)

        // Перемотки: те же точки, те же сэмплы после.
        val d = sa.durationUs
        val rnd = Random(file.name.hashCode().toLong())
        val times = mutableListOf(0L, 1L, d / 3, d / 2, d * 9 / 10, d - 1, d, d + 1_000_000)
        repeat(randomSeeks) { times += (rnd.nextDouble() * d).toLong() }
        for (t in times) {
            val pa = a.harness.seekTo(t)
            val pb = b.harness.seekTo(t)
            assertEquals("${file.name}: точки перемотки к $t", points(pa), points(pb))
            val ca = afterSeek(a.harness, ta.size, afterSeek)
            val cb = afterSeek(b.harness, tb.size, afterSeek)
            assertEquals("${file.name}: дорожки после перемотки к $t", ca.second.keys, cb.second.keys)
            for (id in ca.second.keys) {
                val la = ca.second.getValue(id)
                val lb = cb.second.getValue(id)
                // Дочитали до конца файла — сравниваем всё; иначе — первые [afterSeek] каждой дорожки.
                if (ca.first && cb.first) assertEquals("${file.name}: сэмплы дорожки $id после перемотки к $t", la, lb)
                else assertEquals("${file.name}: сэмплы дорожки $id после перемотки к $t", la.take(afterSeek), lb.take(afterSeek))
            }
        }
        val total = ta.values.sumOf { it.count }
        a.harness.close()
        b.harness.close()
        large.release()
        return total
    }

    /** Читать после перемотки, пока у каждой дорожки не наберётся [k] сэмплов; first — дочитано до конца. */
    private fun afterSeek(h: ExtractorHarness, tracks: Int, k: Int): Pair<Boolean, Map<Int, List<ExtractorHarness.Sample>>> {
        val got = sortedMapOf<Int, MutableList<ExtractorHarness.Sample>>()
        h.onSample = { s -> got.getOrPut(s.track) { ArrayList() }.add(s) }
        val stopped = h.read(until = { got.size == tracks && got.values.all { it.size >= k } })
        h.onSample = null
        return !stopped to got
    }

    private fun points(p: SeekMap.SeekPoints) = "${p.first.timeUs}@${p.first.position} ${p.second.timeUs}@${p.second.position}"

    @Test fun h264FaststartMatchesMedia3() { assertTrue(compare(resource("h264-faststart.mp4")) > 0) }

    @Test fun h264MoovAtEndMatchesMedia3() { assertTrue(compare(resource("h264-moov-end.mp4")) > 0) }

    @Test fun bFramesAndAacPrimingEditsMatchMedia3() { assertTrue(compare(resource("avc-bframes-aac.mp4")) > 0) }

    @Test fun hevcMatchesMedia3() { assertTrue(compare(resource("hevc-aac.mp4")) > 0) }

    @Test fun variableFrameRateWithoutBFramesMatchesMedia3() { assertTrue(compare(resource("avc-vfr-nob.mp4")) > 0) }

    @Test fun lateVideoWithEmptyEditAndTwoAudioTracksMatchesMedia3() { assertTrue(compare(resource("avc-late-video-2audio.mp4")) > 0) }

    /** 64-битные смещения (co64) и moov в начале — как у файла больше 4 ГБ. */
    @Test fun co64FaststartMatchesMedia3() {
        val src = resource("avc-bframes-aac.mp4")
        val out = File(tmp.root, "co64.mp4")
        ChannelByteSource.of(src).use { s ->
            val insp = Mp4Inspector(CachedReader(s), CheckControl.NONE)
            insp.inspect(-1)
            out.outputStream().buffered().use { Mp4Rebuild(s, insp.lastIndex!!).write(it, CheckControl.NONE) }
        }
        assertTrue(compare(out) > 0)
    }

    /** Файл не MP4 с индексом в файле (не читается второй дескриптор) — обычный путь Media3, без выдачи дорожек дважды. */
    @Test fun sourceThatCannotBeOpenedFallsBackToMedia3() {
        val file = resource("avc-bframes-aac.mp4")
        val large = LargeMp4Extractor { throw java.io.IOException("провайдер не дал дескриптор") }
        val a = start(file, media3(), 100)
        val b = start(file, large, 100)
        a.harness.read()
        b.harness.read()
        assertTrue(large.fallbackReason!!.contains("не читаются"))
        for (id in a.harness.tracks.keys) {
            assertEquals(a.harness.tracks[id]!!.format, b.harness.tracks[id]!!.format)
            assertEquals(a.harness.tracks[id]!!.digest, b.harness.tracks[id]!!.digest)
        }
        assertEquals(a.harness.tracks.keys, b.harness.tracks.keys)
    }

    /**
     * Синтетический марафон (настоящие кадры, таблицы с ctts, stss, co64/stco,
     * stsc с записями на каждый повтор, правка у видео) — короткие варианты
     * целиком против Media3: moov в начале и в конце, 32- и 64-битные заголовки.
     */
    @Test fun syntheticMarathonMatchesMedia3() {
        val gen = SyntheticMarathon(SyntheticMarathon.clipFromResources())
        val variants = listOf(
            "m-start.mp4" to SyntheticMarathon.Options(durationSec = 96),
            "m-end-large.mp4" to SyntheticMarathon.Options(durationSec = 96, moovFirst = false, largeHeaders = true),
            "m-stco-singles.mp4" to SyntheticMarathon.Options(durationSec = 96, co64 = false, audioSingles = 375),
            "m-pairs-noedit.mp4" to SyntheticMarathon.Options(durationSec = 96, audioSingles = 1, videoEdit = false),
        )
        for ((name, o) in variants) {
            val f = File(tmp.root, name)
            gen.write(f, o)
            assertTrue(compare(f, keepPerTrack = 2000, randomSeeks = 10, afterSeek = 120) > 0)
        }
    }

    /** Длинный файл стенда (NOX_LONG_FILE): все сэмплы и перемотки. */
    @Test fun longFileMatchesMedia3() {
        val path = System.getenv("NOX_LONG_FILE")
        assumeTrue("нужен NOX_LONG_FILE", path != null && File(path).exists())
        val n = compare(File(path!!), keepPerTrack = 2000, randomSeeks = 20, afterSeek = 300)
        println("длинный файл: сравнено $n сэмплов")
        assertTrue(n > 1_000_000)
    }
}
