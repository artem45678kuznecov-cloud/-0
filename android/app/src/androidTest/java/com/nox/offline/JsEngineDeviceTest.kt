package com.nox.offline

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.nox.offline.js.JsEngine
import com.nox.offline.js.JsEngineException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Встроенный JS-движок в установленном APK: изоляция, пределы и настоящий
 * путь yt-dlp → провайдер NOX → QuickJS-NG (libnoxjs.so) для задач YouTube.
 */
@RunWith(AndroidJUnit4::class)
class JsEngineDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun python(): Python {
        synchronized(JsEngineDeviceTest::class.java) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        }
        return Python.getInstance()
    }

    @Test fun engineLoadsForThisAbi() {
        assertTrue("libnoxjs.so для ${Build.SUPPORTED_ABIS.joinToString()}", JsEngine.available())
        assertTrue(JsEngine.version().startsWith("0.17"))
        assertEquals("2\n", JsEngine.run("console.log(1 + 1)"))
        assertEquals("ё 😀 é\n", JsEngine.run("console.log('ё', '😀', 'é')"))
    }

    @Test fun scriptHasNoFilesNetworkOrHostApi() {
        val probes = listOf("typeof require", "typeof std", "typeof os", "typeof fetch", "typeof XMLHttpRequest",
            "typeof setTimeout", "typeof print", "typeof java", "typeof Android", "typeof process")
        for (p in probes) assertEquals(p, "undefined\n", JsEngine.run("console.log($p)"))
    }

    @Test fun limitsStopRunawayScripts() {
        fun expect(status: Int, script: String, limits: JsEngine.Limits) {
            try {
                JsEngine.run(script, limits)
                fail("ожидался отказ $status")
            } catch (e: JsEngineException) {
                assertEquals(e.message, status, e.status)
            }
        }
        val started = System.currentTimeMillis()
        expect(2, "for(;;){}", JsEngine.Limits(timeoutMs = 500))
        assertTrue("время ${System.currentTimeMillis() - started}", System.currentTimeMillis() - started < 10_000)
        expect(3, "let a=[]; for(;;) a.push(new Array(100000).fill(1))", JsEngine.Limits(memory = 32L shl 20))
        expect(4, "let s='x'; for(;;){ s+=s; console.log(s) }", JsEngine.Limits(output = 1L shl 20))
        expect(1, "throw new Error('boom')", JsEngine.Limits())
        // После отказов движок работает дальше.
        assertEquals("ok\n", JsEngine.run("console.log('ok')"))
    }

    @Test fun pythonSeesTheEmbeddedRuntime() {
        val status = JSONObject(python().getModule("resolver").callAttr("js_status").toString())
        assertTrue(status.toString(), status.getBoolean("ready"))
        assertTrue(status.toString(), status.getString("engine").startsWith("0.17"))
        assertEquals("2026.08.19", status.getJSONObject("versions").getString("yt_dlp"))
        assertEquals("0.8.0", status.getJSONObject("versions").getString("ejs"))
    }

    /** Файл, положенный в /data/local/tmp через adb, читается через shell инструментации. */
    private fun fromShell(path: String, target: File): File? {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        if (bytes.size < 100_000) return null
        target.parentFile!!.mkdirs()
        target.writeBytes(bytes)
        return target
    }

    /**
     * Настоящие задачи YouTube (n и sig) из набора тестов yt-dlp 2026.08.19
     * для плеера 74edf1a3. Файл плеера кладёт CI (скачан с youtube.com):
     * adb push … /data/local/tmp/nox-ejs/player-74edf1a3.js. Без него тест
     * пропускается, а не считается пройденным.
     */
    @Test fun ejsSolvesRealYoutubeChallengesInsideApk() {
        val player = fromShell("/data/local/tmp/nox-ejs/player-74edf1a3.js", File(ctx.cacheDir, "ejs/player-74edf1a3.js"))
        assumeTrue("нет файла плеера в /data/local/tmp/nox-ejs", player != null)
        player!!
        val sigIn = "NJAJEij0EwRgIhAI0KExTgjfPk-MPM9MAdzyyPRt=BM8-XO5tm5hlMCSVpAiEAv7eP3CURqZNSPow8BXXAoazVoXgeMP7gH9BdylHCwgw=gwzz"
        val url = "https://www.youtube.com/s/player/74edf1a3/player_ias.vflset/en_US/base.js"
        val cache = File(ctx.cacheDir, "ejs-test-cache").apply { deleteRecursively() }
        // Предел времени увеличен только для эмулятора без аппаратного ускорения (TCG),
        // где QuickJS в десятки раз медленнее телефона. Рабочий предел — JsEngine.TIMEOUT_MS.
        val nox = python().getModule("nox_jsc")
        val raw = nox.callAttr("solve_file", player.absolutePath, url,
            arrayOf("IlLiA21ny7gqA2m4p37", "eabGFpsUKuWHXGh6FR4"), arrayOf(sigIn), cache.absolutePath, 900_000).toString()
        val r = JSONObject(raw)
        assertTrue(raw, r.getBoolean("available"))
        assertEquals(raw, 1, r.getInt("runs"))
        assertEquals(raw, 0, r.getInt("failed"))
        assertEquals("9nRTxrbM1f0yHg", r.getJSONObject("n").getString("IlLiA21ny7gqA2m4p37"))
        assertEquals("izmYqDEY6kl7Sg", r.getJSONObject("n").getString("eabGFpsUKuWHXGh6FR4"))
        assertEquals("NJAJEij0EwRgIhAI0KExTgjfPk-MPM9MAdzyyPRt=BM8-XO5tm5hzMCSVpAiEAv7eP3CURqZNSPow8BXXAoazVoXgeMP7gH9BdylHCwgw=gwzl",
            r.getJSONObject("sig").getString(sigIn))
        // Второе решение — по разобранному плееру из кэша: заметно быстрее.
        val raw2 = nox.callAttr("solve_file", player.absolutePath, url,
            arrayOf("IlLiA21ny7gqA2m4p37"), arrayOf<String>(), cache.absolutePath, 900_000).toString()
        val r2 = JSONObject(raw2)
        assertEquals("9nRTxrbM1f0yHg", r2.getJSONObject("n").getString("IlLiA21ny7gqA2m4p37"))
        val first = r.getLong("ms")
        val second = r2.getLong("ms")
        assertTrue("кэш плеера: $first мс → $second мс", second * 3 < first)
        // Для отчёта: во сколько раз этот JS медленнее эталонного цикла (оценка скорости среды).
        val bench = JsEngine.run("let t=Date.now(),x=0;for(let i=0;i<20000000;i++){x=(x+i*7)%1000003}console.log(Date.now()-t)").trim()
        android.util.Log.i("NOX-TEST", "ejs-device first=${first}ms cached=${second}ms bench20M=${bench}ms abi=${Build.SUPPORTED_ABIS.first()}")
    }
}
