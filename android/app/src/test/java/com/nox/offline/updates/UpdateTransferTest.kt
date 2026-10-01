package com.nox.offline.updates

import com.nox.offline.downloader.HttpDownloader
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Передача APK обновления против MockWebServer: обрывы, отказ сервера,
 * сервер без Range, другой файл, новая версия, отсутствие сети, отмена —
 * и сравнение с логикой 0.4.2 на одном и том же файле.
 */
class UpdateTransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before fun start() { server = MockWebServer() }
    @After fun stop() { server.shutdown() }

    private fun data(size: Int) = ByteArray(size).also { Random(7).nextBytes(it) }

    /** Что сделать с [n]-м запросом за файлом (с 0) при смещении [offset]. */
    private sealed class Fault {
        /** Оборвать соединение, отдав [bytes] байт тела. */
        data class CutAfter(val bytes: Long) : Fault()
        data class Status(val code: Int) : Fault()
        /** Отдать весь файл с 200, не глядя на Range. */
        object IgnoreRange : Fault()
    }

    /**
     * Сервер как у GitHub: /download/NOX.apk отвечает 302 на «подписанную»
     * ссылку /blob?sig=…, та отдаёт файл с Range. [fault] — сбои по номеру запроса за файлом.
     */
    private fun github(file: ByteArray, bytesPerSecond: Long = 0, fault: (Int, Long) -> Fault? = { _, _ -> null }): Dispatcher =
        object : Dispatcher() {
            val n = AtomicInteger()
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path.startsWith("/download/")) {
                    return MockResponse().setResponseCode(302).setHeader("Location", "/blob?sig=secret${n.get()}&se=2026")
                }
                val i = n.getAndIncrement()
                val range = request.getHeader("Range")
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
                val f = fault(i, from)
                if (f is Fault.Status) return MockResponse().setResponseCode(f.code)
                val ignore = f is Fault.IgnoreRange || range == null
                val start = if (ignore) 0 else from.toInt()
                val body = Buffer().write(file, start, file.size - start)
                val r = MockResponse().setBody(body)
                if (ignore) r.setResponseCode(200) else r.setResponseCode(206).setHeader("Content-Range", "bytes $start-${file.size - 1}/${file.size}")
                if (bytesPerSecond > 0) r.throttleBody(bytesPerSecond / 10, 100, TimeUnit.MILLISECONDS)
                if (f is Fault.CutAfter) {
                    // Отдать часть и оборвать: «пропала сеть посреди передачи».
                    r.setBody(Buffer().write(file, start, f.bytes.toInt()))
                    r.setHeader("Content-Length", (file.size - start).toString())
                    r.socketPolicy = SocketPolicy.DISCONNECT_AT_END
                }
                return r
            }
        }

    private fun transfer(awaitNetwork: suspend () -> Boolean = { true }) =
        UpdateTransfer(client, sleep = { }, awaitNetwork = awaitNetwork)

    private fun url() = server.url("/download/NOX.apk").toString()

    @Test fun `plain transfer goes through the redirect and logs host without the signed query`() = runBlocking {
        val file = data(300_000)
        server.dispatcher = github(file)
        val part = File(tmp.root, "NOX.apk.part")
        val t = transfer()
        val r = t.run(url(), part, file.size.toLong(), refresh = { error("не нужен") }) { }
        assertEquals(UpdateTransfer.Result.Done, r)
        assertArrayEquals(file, part.readBytes())
        assertEquals(file.size.toLong(), t.currentStats.networkBytes)
        assertEquals(0, t.currentStats.repeatedBytes(file.size.toLong()))
        val log = t.currentStats.requests.joinToString("\n")
        assertTrue(log, log.contains("HTTP 302") && log.contains("HTTP 200"))
        assertFalse("подпись ссылки в журнал не попадает: $log", log.contains("secret"))
    }

    @Test fun `cut connection resumes with Range from the saved part, nothing is downloaded twice`() = runBlocking {
        val file = data(400_000)
        server.dispatcher = github(file) { i, _ -> if (i == 0) Fault.CutAfter(150_000) else null }
        val part = File(tmp.root, "NOX.apk.part")
        val t = transfer()
        val r = t.run(url(), part, file.size.toLong(), refresh = { error("не нужен") }) { }
        assertEquals(UpdateTransfer.Result.Done, r)
        assertArrayEquals(file, part.readBytes())
        assertEquals(2, t.currentStats.attempts)
        assertEquals(0, t.currentStats.repeatedBytes(file.size.toLong()))
        assertTrue(t.currentStats.requests.any { it.contains("Range bytes=150000-") })
    }

    @Test fun `server refusal keeps the part and continues with a fresh link`() = runBlocking {
        val file = data(300_000)
        server.dispatcher = github(file) { i, _ -> when (i) { 0 -> Fault.CutAfter(100_000); 1 -> Fault.Status(403); else -> null } }
        val part = File(tmp.root, "NOX.apk.part")
        var refreshed = 0
        val t = transfer()
        val r = t.run(url(), part, file.size.toLong(), refresh = { refreshed++; UpdateTransfer.Refresh.Same(url()) }) { }
        assertEquals(UpdateTransfer.Result.Done, r)
        assertEquals(1, refreshed)
        assertArrayEquals(file, part.readBytes())
        assertEquals("скачанные 100 000 байт не удалялись", 0, t.currentStats.repeatedBytes(file.size.toLong()))
    }

    @Test fun `server ignoring Range restarts from zero instead of appending a whole file`() = runBlocking {
        val file = data(200_000)
        server.dispatcher = github(file) { i, _ -> when (i) { 0 -> Fault.CutAfter(80_000); 1 -> Fault.IgnoreRange; else -> null } }
        val part = File(tmp.root, "NOX.apk.part")
        val t = transfer()
        val r = t.run(url(), part, file.size.toLong(), refresh = { error("не нужен") }) { }
        assertEquals(UpdateTransfer.Result.Done, r)
        assertArrayEquals(file, part.readBytes())
        assertEquals("повторены первые 80 000 байт", 80_000L, t.currentStats.repeatedBytes(file.size.toLong()))
    }

    @Test fun `different size at the source is a different file and is not appended`() = runBlocking {
        val file = data(100_000)
        server.dispatcher = github(file)
        val part = File(tmp.root, "NOX.apk.part")
        val r = transfer().run(url(), part, 99_000, refresh = { error("не нужен") }) { }
        assertTrue(r is UpdateTransfer.Result.Failed)
        assertFalse(part.exists())
    }

    @Test fun `newer release published during the download makes the job obsolete`() = runBlocking {
        val file = data(100_000)
        server.dispatcher = github(file) { _, _ -> Fault.Status(404) }
        val r = transfer().run(url(), File(tmp.root, "p"), file.size.toLong(), refresh = { UpdateTransfer.Refresh.Changed }) { }
        assertEquals(UpdateTransfer.Result.Obsolete, r)
    }

    @Test fun `no network stops the carrier instead of burning attempts`() = runBlocking {
        val r = transfer(awaitNetwork = { false }).run(url(), File(tmp.root, "p"), 10, refresh = { error("") }) { }
        assertEquals(UpdateTransfer.Result.NoNetwork, r)
        assertEquals(0, server.requestCount)
    }

    @Test fun `persistent failure without progress ends with an honest error, part kept`() = runBlocking {
        val file = data(100_000)
        server.dispatcher = github(file) { i, _ -> if (i == 0) Fault.CutAfter(10_000) else Fault.Status(500) }
        val part = File(tmp.root, "p")
        val phases = HashSet<UpdateTransfer.Phase>()
        val r = transfer().run(url(), part, file.size.toLong(), refresh = { error("") }) { phases += it.phase }
        assertTrue(r is UpdateTransfer.Result.Failed)
        assertEquals(10_000L, part.length())
        assertTrue("ожидание повтора показано: $phases", UpdateTransfer.Phase.RETRY_WAIT in phases)
    }

    @Test fun `cancel keeps the downloaded part`() = runBlocking {
        val file = data(2_000_000)
        server.dispatcher = github(file, bytesPerSecond = 400_000)
        val part = File(tmp.root, "p")
        val job = async(kotlinx.coroutines.Dispatchers.IO) {
            UpdateTransfer(client).run(url(), part, file.size.toLong(), refresh = { error("") }) { }
        }
        withTimeout(10_000) { while (part.length() < 300_000) delay(50) }
        job.cancel()
        runCatching { job.await() }
        assertTrue(part.length() >= 300_000)
        assertTrue(part.length() < file.size)
    }

    /**
     * До/после на одном файле: 0.4.2 (один вызов HttpDownloader без размера;
     * при любом сбое — стоп до нажатия «Повторить»; при 403 — удаление части)
     * против новой передачи. Сбои те же: обрыв на 40 % и отказ 403 на 70 %.
     */
    @Test fun `old and new update download on the same faults`() = runBlocking {
        val size = 6_000_000
        val file = data(size)
        // Каждый сбой — один раз: обрыв на 40 %, обрыв на 70 %, отказ 403 на 70 %.
        val cut40 = java.util.concurrent.atomic.AtomicBoolean()
        val cut70 = java.util.concurrent.atomic.AtomicBoolean()
        val refused = java.util.concurrent.atomic.AtomicBoolean()
        val faults: (Int, Long) -> Fault? = { _, offset ->
            when {
                offset == 0L && cut40.compareAndSet(false, true) -> Fault.CutAfter(size * 4L / 10)
                offset == size * 4L / 10 && cut70.compareAndSet(false, true) -> Fault.CutAfter(size * 3L / 10)
                offset == size * 7L / 10 && refused.compareAndSet(false, true) -> Fault.Status(403)
                else -> null
            }
        }
        // 0.4.2
        server.dispatcher = github(file, fault = faults)
        val oldPart = File(tmp.root, "old.part")
        val counting = CountingClient(client)
        val http = HttpDownloader(counting.client)
        var userRetries = 0
        while (true) {
            val out = http.download(url(), mapOf("Accept" to "application/vnd.android.package-archive"), oldPart) { _, _ -> }
            if (out is HttpDownloader.Outcome.Completed) break
            if (out is HttpDownloader.Outcome.Expired) oldPart.delete()       // так делал 0.4.2
            userRetries++                                                    // и ждал нажатия «Повторить»
            if (userRetries > 10) break
        }
        val oldNetwork = counting.bytes()
        assertArrayEquals(file, oldPart.readBytes())

        // 0.4.3
        server.shutdown()
        server = MockWebServer()
        cut40.set(false); cut70.set(false); refused.set(false)
        server.dispatcher = github(file, fault = faults)
        val newPart = File(tmp.root, "new.part")
        val t = transfer()
        val r = t.run(url(), newPart, size.toLong(), refresh = { UpdateTransfer.Refresh.Same(url()) }) { }
        assertEquals(UpdateTransfer.Result.Done, r)
        assertArrayEquals(file, newPart.readBytes())
        val report = "0.4.2: нажатий «Повторить» $userRetries, из сети $oldNetwork байт, повторно ${oldNetwork - size}; " +
            "0.4.3: нажатий 0, попыток ${t.currentStats.attempts}, из сети ${t.currentStats.networkBytes} байт, " +
            "повторно ${t.currentStats.repeatedBytes(size.toLong())}"
        println(report)
        File(System.getProperty("java.io.tmpdir"), "nox-update-compare.txt").writeText(report + "\n")
        assertEquals(3, userRetries)
        assertTrue("0.4.2 после 403 скачивал заново", oldNetwork - size >= size * 7L / 10)
        assertEquals(0, t.currentStats.repeatedBytes(size.toLong()))
    }

    /** Считает байты тел ответов — тот же учёт, что у UpdateTransfer, для логики 0.4.2. */
    private class CountingClient(base: OkHttpClient) {
        private val counter = java.util.concurrent.atomic.AtomicLong()
        val client: OkHttpClient = base.newBuilder().addNetworkInterceptor { chain ->
            val resp = chain.proceed(chain.request())
            val body = resp.body ?: return@addNetworkInterceptor resp
            val src = object : okio.ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) counter.addAndGet(it) }
            }.buffer()
            resp.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = src
            }).build()
        }.build()
        fun bytes() = counter.get()
    }
}
