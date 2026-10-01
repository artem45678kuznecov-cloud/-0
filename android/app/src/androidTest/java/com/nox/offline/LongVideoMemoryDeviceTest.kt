@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline

import android.os.Debug
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.nox.offline.core.NoxLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.media.CheckControl
import com.nox.offline.media.FileChecks
import com.nox.offline.storage.MediaLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Память NOX на длинном MP4 — как у пользователя: видео из медиатеки
 * открывается настоящим PlaybackHub с сохранённой позиции 270 000 мс, на
 * экране MainActivity. Замер по этапам: Java-куча (её ограничивает
 * heapgrowthlimit, 256 МиБ на телефоне пользователя) и нативная память
 * отдельно; пики — опросом каждые 10 мс, удержанное — после сборки мусора.
 *
 * Файл кладёт стенд: <external files>/NOX/Media/long15h.mp4 (настоящие
 * таблицы сэмплов 15-часового H.264 + AAC 48 кГц). Тяжёлый: только с
 * аргументом `nox.long=1`. Отчёт: <external files>/longsrc/report-memory.txt.
 *
 * `nox.ballastMb=N` — до открытия занять N МиБ Java-кучи (как прочие данные
 * процесса на телефоне пользователя: в его отчёте в момент отказа было занято
 * 251 из 256 МиБ). Так сравниваются старый и новый разбор при одинаковой
 * нагрузке.
 */
