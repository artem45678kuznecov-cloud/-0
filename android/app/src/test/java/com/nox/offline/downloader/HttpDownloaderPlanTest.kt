package com.nox.offline.downloader

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 0.3.0: докачка кусками (как у YouTube), сверка точного размера,
 * перенаправления без утечки Cookie на чужой хост.
 */
class HttpDownloaderPlanTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var dir: File
    private val body: ByteArray = ByteArray(1_000_000) { i -> ((i * 31 + 7) % 253).toByte() }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val downloader = HttpDownloader(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        other = MockWebServer(); other.start()
        dir = File(System.getProperty("java.io.tmpdir"), "nox-plan-${System.nanoTime()}").apply { mkdirs() }
        requests.clear()
    }

    @After fun tearDown() {
        server.shutdown(); other.shutdown()
        dir.deleteRecursively()
    }

    /** Отдаёт [data] с поддержкой `bytes=a-b` и `bytes=a-`. */
    private fun ranged(data: ByteArray) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(data))
            val spec = range.removePrefix("bytes=")
            val start = spec.substringBefore('-').toLong()
            val endRaw = spec.substringAfter('-')
            val end = if (endRaw.isBlank()) data.size - 1L else minOf(endRaw.toLong(), data.size - 1L)
            if (start >= data.size) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${data.size}")
            val slice = data.copyOfRange(start.toInt(), (end + 1).toInt())
            return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${data.size}")
                .setBody(Buffer().write(slice))
        }
    }

    @Test fun `chunked ranges assemble the exact file`() = runBlocking {
        server.dispatcher = ranged(body)
        val part = File(dir, "v.part")
        val r = downloader.download(server.url("/v").toString(), emptyMap(), part, body.size.toLong(), 300_000) { _, _ -> }
        assertTrue(r is HttpDownloader.Outcome.Completed)
        assertArrayEquals(body, part.readBytes())
        val ranges = requests.map { it.getHeader("Range") }
        assertEquals(listOf("bytes=0-299999", "bytes=300000-599999", "bytes=600000-899999", "bytes=900000-999999"), ranges)
    }

    @Test fun `chunked resume continues from the part`() = runBlocking {
        server.dispatcher = ranged(body)
        val part = File(dir, "v.part").apply { writeBytes(body.copyOfRange(0, 450_000)) }
        val r = downloader.download(server.url("/v").toString(), emptyMap(), part, body.size.toLong(), 300_000) { _, _ -> }
        assertTrue(r is HttpDownloader.Outcome.Completed)
        assertArrayEquals(body, part.readBytes())
        assertEquals("bytes=450000-749999", requests.first().getHeader("Range"))
    }

    @Test fun `different total size is another file and is never appended`() = runBlocking {
        val otherFile = ByteArray(900_000) { 1 }
        server.dispatcher = ranged(otherFile)
        val start = body.copyOfRange(0, 100_000)
        val part = File(dir, "v.part").apply { writeBytes(start) }
        val r = downloader.download(server.url("/v").toString(), emptyMap(), part, body.size.toLong(), 0) { _, _ -> }
        assertTrue(r is HttpDownloader.Outcome.Failed && r.isSizeMismatch)
        assertArrayEquals(start, part.readBytes())            // часть не тронута
    }

    @Test fun `mismatch on full 200 response writes nothing`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10) { 9 })))
        val part = File(dir, "v.part")
        val r = downloader.download(server.url("/v").toString(), emptyMap(), part, 20, 0) { _, _ -> }
        assertTrue(r is HttpDownloader.Outcome.Failed && r.isSizeMismatch)
        assertTrue(!part.exists() || part.length() == 0L)
    }

    @Test fun `complete part is not requested again`() = runBlocking {
        server.dispatcher = ranged(body)
        val part = File(dir, "v.part").apply { writeBytes(body) }
        val r = downloader.download(server.url("/v").toString(), emptyMap(), part, body.size.toLong(), 300_000) { _, _ -> }
        assertTrue(r is HttpDownloader.Outcome.Completed)
        assertEquals(0, requests.size)
    }

    @Test fun `416 with a different total is not success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */500"))
        val part = File(dir, "v.part").apply { writeBytes(ByteArray(500)) }
        val r = downloader.download(server.url("/v").toString(), emptyMap(), part, 1000, 0) { _, _ -> }
        assertTrue(r is HttpDownloader.Outcome.Failed)
    }

    @Test fun `cookies do not follow a redirect to another host`() = runBlocking {
        other.dispatcher = ranged(body)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/file").toString()))
        val part = File(dir, "v.part")
        val headers = mapOf("Cookie" to "sid=secret", "User-Agent" to "NOX", "Authorization" to "Bearer x")
        val r = downloader.download(server.url("/start").toString(), headers, part) { _, _ -> }
        assertTrue(r is HttpDownloader.Outcome.Completed)
        val first = server.takeRequest()
        assertEquals("sid=secret", first.getHeader("Cookie"))
        val second = requests.single()
        assertNull(second.getHeader("Cookie"))
        assertNull(second.getHeader("Authorization"))
        assertEquals("NOX", second.getHeader("User-Agent"))
    }

    @Test fun `same host redirect keeps headers`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return if (request.path == "/a") MockResponse().setResponseCode(301).setHeader("Location", "/b")
                else MockResponse().setBody(Buffer().write(ByteArray(10)))
            }
        }
        val part = File(dir, "v.part")
        val r = downloader.download(server.url("/a").toString(), mapOf("Cookie" to "k=v"), part) { _, _ -> }
        assertTrue(r is HttpDownloader.Outcome.Completed)
        assertEquals("k=v", requests.last().getHeader("Cookie"))
    }

    @Test fun `signed query string is sent whole`() = runBlocking {
        server.dispatcher = ranged(body)
        val q = "/videoplayback?expire=1790361825&sig=AOq0QJ8wRAIgZ%3D%3D&n=abc_DEF-123&lsig=x%2Fy"
        val part = File(dir, "v.part")
        downloader.download(server.url("/").toString().trimEnd('/') + q, emptyMap(), part) { _, _ -> }
        assertEquals(q, requests.single().path)
    }
}
