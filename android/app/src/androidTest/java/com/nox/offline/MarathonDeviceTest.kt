@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline

import android.app.Activity
import android.content.Intent
import android.os.Debug
import android.system.Os
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.media.CheckControl
import com.nox.offline.media.FileChecks
import com.nox.offline.media.SyntheticMarathon
import com.nox.offline.player.PlayerActivity
import com.nox.offline.storage.MediaLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 70-часовой MP4 (moov ≈217 МиБ в начале файла, ~18 млн сэмплов, настоящие
 * кадры H.264 720p + AAC) в настоящем NOX: медиатека → PlaybackHub →
 * PlayerActivity на экране. Файл создаётся на устройстве тем же генератором,
 * что и в тестах JVM, в <external files>/NOX/Media/ и после теста удаляется.
 *
 * Java-куча ограничивается 256 МиБ, как у телефона пользователя: если предел
 * процесса больше, до открытия занимается балласт (maxMemory − 256 МиБ).
 * Отчёт — в logcat с тегом NOX-TEST (CI сохраняет его в build-shots/nox-test.log).
 * Отключить: аргумент `nox.marathon=0`.
 */
@RunWith(AndroidJUnit4::class)
class MarathonDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val app get() = NoxApp.get(ctx)
    private fun arg(k: String): String? = InstrumentationRegistry.getArguments().getString(k)
    private val failures = ArrayList<String>()
    private val ballast = ArrayList<ByteArray>()

    private fun say(s: String) { Log.i("NOX-TEST", "marathon: $s") }
    private fun mib(b: Long) = String.format(Locale.US, "%.1f", b / 1_048_576.0)
    private fun check(ok: Boolean, what: String) { if (!ok) { failures += what; say("НЕ ПРОШЛО: $what") } }

    private data class Mem(val java: Long, val native: Long)

    private fun mem(gc: Boolean): Mem {
        if (gc) repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(150) }
        val r = Runtime.getRuntime()
        return Mem(r.totalMemory() - r.freeMemory(), Debug.getNativeHeapAllocatedSize())
    }

    private class Peaks : Thread("nox-marathon-peaks") {
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

    private class Counters : AnalyticsListener {
        @Volatile var firstFrames = 0
        @Volatile var loadErrors = 0
        override fun onRenderedFirstFrame(t: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) { firstFrames++ }
        override fun onLoadError(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
            loadErrors++
            Log.i("NOX-TEST", "marathon: ошибка загрузки: ${generateSequence(error as Throwable) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }}")
        }
    }

    private class Snap(val state: Int, val error: Throwable?, val positionMs: Long, val durationMs: Long, val video: Int, val audio: Int,
                       val videoFormat: String?, val audioFormat: String?)

    private fun snap(): Snap {
        var s: Snap? = null
        instr.runOnMainSync {
            val p = app.playback.player()
            p.videoDecoderCounters?.ensureUpdated()
            p.audioDecoderCounters?.ensureUpdated()
            s = Snap(p.playbackState, p.playerError, p.currentPosition, p.duration,
                p.videoDecoderCounters?.renderedOutputBufferCount ?: 0, p.audioDecoderCounters?.renderedOutputBufferCount ?: 0,
                p.videoFormat?.let { "${it.sampleMimeType} ${it.width}x${it.height}" }, p.audioFormat?.sampleMimeType)
        }
        return s!!
    }

    /** Ждать готовности; пики памяти за этап. */
    private fun waitReady(stage: String, base: Mem, timeoutMs: Long = 180_000): Boolean {
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
        val ok = last.error == null && last.state == Player.STATE_READY
        say("$stage: ${if (ok) "готов" else if (last.error != null) "ОШИБКА" else "не готов"} за $ms мс; позиция ${last.positionMs / 1000} с " +
            "из ${last.durationMs / 1000} с; пик Java ${mib(peaks.java)} МиБ (+${mib(peaks.java - base.java)}), " +
            "пик native +${mib(peaks.native - base.native)} МиБ")
        last.error?.let { e -> say("  ошибка: " + generateSequence(e as Throwable) { it.cause }.take(5).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }) }
        check(ok, "$stage: готовность")
        return ok
    }

    /** Играть [seconds] с: позиция должна идти, кадры и звук — выводиться. */
    private fun play(stage: String, seconds: Int, video: Boolean = true) {
        instr.runOnMainSync { app.playback.player().playWhenReady = true }
        val a = snap()
        Thread.sleep(seconds * 1000L)
        val b = snap()
        instr.runOnMainSync { app.playback.player().playWhenReady = false }
        say("$stage: за $seconds с позиция ${a.positionMs / 1000} → ${b.positionMs / 1000} с; кадров выведено +${b.video - a.video}, " +
            "звуковых буферов +${b.audio - a.audio}; видео ${b.videoFormat}, звук ${b.audioFormat}")
        check(b.positionMs > a.positionMs, "$stage: позиция не идёт")
        check(b.audio > a.audio, "$stage: звук не выводится")
        if (video) check(b.video > a.video, "$stage: кадры не выводятся")
    }

    private fun seek(stage: String, ms: Long, base: Mem) {
        instr.runOnMainSync { app.playback.player().seekTo(ms) }
        if (waitReady(stage, base)) {
            val pos = snap().positionMs
            check(pos in ms - 2_500..ms + 1_000, "$stage: позиция $pos вместо $ms")
            play(stage, 4)
        }
    }

    /** Открытые дескрипторы процесса на файл [name] (по /proc/self/fd). */
    private fun fds(name: String): Int = File("/proc/self/fd").listFiles()?.count { f ->
        runCatching { Os.readlink(f.path).endsWith(name) }.getOrDefault(false)
    } ?: -1

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resumed(): Activity? {
        var a: Activity? = null
        instr.runOnMainSync { a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
        return a
    }

    private fun waitActivity(cls: Class<*>, timeoutMs: Long = 20_000): Activity? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            var found: Activity? = null
            instr.runOnMainSync {
                val reg = ActivityLifecycleMonitorRegistry.getInstance()
                found = (reg.getActivitiesInStage(Stage.RESUMED) + reg.getActivitiesInStage(Stage.PAUSED)).firstOrNull { cls.isInstance(it) }
            }
            if (found != null) return found
            Thread.sleep(100)
        }
        return null
    }

    @Test
    fun marathon70hPlaysInNoxWithin256MiB(): Unit = runBlocking {
        assumeTrue("отключено аргументом nox.marathon=0", arg("nox.marathon") != "0")
        val dir = File(ctx.getExternalFilesDir(null), "NOX/Media").apply { mkdirs() }
        val file = File(dir, "marathon70h.mp4")
        val clip = instr.context.assets.open("media/marathon-clip.mp4").use { it.readBytes() }
        var t0 = System.currentTimeMillis()
        val layout = SyntheticMarathon(clip).write(file, SyntheticMarathon.Options())
        say("файл: ${file.length()} байт, moov ${layout.moovSize} байт (${layout.moovSize / 1_048_576} МиБ) в начале, " +
            "${layout.videoSamples}+${layout.audioSamples} сэмплов, ${layout.durationUs / 1_000_000} с; создан за ${System.currentTimeMillis() - t0} мс")
        val shaBefore = sha256(file)
        val sizeBefore = file.length()
        val mtimeBefore = file.lastModified()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val counters = Counters()
        val ids = ArrayList<Long>()
        try {
            Thread.sleep(3000)
            val rt = Runtime.getRuntime()
            say("устройство: ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.SUPPORTED_ABIS.first()}; " +
                "предел Java-кучи процесса ${mib(rt.maxMemory())} МиБ")
            val db = app.db
            val durSec = layout.durationUs / 1_000_000
            fun entity(title: String, path: String, uri: String) = MediaEntity(title = title, filePath = path, contentUri = uri,
                sizeBytes = file.length(), quality = "720p", height = 720, width = 1280, durationSec = durSec,
                createdAt = System.currentTimeMillis(), container = "mp4", codecs = "H.264 + AAC", kind = "video", imported = true)
            val byFile = db.media().insert(entity("Марафон 70 ч (файл)", file.absolutePath, "")).also { ids += it }
            val shared = MediaLocator.shareUri(ctx, db.media().get(byFile)!!)
            val byUri = db.media().insert(entity("Марафон 70 ч (content://)", "", shared.toString())).also { ids += it }
            db.playback().upsert(PlaybackEntity(byFile, 270_000, durSec * 1000, System.currentTimeMillis()))
            db.playback().upsert(PlaybackEntity(byUri, 48 * 3_600_000L + 5_000, durSec * 1000, System.currentTimeMillis()))

            // Предел 256 МиБ, как на телефоне пользователя.
            val ballastMb = ((rt.maxMemory() - (256L shl 20)) shr 20).toInt().coerceAtLeast(0)
            repeat(ballastMb) { ballast += ByteArray(1 shl 20) { 1 } }
            instr.runOnMainSync { app.playback.player().addAnalyticsListener(counters) }
            val base = mem(gc = true)
            say("до открытия: Java ${mib(base.java)} МиБ (балласт $ballastMb МиБ, свободно до предела ${mib(rt.maxMemory() - base.java)} МиБ), " +
                "native ${mib(base.native)} МиБ")

            // 1. file://, с сохранённой позиции 270 с, на весь экран
            NoxLog.clear()
            t0 = System.currentTimeMillis()
            instr.runOnMainSync { app.playback.open(byFile, play = false) }
            if (waitReady("файл: открытие с сохранённых 270 с", base)) {
                say("файл: подготовка до готовности ${System.currentTimeMillis() - t0} мс")
                ctx.startActivity(PlayerActivity.fullscreen(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                check(waitActivity(PlayerActivity::class.java) != null, "полный экран не открылся")
                Thread.sleep(1500)
                play("файл: воспроизведение с 270 с на весь экран", 6)
                check(counters.firstFrames > 0, "первый кадр не выведен")
                say("первых кадров выведено: ${counters.firstFrames}; после первого кадра: Java ${mib(mem(false).java)} МиБ")
                seek("файл: середина (34:54:00)", layout.durationUs / 2000, base)
                seek("файл: после 24 ч (24:00:03)", 24 * 3_600_000L + 3_000, base)
                seek("файл: после 48 ч (48:00:01)", 48 * 3_600_000L + 1_300, base)
                seek("файл: 99 %", layout.durationUs / 1000 * 99 / 100, base)
                seek("файл: назад к 1:00:00", 3_600_000L, base)
                seek("файл: начало", 0, base)
            }
            NoxLog.dump().filter { it.contains("mp4-") }.take(8).forEach { say("  журнал: $it") }
            instr.runOnMainSync { app.playback.close() }
            resumed()?.takeIf { it is PlayerActivity }?.let { a -> instr.runOnMainSync { a.finish() } }
            Thread.sleep(1500)
            var closed = mem(gc = true)
            say("после закрытия: Java +${mib(closed.java - base.java)} МиБ, native +${mib(closed.native - base.native)} МиБ; " +
                "дескрипторов на файл: ${fds(file.name)}")
            check(fds(file.name) == 0, "после закрытия остались дескрипторы на файл")

            // 2. content:// (FileProvider: openTypedAssetFileDescriptor, как у документа SAF), сохранённая позиция после 48 ч
            NoxLog.clear()
            instr.runOnMainSync { app.playback.open(byUri, play = false) }
            if (waitReady("content://: открытие с сохранённой позиции 48:00:05", base)) {
                val pos = snap().positionMs
                check(pos in 48 * 3_600_000L..48 * 3_600_000L + 6_000, "content://: позиция $pos вместо 48:00:05")
                play("content://: воспроизведение после 48 ч", 5, video = false)
                seek("content://: 99 %", layout.durationUs / 1000 * 99 / 100, base)
                seek("content://: назад к 0:10:00", 600_000L, base)
            }
            NoxLog.dump().filter { it.contains("mp4-") }.take(4).forEach { say("  журнал: $it") }
            instr.runOnMainSync { app.playback.close() }

            // 3. С начала (не с сохранённой позиции)
            instr.runOnMainSync { app.playback.open(byFile, fromStart = true, play = false) }
            if (waitReady("файл: открытие с начала", base)) {
                check(snap().positionMs < 1_000, "с начала: позиция ${snap().positionMs}")
                play("файл: с начала", 4, video = false)
            }
            instr.runOnMainSync { app.playback.close() }

            // 4. Отмена подготовки: закрыть, не дождавшись готовности
            instr.runOnMainSync { app.playback.open(byFile, play = false) }
            Thread.sleep(150)
            instr.runOnMainSync { app.playback.close() }
            Thread.sleep(2000)
            closed = mem(gc = true)
            say("отмена подготовки: Java +${mib(closed.java - base.java)} МиБ; дескрипторов на файл: ${fds(file.name)}")
            check(fds(file.name) == 0, "после отмены остались дескрипторы на файл")

            // 5. Повторные открытия: память не копится
            val after = ArrayList<String>()
            repeat(3) { i ->
                instr.runOnMainSync { app.playback.open(byFile, play = false) }
                waitReady("повтор ${i + 1}", base)
                instr.runOnMainSync { app.playback.player().seekTo(layout.durationUs / 3000) }
                waitReady("повтор ${i + 1}: перемотка", base)
                instr.runOnMainSync { app.playback.close() }
                after += mib(mem(gc = true).java - base.java)
            }
            say("повторные открытия: удержано после закрытия Java +${after.joinToString(" / +")} МиБ")
            check(after.last().toDouble() - after.first().toDouble() < 4.0, "повторные открытия копят память: $after")

            // 6. «Картинка в картинке»
            instr.runOnMainSync { app.playback.open(byFile, play = true) }
            if (waitReady("картинка в картинке: открытие", base)) {
                ctx.startActivity(PlayerActivity.fullscreen(ctx, pip = true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val pa = waitActivity(PlayerActivity::class.java)
                Thread.sleep(4000)
                var inPip = false
                instr.runOnMainSync { inPip = pa?.isInPictureInPictureMode == true }
                say("картинка в картинке: ${if (inPip) "включена" else "не включилась"}")
                check(inPip, "картинка в картинке не включилась")
                play("картинка в картинке", 4)
                pa?.let { a -> instr.runOnMainSync { a.finish() } }
            }
            instr.runOnMainSync { app.playback.close() }
            Thread.sleep(1000)

            // 7. Виртуальная серия внутри файла (30:00:00–31:00:00) и «Только звук»
            val part = db.chapters().insert(ChapterEntity(mediaId = byFile, title = "Часть 31", startMs = 30 * 3_600_000L,
                endMs = 31 * 3_600_000L, kind = ChapterKind.EPISODE, createdAt = System.currentTimeMillis()))
            instr.runOnMainSync { app.playback.open(byFile, chapterId = part, play = false) }
            if (waitReady("серия «Часть 31» (с 30:00:00)", base)) {
                var abs = 0L
                instr.runOnMainSync { abs = app.playback.state.value.absoluteMs }
                say("серия: абсолютная позиция ${abs / 1000} с")
                check(abs in 30 * 3_600_000L - 2_500..30 * 3_600_000L + 2_000, "серия: абсолютная позиция $abs")
                play("серия «Часть 31»", 4)
                instr.runOnMainSync { app.playback.setListen(true) }
                Thread.sleep(2500)
                play("«Только звук»", 4, video = false)
                val v = snap().videoFormat
                say("«Только звук»: видеодорожка ${v ?: "выключена"}")
                check(v == null, "«Только звук»: видео не выключено")
                instr.runOnMainSync { app.playback.setListen(false) }
            }
            instr.runOnMainSync { app.playback.close() }
            db.chapters().delete(part)

            // 8. «Проверить файл» тем же путём, затем воспроизведение
            val peaks = Peaks().apply { start() }
            t0 = System.currentTimeMillis()
            val result = runCatching { FileChecks.check(app, db.media().get(byFile)!!, CheckControl.NONE) }
            peaks.interrupt(); peaks.join()
            val c = result.getOrNull()
            say("«Проверить файл»: ${c?.verdict ?: "СБОЙ ${result.exceptionOrNull()}"} за ${System.currentTimeMillis() - t0} мс; " +
                "пик Java ${mib(peaks.java)} МиБ; ${c?.summary?.take(300)}")
            c?.details?.filter { it.startsWith("разборщик плеера") }?.forEach { say("  $it") }
            c?.limits?.forEach { say("  не проверялось: $it") }
            check(c != null, "«Проверить файл» упал")
            check(c?.details?.any { it.startsWith("разборщик плеера, конец") } == true, "«Проверить файл»: разборщик плеера не дошёл до конца")
            check(c?.details?.none { it.contains("остановился") } == true, "«Проверить файл»: разборщик плеера остановился")
            instr.runOnMainSync { app.playback.open(byFile, play = false) }
            if (waitReady("после проверки: открытие", base)) play("после проверки", 4, video = false)
            instr.runOnMainSync { app.playback.close() }

            check(counters.loadErrors == 0, "ошибки загрузки источника: ${counters.loadErrors}")
            val pb = db.playback().get(byFile)
            say("позиция файла в базе после всех закрытий: ${pb?.positionMs} мс")
        } finally {
            ballast.clear()
            instr.runOnMainSync { app.playback.player().removeAnalyticsListener(counters); app.playback.close() }
            for (id in ids) { app.db.playback().delete(id); app.db.media().get(id)?.let { app.db.media().delete(it) } }
            scenario.close()
        }
        val shaAfter = sha256(file)
        say("исходный файл: SHA-256 до ${shaBefore.take(16)}…, после ${shaAfter.take(16)}…; размер ${file.length()} (был $sizeBefore); " +
            "время изменения ${if (file.lastModified() == mtimeBefore) "то же" else "ИЗМЕНИЛОСЬ"}")
        check(shaAfter == shaBefore && file.length() == sizeBefore && file.lastModified() == mtimeBefore, "исходный файл изменился")
        file.delete()
        assertTrue("не прошло: $failures", failures.isEmpty())
    }
}
