package com.nox.offline.updates

import android.accessibilityservice.AccessibilityService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.MainActivity
import com.nox.offline.NoxApp
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Встроенное скачивание APK на устройстве — со стендом стенда (локальный
 * HTTPS-сервер хоста, эмулятор видит его как 10.0.2.2; сервер режет
 * скорость, рвёт соединение, отвечает 200 вместо 206 и т. п.).
 *
 * Тест нажимает «Обновить» на экране NOX и дальше ведёт себя как человек:
 * сворачивает NOX, гасит и будит экран, на время пропадает сеть (режим
 * полёта). Каждую секунду в отчёт пишется, что показывает NOX. На
 * [ARG_LEAVE_AT] доле файла тест заканчивается, не дожидаясь конца:
 * запуск `am instrument --no-restart` оставляет процесс и задание жить —
 * дальше стенд убивает процесс и смотрит, продолжится ли передача сама.
 *
 * Только по запросу (`nox.update=1`); адрес, размер, SHA-256 и versionCode
 * файла — аргументами; `nox.update.steps=home,screen,net,cancel` — какие
 * помехи устроить. Отчёт: <external files>/updsrc/report-update.txt.
 */
@RunWith(AndroidJUnit4::class)
class UpdateDownloadDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val app get() = NoxApp.get(ctx)
    private fun arg(k: String): String? = InstrumentationRegistry.getArguments().getString(k)
    private val reportFile by lazy { File(File(ctx.getExternalFilesDir(null), "updsrc").apply { mkdirs() }, "report-update.txt") }
    private val t0 = System.currentTimeMillis()

    private fun say(s: String) {
        val line = String.format(Locale.US, "%6.1f с  %s", (System.currentTimeMillis() - t0) / 1000.0, s)
        Log.i(TAG, line)
        runCatching { reportFile.appendText(line + "\n") }
    }

    private fun shell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }.trim()
    }

    private fun visible() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun describe(s: UpdateState): String = when (s) {
        is UpdateState.Downloading -> "${s.phase}: ${UpdateScheduler.downloadLine(s)}" + (if (s.note.isNotBlank()) " [${s.note}]" else "")
        is UpdateState.Failed -> "Failed: ${s.message}"
        else -> s.javaClass.simpleName
    }

    private fun fraction(s: UpdateState): Double =
        if (s is UpdateState.Downloading && s.total > 0) s.done.toDouble() / s.total else if (s is UpdateState.Downloading) 0.0 else 1.0

    @Test
    fun downloadWhileMinimisedLockedAndOffline() {
        assumeTrue("скачивание обновления — только по запросу (nox.update=1)", arg("nox.update") == "1")
        val manifest = UpdateManifest.parse(JSONObject()
            .put("format", UpdateManifest.FORMAT).put("formatVersion", 1)
            .put("applicationId", ctx.packageName).put("versionName", arg("nox.update.name") ?: "test")
            .put("versionCode", arg("nox.update.vc")!!.toInt()).put("minSdk", 26)
            .put("apk", JSONObject().put("name", "nox.apk").put("url", arg("nox.update.url"))
                .put("size", arg("nox.update.size")!!.toLong()).put("sha256", arg("nox.update.sha")))
            .toString())
        if (arg("nox.update.fresh") == "1") {
            // NOX сам продолжает сохранённое задание, когда оказывается на экране: сначала
            // остановить его, потом удалять файлы (иначе передача пишет в удалённую часть).
            instr.runOnMainSync { app.updates.cancelDownload() }
            Thread.sleep(2000)
            File(ctx.filesDir, "updates").deleteRecursively()
        }
        reportFile.delete()
        val leaveAt = arg(ARG_LEAVE_AT)?.toDoubleOrNull() ?: 0.5
        // Какие помехи устроить: home — свернуть, screen — погасить экран, net — режим полёта,
        // cancel — «Отменить» и снова «Обновить» (только по имени).
        val steps = (arg("nox.update.steps") ?: "home,screen,net").split(',').map { it.trim() }.toSet()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(3000)
        instr.runOnMainSync { app.updates.download(manifest) }
        say("«Обновить» нажато на экране NOX; носитель: ${if (UpdateScheduler.isScheduled(ctx)) "задание UIDT в JobScheduler" else "не в JobScheduler"}")

        var last = ""
        var lastLine = 0L
        var home = false
        var sleepAt = 0L
        var woke = false
        var netOffAt = 0L
        var netBack = false
        var cancelled = false
        try {
            while (true) {
                val s = app.updates.state.value
                val now = System.currentTimeMillis()
                val d = describe(s)
                val key = d.substringBefore(':')
                if (key != last || now - lastLine >= 2000) {
                    say("${if (visible()) "на экране" else "свёрнут"} · $d")
                    last = key
                    lastLine = now
                }
                val f = fraction(s)
                if (s !is UpdateState.Downloading && s !is UpdateState.Verifying) { say("итог: $d"); break }
                if (!cancelled && "cancel" in steps && f >= 0.15) {
                    // «Отменить», потом снова «Обновить»: часть остаётся, продолжение — с неё.
                    cancelled = true
                    instr.runOnMainSync { app.updates.cancelDownload() }
                    Thread.sleep(3000)
                    val part = File(File(ctx.filesDir, "updates"), "NOX-${manifest.versionCode}.apk.part").length()
                    say("--- «Отменить»: состояние ${app.updates.state.value.javaClass.simpleName}, часть на диске $part байт")
                    Thread.sleep(5000)
                    instr.runOnMainSync { app.updates.download(manifest) }
                    say("--- снова «Обновить»")
                }
                if (!home && "home" in steps && f >= 0.08) {
                    home = true
                    instr.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    say("--- нажата «Домой»: NOX свёрнут")
                }
                if (home && "screen" in steps && sleepAt == 0L && f >= 0.2) {
                    sleepAt = now
                    shell("input keyevent 223")
                    say("--- экран погашен")
                }
                if (sleepAt > 0 && !woke && now - sleepAt >= 20_000) {
                    woke = true
                    shell("input keyevent 224")
                    say("--- экран включён (NOX по-прежнему свёрнут)")
                }
                if ((woke || "screen" !in steps) && "net" in steps && netOffAt == 0L && f >= 0.35) {
                    netOffAt = now
                    shell("cmd connectivity airplane-mode enable")
                    say("--- сеть пропала (режим полёта)")
                }
                if (netOffAt > 0 && !netBack && now - netOffAt >= 45_000) {
                    netBack = true
                    shell("cmd connectivity airplane-mode disable")
                    say("--- сеть вернулась")
                }
                if ((netBack || "net" !in steps) && f >= leaveAt) {
                    say("--- тест заканчивается на ${(f * 100).toInt()} %: процесс и задание остаются")
                    break
                }
                Thread.sleep(500)
            }
        } finally {
            if (netOffAt > 0 && !netBack) shell("cmd connectivity airplane-mode disable")
            say("диагностика:\n" + app.updates.diagnostics())
            scenario.close()
        }
    }

    companion object {
        private const val TAG = "NOX-UPD"
        const val ARG_LEAVE_AT = "nox.update.leaveAt"
    }
}