@RunWith(AndroidJUnit4::class)
class LongVideoMemoryDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val app get() = NoxApp.get(ctx)
    private fun arg(k: String): String? = InstrumentationRegistry.getArguments().getString(k)
    private val report = StringBuilder()
    private val ballast = ArrayList<ByteArray>()

    private fun say(s: String) {
        Log.i(TAG, s)
        report.append(s).append('\n')
    }

    private fun mib(b: Long) = String.format(Locale.US, "%.1f", b / 1_048_576.0)

    private data class Mem(val java: Long, val native: Long)

    private fun now(gc: Boolean): Mem {
        if (gc) repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(200) }
        val r = Runtime.getRuntime()
        return Mem(r.totalMemory() - r.freeMemory(), Debug.getNativeHeapAllocatedSize())
    }

    /** Пики за этап: опрос каждые 10 мс в отдельном потоке. */
    private class Peaks : Thread("nox-mem-peaks") {
        @Volatile var java = 0L
        @Volatile var native = 0L
        override fun run() {
            val r = Runtime.getRuntime()
            while (!isInterrupted) {
                java = maxOf(java, r.totalMemory() - r.freeMemory())
                native = maxOf(native, Debug.getNativeHeapAllocatedSize())
                try { sleep(10) } catch (_: InterruptedException) { break }
            }
        }
    }

    private class Snap(val state: Int, val error: Throwable?, val positionMs: Long, val durationMs: Long)

    private fun snap(): Snap {
        var s: Snap? = null
        instr.runOnMainSync {
            val p = app.playback.player()
            s = Snap(p.playbackState, p.playerError, p.currentPosition, p.duration)
        }
        return s!!
    }

    /** Ждать готовности (или ошибки). Возвращает итог этапа одной строкой. */
    private fun waitReady(stage: String, base: Mem, timeoutMs: Long = 600_000): Boolean {
        val peaks = Peaks().apply { start() }
        val t0 = System.currentTimeMillis()
        var last: Snap
        while (true) {
            last = snap()
            if (last.error != null || last.state == Player.STATE_READY) break
            if (System.currentTimeMillis() - t0 > timeoutMs) break
            Thread.sleep(50)
        }
        peaks.interrupt(); peaks.join()
        val ms = System.currentTimeMillis() - t0
        val held = now(gc = true)
        val ok = last.error == null && last.state == Player.STATE_READY
        say("$stage: ${if (ok) "готов" else if (last.error != null) "ОШИБКА" else "не готов за $timeoutMs мс"} за $ms мс; " +
            "позиция ${last.positionMs / 1000} с из ${last.durationMs / 1000} с; " +
            "пик Java +${mib(peaks.java - base.java)} МиБ (всего ${mib(peaks.java)}), пик native +${mib(peaks.native - base.native)} МиБ; " +
            "удержано после GC: Java +${mib(held.java - base.java)} МиБ, native +${mib(held.native - base.native)} МиБ")
        last.error?.let { e ->
            say("  ошибка: " + generateSequence(e as Throwable) { it.cause }.take(5).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" })
        }
        return ok
    }

    private fun play(seconds: Int): Long {
        instr.runOnMainSync { app.playback.player().playWhenReady = true }
        val start = snap().positionMs
        Thread.sleep(seconds * 1000L)
        instr.runOnMainSync { app.playback.player().playWhenReady = false }
        return snap().positionMs - start
    }

    private fun seek(ms: Long) = instr.runOnMainSync { app.playback.player().seekTo(ms) }

    /** Загрузки источника за этап: начатые, законченные, ошибки (с причиной) — повторы видны здесь. */
    private class Loads : AnalyticsListener {
        @Volatile var started = 0
        @Volatile var completed = 0
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun onLoadStarted(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) { started++ }
        override fun onLoadCompleted(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) { completed++ }
        override fun onLoadError(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
            if (errors.size < 5) errors += generateSequence(error as Throwable) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            else if (errors.size == 5) errors += "…"
        }
    }

    private fun runCase(label: String, m: MediaEntity, base: Mem): List<String> {
        val failures = ArrayList<String>()
        say("--- $label")
        val loads = Loads()
        instr.runOnMainSync { app.playback.player().addAnalyticsListener(loads) }
        NoxLog.clear()
        try {
            failures += runSteps(label, m, base, loads)
        } finally {
            instr.runOnMainSync { app.playback.player().removeAnalyticsListener(loads); app.playback.close() }
            say("$label: загрузок источника ${loads.started}, закончено ${loads.completed}, ошибок ${loads.errors.size}" +
                loads.errors.joinToString("") { "\n  ошибка загрузки: $it" })
            NoxLog.dump().filter { it.contains("mp4-large") }.forEach { say("  журнал: $it") }
        }
        val closed = now(gc = true)
        say("$label: после закрытия: Java +${mib(closed.java - base.java)} МиБ, native +${mib(closed.native - base.native)} МиБ")
        return failures
    }

    private fun runSteps(label: String, m: MediaEntity, base: Mem, loads: Loads): List<String> {
        val failures = ArrayList<String>()
        instr.runOnMainSync { app.playback.open(m.id) }
        if (!waitReady("$label: открытие с 270 000 мс", base)) { failures += "$label: открытие"; return failures }
        val moved = play(8)
        say("$label: за 8 с воспроизведения позиция ушла на ${moved} мс")
        if (moved < 1000) failures += "$label: воспроизведение не идёт"
        seek(27_000_000)
        if (!waitReady("$label: перемотка к середине (7:30:00)", base)) failures += "$label: середина"
        seek(54_100_000)
        if (!waitReady("$label: перемотка к концу (15:01:40)", base)) failures += "$label: конец"
        if (loads.errors.isNotEmpty()) failures += "$label: ошибки загрузки"
        return failures
    }

    @Test
    fun longVideoOpensAndMemoryReturnsAfterClose(): Unit = runBlocking {
        assumeTrue("длинное видео — только по запросу (nox.long=1)", arg("nox.long") == "1")
        val file = File(ctx.getExternalFilesDir(null), "NOX/Media/long15h.mp4")
        assumeTrue("нет файла стенда ${file.absolutePath}", file.exists())
        val out = File(ctx.getExternalFilesDir(null), "longsrc").apply { mkdirs() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val failures = ArrayList<String>()
        val ids = ArrayList<Long>()
        try {
            Thread.sleep(5000)
            val r = Runtime.getRuntime()
            say("лимит Java-кучи процесса (maxMemory): ${mib(r.maxMemory())} МиБ; файл ${file.length()} байт")
            val db = app.db
            fun entity(title: String, path: String, uri: String) = MediaEntity(title = title, filePath = path, contentUri = uri,
                sizeBytes = file.length(), quality = "720p", height = 72, width = 128, durationSec = 54_193,
                createdAt = System.currentTimeMillis(), container = "mp4", codecs = "H.264 + AAC", kind = "video", imported = true)
            val byFile = db.media().insert(entity("Длинный (файл)", file.absolutePath, "")).also { ids += it }
            val shared = MediaLocator.shareUri(ctx, db.media().get(byFile)!!)
            val byUri = db.media().insert(entity("Длинный (content://)", "", shared.toString())).also { ids += it }
            for (id in ids) db.playback().upsert(PlaybackEntity(id, 270_000, 54_193_000, System.currentTimeMillis()))

            val ballastMb = arg("nox.ballastMb")?.toIntOrNull() ?: 0
            repeat(ballastMb) { ballast += ByteArray(1 shl 20) { 1 } }
            val base = now(gc = true)
            say("исходно (NOX на экране, видео не открыто${if (ballastMb > 0) ", занято ещё $ballastMb МиБ балласта" else ""}): " +
                "Java ${mib(base.java)} МиБ, native ${mib(base.native)} МиБ")

            failures += runCase("файл", db.media().get(byFile)!!, base)
            failures += runCase("content://", db.media().get(byUri)!!, base)

            say("--- повторные открытия (файл), 3 раза")
            repeat(3) { i ->
                instr.runOnMainSync { app.playback.open(byFile) }
                if (!waitReady("повтор ${i + 1}", base)) failures += "повтор ${i + 1}"
                instr.runOnMainSync { app.playback.close() }
                val c = now(gc = true)
                say("  после закрытия ${i + 1}: Java +${mib(c.java - base.java)} МиБ")
            }

            say("--- «Проверить файл», затем воспроизведение")
            val peaks = Peaks().apply { start() }
            val t0 = System.currentTimeMillis()
            val check = runCatching { FileChecks.check(app, db.media().get(byFile)!!, CheckControl.NONE) }
            peaks.interrupt(); peaks.join()
            val afterCheck = now(gc = true)
            say("проверка: ${check.getOrNull()?.verdict ?: "СБОЙ ${check.exceptionOrNull()}"} за ${System.currentTimeMillis() - t0} мс; " +
                "пик Java +${mib(peaks.java - base.java)} МиБ; удержано после GC +${mib(afterCheck.java - base.java)} МиБ")
            if (check.isFailure) failures += "проверка файла"
            instr.runOnMainSync { app.playback.open(byFile) }
            if (!waitReady("после проверки: открытие", base)) failures += "открытие после проверки"
            instr.runOnMainSync { app.playback.close() }

            val pb = db.playback().get(byFile)
            say("позиция в базе после всех закрытий: ${pb?.positionMs} мс (была 270 000), просмотрено: ${pb?.completed}")
        } finally {
            ballast.clear()
            File(out, "report-memory.txt").writeText(report.toString())
            instr.runOnMainSync { app.playback.close() }
            for (id in ids) { app.db.playback().delete(id); app.db.media().get(id)?.let { app.db.media().delete(it) } }
            scenario.close()
        }
        assertTrue("не прошло: $failures\n$report", failures.isEmpty())
    }

    companion object {
        private const val TAG = "NOX-LONG"
    }
}
