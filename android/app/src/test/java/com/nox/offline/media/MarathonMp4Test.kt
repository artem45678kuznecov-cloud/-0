@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.media

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 70-часовой MP4 (синтетический, с настоящими кадрами и полным индексом
 * ~18 млн сэмплов, moov ≈217 МиБ) через ту же цепочку, что у плеера:
 * BundledExtractorsAdapter → DefaultExtractorInput → sniff → read → SeekMap → seek.
 *
 * Каждый замер — в отдельном процессе JVM с пределом кучи: -Xmx256m, как
 * у приложения на телефоне пользователя (OBLUE TANK 3, heapgrowthlimit 256 МиБ).
 * Итоги замеров пишутся в build/reports/marathon/report.txt.
 */
class MarathonMp4Test {
    companion object {
        private lateinit var dir: File
        lateinit var moovFirst: File
        lateinit var moovLast: File
        lateinit var startLayout: SyntheticMarathon.Layout
        lateinit var endLayout: SyntheticMarathon.Layout
        private val report = StringBuilder()

        @BeforeClass @JvmStatic
        fun generate() {
            dir = File(System.getProperty("nox.marathonDir") ?: "build/tmp/marathon").apply { mkdirs() }
            val gen = SyntheticMarathon(SyntheticMarathon.clipFromResources())
            moovFirst = File(dir, "marathon-70h-moov-first.mp4")
            moovLast = File(dir, "marathon-70h-moov-last-30g.mp4")
            var t0 = System.nanoTime()
            startLayout = gen.write(moovFirst, SyntheticMarathon.Options())
            say("файл 1: moov в начале — ${startLayout.moovSize} байт (${startLayout.moovSize / 1_048_576} МиБ), " +
                "${startLayout.videoSamples} видео + ${startLayout.audioSamples} звука сэмплов, ${startLayout.durationUs / 1_000_000} с, " +
                "размер ${startLayout.fileSize} байт; создан за ${(System.nanoTime() - t0) / 1_000_000} мс")
            t0 = System.nanoTime()
            // moov в конце, после mdat с 64-битным размером; кадры дальше 30 ГБ (разреженная дыра) — смещения co64 > 2^32.
            endLayout = gen.write(moovLast, SyntheticMarathon.Options(moovFirst = false, holeBytes = 30_000_000_000L, largeHeaders = true))
            say("файл 2: moov в конце (после mdat, 64-битные заголовки), кадры с ${endLayout.dataStart} байт, " +
                "видимый размер ${endLayout.fileSize} байт; создан за ${(System.nanoTime() - t0) / 1_000_000} мс")
        }

        @AfterClass @JvmStatic
        fun cleanup() {
            File("build/reports/marathon").apply { mkdirs() }.resolve("report.txt").writeText(report.toString())
            if (System.getProperty("nox.keepMarathon") == null) { moovFirst.delete(); moovLast.delete() }
        }

        fun say(s: String) { println(s); synchronized(report) { report.append(s).append('\n') } }

        /** Запуск [MarathonProbe] в отдельной JVM с кучей [heap]. */
        fun probe(heap: String, vararg args: String, timeoutS: Long = 900): Map<String, String> {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val pb = ProcessBuilder(listOf(java, "-Xmx$heap", "-Xss4m", MarathonProbe::class.java.name) + args).redirectErrorStream(true)
            pb.environment()["CLASSPATH"] = System.getProperty("java.class.path")
            val p = pb.start()
            val lines = ArrayList<String>()
            val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(lines) { lines += it } } }.apply { start() }
            val finished = p.waitFor(timeoutS, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            reader.join(5000)
            val out = synchronized(lines) { lines.toList() }
            val res = LinkedHashMap<String, String>()
            for (l in out) if (l.startsWith("R ")) res[l.substring(2).substringBefore('=')] = l.substringAfter('=')
            assertTrue("процесс замера не уложился в $timeoutS с:\n${out.takeLast(40).joinToString("\n")}", finished)
            assertTrue("процесс замера упал (код ${p.exitValue()}):\n${out.takeLast(60).joinToString("\n")}", res["done"] == "true")
            return res
        }
    }

    private fun seekLine(r: Map<String, String>, label: String): Map<String, String> =
        r.getValue("seek.$label").split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    /** Проверки нового пути на длинном файле; [heap] — предел кучи процесса. */
    private fun checkNewChain(file: File, layout: SyntheticMarathon.Layout, heap: String, title: String): Map<String, String> {
        val r = probe(heap, "new", file.path)
        say("--- $title, куча -Xmx$heap")
        r.filterKeys { it != "done" }.forEach { (k, v) -> say("  $k = $v") }
        assertEquals("$title: выбран экономный разборщик", "LargeMp4Extractor", r["extractor"])
        assertEquals("$title: peekBuffer после распознавания не растёт", "65536", r["peekBufferAfterSniff"])
        assertEquals("$title: без ухода в обычный Mp4Extractor", "null", r["fallback"])
        val duration = r.getValue("durationUs").toLong()
        assertTrue("$title: длительность $duration вместо ${layout.durationUs}", Math.abs(duration - layout.durationUs) <= 100_000)
        assertTrue("$title: первый видеосэмпл — ключевой с 0: ${r["firstSamples"]}", r.getValue("firstSamples").contains(":0:key=true"))
        assertEquals("$title: эталон первого повтора", "575", r["refSamples"])
        // Плеер ограничивает буфер, только если вход продвигается: между проверками LoadControl — меньше 50 с.
        assertTrue("$title: между проверками загрузки ${r["maxContentBetweenLoadChecksS"]} с", r.getValue("maxContentBetweenLoadChecksS").toLong() < 50)
        assertEquals("$title: данные сэмплов после перемоток совпадают с эталоном", "0", r["crcMismatches"])
        assertTrue("$title: сверено сэмплов ${r["crcChecked"]}", r.getValue("crcChecked").toInt() > 300)
        // Первый кадр ролика показывается с 80 мс (так его читает и Media3): к началу перемотка встаёт на него.
        val firstKey = r.getValue("firstSamples").split(';').first { it.startsWith("0:") }.split(':')[1].toLong()
        val labels = listOf("saved270s", "middle", "after24h", "after48h", "p99", "back1h", "start").filter { r.containsKey("seek.$it") }
        assertTrue("$title: перемотки ${labels}", labels.size >= 5)
        for (label in labels) {
            val s = seekLine(r, label)
            val target = s.getValue("target").toLong()
            val v = s.getValue("firstVideo").toLong()
            assertEquals("$title: перемотка $label начинается с ключевого кадра", "true", s["key"])
            val ok = if (target < firstKey) v == firstKey else v <= target && target - v <= 2_000_000
            assertTrue("$title: перемотка $label: кадр $v — ключевой не позже цели $target и не дальше 2 с (GOP)", ok)
            assertTrue("$title: перемотка $label: есть звук", s["firstAudio"] != "null")
        }
        assertEquals("$title: во время чтения файл открыт плеером и экономным разбором", "2", r["fdsWhileOpen"])
        assertEquals("$title: дескрипторы закрыты после закрытия", "0", r["fdsAfterClose"])
        assertEquals("$title: дескрипторы закрыты после повторных открытий", "0", r["fdsAfterReopen"])
        assertEquals("$title: дескрипторы закрыты после отмены", "0", r["fdsAfterCancel"])
        val reopen = r.getValue("reopenRetainedMiB").split(',').map { it.toDouble() }
        assertTrue("$title: повторные открытия не копят память: $reopen", reopen.last() - reopen.first() < 2.0)
        assertTrue("$title: после закрытия удержано ${r["closedRetainedMiB"]} МиБ", r.getValue("closedRetainedMiB").toDouble() < 4.0)
        return r
    }

    /** Старая ошибка: sniff 0.4.3 на настоящем DefaultExtractorInput копирует весь moov в peekBuffer. */
    @Test
    fun oldSniffOnHugeMoovRunsOutOfMemoryInPeekBuffer() {
        val r = probe("256m", "old", moovFirst.path)
        say("--- старый sniff 0.4.3 (Mp4Extractor.sniff внутри экстрактора больших MP4), куча -Xmx256m")
        r.filterKeys { it != "done" }.forEach { (k, v) -> say("  $k = $v") }
        assertEquals("старый путь должен падать нехваткой памяти", "true", r["oom"])
        val stack = r.getValue("stack")
        for (frame in listOf("DefaultExtractorInput.ensureSpaceForPeek", "DefaultExtractorInput.advancePeekPosition",
                "Sniffer.sniffInternal", "Mp4Extractor.sniff", "Sniff043.sniff", "BundledExtractorsAdapter.init")) {
            assertTrue("в стеке нет $frame: $stack", stack.contains(frame))
        }
        // К моменту отказа во входе уже удержано больше 100 МиБ просмотренного moov.
        assertTrue("удержано ${r["peekedBytesAtOom"]}", r.getValue("peekedBytesAtOom").toLong() > 100L shl 20)
    }

    @Test
    fun moovAtStartPlaysWithin256MiB() {
        val r = checkNewChain(moovFirst, startLayout, "256m", "moov в начале (≈217 МиБ)")
        // Бюджет распознавания: окно 4 КиБ + служебное, независимо от moov 217 МиБ.
        val scan = r.getValue("boundedScanAllocatedBytes").toLong()
        val sniff = r.getValue("largeSniffAllocatedBytes").toLong()
        assertTrue("BoundedMp4Sniffer.scan выделил $scan байт", scan in 0..BoundedMp4Sniffer.MEMORY_BUDGET_BYTES)
        assertTrue("LargeMp4Extractor.sniff выделил $sniff байт", sniff in 0..BoundedMp4Sniffer.MEMORY_BUDGET_BYTES)
        assertEquals("sniff на свежем входе не растит peekBuffer", "65536", r["largeSniffPeekBuffer"])
        assertEquals("true", r["largeSniffResult"])
    }

    /** Та же цепочка при куче в 8 раз меньше: память не зависит от размера moov. */
    @Test
    fun moovAtStartPlaysWithin32MiB() {
        checkNewChain(moovFirst, startLayout, "32m", "moov в начале (≈217 МиБ)")
    }

    @Test
    fun moovAtEndAfter30GbMdatPlaysWithin256MiB() {
        val r = checkNewChain(moovLast, endLayout, "256m", "moov в конце после mdat 30 ГБ (co64 > 4 ГиБ)")
        assertTrue(r.getValue("scan").contains("moovAfterMdat=true"))
        val pos = seekLine(r, "after48h").getValue("point").substringAfter('@').toLong()
        assertTrue("смещение кадра после 48 ч ($pos) дальше 4 ГиБ", pos > (1L shl 32))
    }

    /** Весь индекс 70 часов против Mp4Extractor Media3 (с большой кучей): точки перемотки и сэмплы после них. */
    @Test
    fun wholeIndexMatchesMedia3() {
        val r = probe("3g", "media3", moovFirst.path, "300", timeoutS = 1800)
        say("--- сравнение с Media3 Mp4Extractor (куча 3 ГиБ только для эталона): ${r.filterKeys { it != "done" }}")
        assertEquals("расхождений с Media3: ${r["diff"]}", "0", r["differences"])
        assertTrue(r.getValue("compared").toInt() > 300)
    }

    /**
     * Регрессия 0.4.3: 15-часовой фильм с moov в конце (как файл стенда long15h.mp4).
     * У обычного Mp4Extractor он падал в calculateAccumulatedSampleSizes; теперь — экономный путь.
     */
    @Test
    fun fifteenHourMoovAtEndStillPlaysWithin256MiB() {
        val f = File(dir, "marathon-15h-moov-end.mp4")
        val l = SyntheticMarathon(SyntheticMarathon.clipFromResources()).write(f, SyntheticMarathon.Options(durationSec = 54_193, moovFirst = false))
        try {
            say("файл 15 ч: moov в конце, ${l.moovSize / 1_048_576} МиБ, ${l.videoSamples + l.audioSamples} сэмплов")
            checkNewChain(f, l, "256m", "15 ч, moov в конце (регрессия 0.4.3)")
        } finally {
            f.delete()
        }
    }

    /** Удержанное после подготовки не растёт с длиной индекса: 10 ч против 70 ч. */
    @Test
    fun retainedMemoryDoesNotGrowWithIndex() {
        val short = File(dir, "marathon-10h.mp4")
        val l = SyntheticMarathon(SyntheticMarathon.clipFromResources()).write(short, SyntheticMarathon.Options(durationSec = 36_000))
        try {
            val a = probe("256m", "new", short.path, "reopen=1").getValue("prepRetainedMiB").toDouble()
            val b = probe("256m", "new", moovFirst.path, "reopen=1").getValue("prepRetainedMiB").toDouble()
            say("--- удержано после подготовки: 10 ч (moov ${l.moovSize / 1_048_576} МиБ) — $a МиБ; 70 ч (moov ${startLayout.moovSize / 1_048_576} МиБ) — $b МиБ")
            assertTrue("рост с длиной индекса: $a → $b МиБ", b - a < 2.0)
        } finally {
            short.delete()
        }
        assertFalse(short.exists())
    }
}
