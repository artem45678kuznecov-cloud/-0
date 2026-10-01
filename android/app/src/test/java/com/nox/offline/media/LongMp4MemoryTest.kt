@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.media

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Замер на JVM (вспомогательный, не замена замеру на телефоне): сколько кучи
 * берёт разбор индекса длинного MP4 до первого сэмпла. Файл — NOX_LONG_FILE.
 */
class LongMp4MemoryTest {
    private fun used(): Long {
        repeat(3) { System.gc(); Thread.sleep(50) }
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory()
    }

    fun measure(name: String, file: File, make: () -> Extractor): String {
        val before = used()
        var sampled = 0L
        val sampler = Thread {
            val r = Runtime.getRuntime()
            while (!Thread.currentThread().isInterrupted) {
                sampled = maxOf(sampled, r.totalMemory() - r.freeMemory())
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val t0 = System.nanoTime()
        val h = ExtractorHarness(file, make())
        h.read(until = { h.seekMap != null })
        val parseMs = (System.nanoTime() - t0) / 1_000_000
        sampler.interrupt(); sampler.join()
        val retained = used() - before
        val counts = h.tracks.values.joinToString { "${it.format?.sampleMimeType}" }
        h.close()
        return "$name: разбор индекса $parseMs мс; удержано после разбора ${retained / 1_048_576} МиБ; " +
            "пик кучи по опросу каждые 2 мс (вместе с ещё не собранным мусором) ${(sampled - before) / 1_048_576} МиБ; дорожки: $counts; " +
            "длительность ${h.seekMap?.durationUs?.div(1_000_000)} с"
    }

    /** «Проверить файл» (структура): пик и удержанное после проверки. */
    @Test
    fun fileCheckMemoryOnLongFile() {
        val path = System.getenv("NOX_LONG_FILE")
        assumeTrue("нужен NOX_LONG_FILE", path != null && File(path).exists())
        val file = File(path!!)
        val before = used()
        var sampled = 0L
        val sampler = Thread {
            val r = Runtime.getRuntime()
            while (!Thread.currentThread().isInterrupted) {
                sampled = maxOf(sampled, r.totalMemory() - r.freeMemory())
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val t0 = System.nanoTime()
        val c = ChannelByteSource.of(file).use { ContainerCheck.check(it, -1, CheckControl.NONE) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        sampler.interrupt(); sampler.join()
        val retained = used() - before
        val r = "Проверка структуры (Mp4Inspector): ${c.verdict} за $ms мс; пик ${(sampled - before) / 1_048_576} МиБ; " +
            "удержано после проверки ${retained / 1_048_576} МиБ; ${c.summary.take(160)}"
        println(r)
        File(System.getProperty("java.io.tmpdir"), "nox-long-memory.txt").appendText(r + "\n")
    }

    /** Скорость чтения кадров после разбора: с 270 с и с середины, по 100 000 сэмплов. */
    @Test
    fun sampleReadSpeedOnLongFile() {
        val path = System.getenv("NOX_LONG_FILE")
        assumeTrue("нужен NOX_LONG_FILE", path != null && File(path).exists())
        val file = File(path!!)
        val out = File(System.getProperty("java.io.tmpdir"), "nox-long-memory.txt")
        for ((name, make) in listOf<Pair<String, () -> Extractor>>(
            "Media3 Mp4Extractor" to { Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0) },
            "LargeMp4Extractor" to { LargeMp4Extractor { ChannelByteSource.of(file) } },
        )) {
            val h = ExtractorHarness(file, make())
            h.read(until = { h.seekMap != null })
            val parts = ArrayList<String>()
            for (at in listOf(270_000_000L, h.seekMap!!.durationUs / 2)) {
                h.seekTo(at)
                val reloads = h.reloads
                val t0 = System.nanoTime()
                h.read(maxSamples = 100_000)
                parts += "с ${at / 1_000_000} с: 100 000 сэмплов за ${(System.nanoTime() - t0) / 1_000_000} мс, переоткрытий ${h.reloads - reloads}"
            }
            h.close()
            val r = "$name, чтение кадров: ${parts.joinToString("; ")}"
            println(r)
            out.appendText(r + "\n")
        }
    }

    @Test
    fun media3Mp4ExtractorOnLongFile() {
        val path = System.getenv("NOX_LONG_FILE")
        assumeTrue("нужен NOX_LONG_FILE", path != null && File(path).exists())
        val file = File(path!!)
        val out = File(System.getProperty("java.io.tmpdir"), "nox-long-memory.txt")
        for ((name, make) in listOf<Pair<String, () -> Extractor>>(
            "Media3 Mp4Extractor" to { Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0) },
            "LargeMp4Extractor (таблицы в файле)" to { LargeMp4Extractor { ChannelByteSource.of(file) } },
        )) {
            val r = measure(name, file, make)
            println(r)
            out.appendText(r + "\n")
        }
    }
}
