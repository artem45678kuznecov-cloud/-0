package com.nox.offline.downloader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Передача файла против MockWebServer: Range, 206, 200 поверх части,
 * 416, протухший адрес, отмена с сохранением .part.
 */
class HttpDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val body: ByteArray = ByteArray(700 * 1024) { i -> ((i * 7 + 13) % 251).toByte() }
    private val log = mutableListOf<String>()

    private val downloader = HttpDownloader(
        OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
    ) { log.add(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = File(System.getProperty("java.io.tmpdir"), "nox-test-${System.nanoTime()}").apply { mkdirs() }
        log.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    /** Сервер, который умеет Range, как VK-CDN. */
    private fun rangeDispatcher(supportRange: Boolean = true, expiredCode: Int = 0): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (expiredCode != 0) return MockResponse().setResponseCode(expiredCode)
            val range = request.getHeader("Range")
            if (range != null && supportRange) {
                val start = range.removePrefix("bytes=").removeSuffix("-").toLong()
                if (start >= body.size) {
                    return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${body.size}")
                }
                val slice = body.copyOfRange(start.toInt(), body.size)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                    .setHeader("Content-Length", slice.size.toString())
                    .setBody(Buffer().write(slice))
            }
            return MockResponse().setResponseCode(200)
                .setHeader("Content-Length", body.size.toString())
                .setBody(Buffer().write(body))
        }
    }

    private fun url() = server.url("/v.mp4").toString()

    @Test
    fun `fresh download streams whole file to part`() = runBlocking {
        server.dispatcher = rangeDispatcher()
        val part = File(dir, "v.mp4.part")
        var lastTotal = -1L
        val out = downloader.download(url(), mapOf("User-Agent" to "nox"), part) { _, t -> lastTotal = t }
        assertEquals(HttpDownloader.Outcome.Completed(body.size.toLong()), out)
        assertTrue(part.readBytes().contentEquals(body))
        assertEquals(body.size.toLong(), lastTotal)
        val req = server.takeRequest()
        assertNull(req.getHeader("Range"))
        assertEquals("identity", req.getHeader("Accept-Encoding"))
        assertEquals("nox", req.getHeader("User-Agent"))
    }

    @Test
    fun `resume sends Range from part size and appends`() = runBlocking {
        server.dispatcher = rangeDispatcher()
        val part = File(dir, "v.mp4.part")
        val offset = 300 * 1024
        part.writeBytes(body.copyOfRange(0, offset))
        val out = downloader.download(url(), emptyMap(), part) { _, _ -> }
        assertEquals(HttpDownloader.Outcome.Completed(body.size.toLong()), out)
        assertTrue(part.readBytes().contentEquals(body))
        val req = server.takeRequest()
        assertEquals("bytes=$offset-", req.getHeader("Range"))
        assertEquals("identity", req.getHeader("Accept-Encoding"))
    }

    @Test
    fun `200 on Range restarts from zero instead of appending`() = runBlocking {
        server.dispatcher = rangeDispatcher(supportRange = false)
        val part = File(dir, "v.mp4.part")
        part.writeBytes(ByteArray(100 * 1024) { 1 })          // мусорный кусок
        val out = downloader.download(url(), emptyMap(), part) { _, _ -> }
        assertEquals(HttpDownloader.Outcome.Completed(body.size.toLong()), out)
        assertTrue("файл обязан быть ровно телом, а не мусором + телом", part.readBytes().contentEquals(body))
        assertTrue(log.any { it.startsWith("http-range-ignored") })
    }

    @Test
    fun `416 with matching total means already complete`() = runBlocking {
        server.dispatcher = rangeDispatcher()
        val part = File(dir, "v.mp4.part")
        part.writeBytes(body)
        val out = downloader.download(url(), emptyMap(), part) { _, _ -> }
        assertEquals(HttpDownloader.Outcome.Completed(body.size.toLong()), out)
        assertEquals(body.size.toLong(), part.length())
    }

    @Test
    fun `expired codes are reported not retried`() = runBlocking {
        for (code in listOf(401, 403, 404, 410)) {
            server.dispatcher = rangeDispatcher(expiredCode = code)
            val part = File(dir, "v$code.mp4.part")
            part.writeBytes(body.copyOfRange(0, 1000))
            val out = downloader.download(url(), emptyMap(), part) { _, _ -> }
            assertEquals(HttpDownloader.Outcome.Expired(code), out)
            assertEquals("часть обязана остаться", 1000L, part.length())
        }
    }

    @Test
    fun `server error is a retryable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val part = File(dir, "v.mp4.part")
        val out = downloader.download(url(), emptyMap(), part) { _, _ -> }
        assertTrue(out is HttpDownloader.Outcome.Failed)
        assertEquals(503, (out as HttpDownloader.Outcome.Failed).code)
    }

    @Test
    fun `short body is a failure and keeps what arrived`() = runBlocking {
        // Сервер обещает весь файл, а соединение рвётся посередине тела.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(Buffer().write(body))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val part = File(dir, "v.mp4.part")
        val out = downloader.download(url(), emptyMap(), part) { _, _ -> }
        assertTrue(out.toString(), out is HttpDownloader.Outcome.Failed)
        assertTrue("что-то дошло", part.length() > 0)
        assertTrue("но не всё", part.length() < body.size)
    }

    @Test
    fun `cancellation keeps the part file`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Length", body.size.toString())
                    .setBody(Buffer().write(body))
                    .throttleBody(32 * 1024, 100, TimeUnit.MILLISECONDS)
        }
        val part = File(dir, "v.mp4.part")
        val job = CoroutineScope(Dispatchers.IO).async {
            downloader.download(url(), emptyMap(), part) { _, _ -> }
        }
        delay(450)
        job.cancel()
        val out = try { job.await() } catch (e: Exception) { null }
        assertTrue(out == null || out == HttpDownloader.Outcome.Cancelled)
        assertTrue("что-то уже должно было записаться", part.exists() && part.length() > 0)
        assertTrue(part.length() < body.size)
        // И дописать можно с того же места.
        server.dispatcher = rangeDispatcher()
        val out2 = downloader.download(url(), emptyMap(), part) { _, _ -> }
        assertEquals(HttpDownloader.Outcome.Completed(body.size.toLong()), out2)
        assertTrue(part.readBytes().contentEquals(body))
    }

    @Test
    fun `content range parser`() {
        val cr = HttpDownloader.ContentRange.parse("bytes 100-999/1000")
        assertNotNull(cr)
        assertEquals(100L, cr!!.start); assertEquals(999L, cr.end); assertEquals(1000L, cr.total)
        assertEquals(-1L, HttpDownloader.ContentRange.parse("bytes 0-9/*")!!.total)
        assertNull(HttpDownloader.ContentRange.parse("garbage"))
        assertEquals(1000L, HttpDownloader.ContentRange.totalOf416("bytes */1000"))
        assertEquals(-1L, HttpDownloader.ContentRange.totalOf416(null))
        assertFalse(HttpDownloader.EXPIRED_CODES.contains(500))
    }
}
