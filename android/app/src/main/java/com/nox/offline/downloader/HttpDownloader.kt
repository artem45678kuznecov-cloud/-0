package com.nox.offline.downloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Передача файла: OkHttp -> .part на диске, потоком, без файла в памяти.
 *
 * Правила — те же, что у HTTP-пути NOX:
 *
 *  - если .part есть, запрос идёт с `Range: bytes=<размер .part>-`;
 *  - `Accept-Encoding: identity` всегда: байты на диске и байты в
 *    Content-Length обязаны быть одними и теми же;
 *  - 206 дописывается, но только если Content-Range начинается ровно с
 *    нашего смещения;
 *  - 200 при смещении 0 — обычная загрузка; 200 при смещении > 0 значит,
 *    что сервер прислал файл целиком: .part обнуляется и пишется с нуля,
 *    полный файл поверх куска не дописывается никогда;
 *  - 416 с `Content-Range: bytes * /<total>`, где total равен размеру
 *    .part, — файл уже скачан; иначе .part обнуляется и повтор с нуля;
 *  - 401/403/404/410 — адрес протух, решение принимает координатор.
 *
 * Класс не знает об Android: его проверяют JVM-тесты с MockWebServer.
 */
class HttpDownloader(
    private val client: OkHttpClient = defaultClient(),
    private val log: (String) -> Unit = {},
) {
    sealed class Outcome {
        data class Completed(val totalBytes: Long) : Outcome()
        data class Expired(val code: Int) : Outcome()
        data class Failed(val message: String, val code: Int = 0) : Outcome()
        object Cancelled : Outcome()
    }

    /** Разобранный `Content-Range: bytes 100-999/1000`. */
    data class ContentRange(val start: Long, val end: Long, val total: Long) {
        companion object {
            private val full = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)")
            private val unsatisfied = Regex("bytes\\s+\\*/(\\d+)")

            fun parse(header: String?): ContentRange? {
                if (header.isNullOrBlank()) return null
                val m = full.find(header.trim()) ?: return null
                val total = m.groupValues[3].let { if (it == "*") -1L else it.toLongOrNull() ?: -1L }
                return ContentRange(m.groupValues[1].toLong(), m.groupValues[2].toLong(), total)
            }

            /** Из ответа 416: полный размер, если сервер его назвал. */
            fun totalOf416(header: String?): Long {
                if (header.isNullOrBlank()) return -1
                val m = unsatisfied.find(header.trim()) ?: return -1
                return m.groupValues[1].toLongOrNull() ?: -1
            }
        }
    }

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        part: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Outcome {
        part.parentFile?.mkdirs()
        var offset = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(url).get()
        for ((k, v) in headers) {
            if (k.equals("Accept-Encoding", true) || k.equals("Range", true)) continue
            builder.header(k, v)
        }
        builder.header("Accept-Encoding", "identity")
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        val call = client.newCall(builder.build())

        // Отмена корутины должна оборвать и блокирующее чтение сокета.
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (!currentCoroutineContext().job.isActive) return Outcome.Cancelled
                return Outcome.Failed("сеть: ${e.message ?: e.javaClass.simpleName}")
            }
            response.use { resp ->
                val code = resp.code
                var total = -1L
                when {
                    code in EXPIRED_CODES -> {
                        log("http-expired code=$code")
                        return Outcome.Expired(code)
                    }
                    code == 416 -> {
                        val t = ContentRange.totalOf416(resp.header("Content-Range"))
                        if (t > 0 && offset == t) {
                            log("http-416-complete total=$t")
                            return Outcome.Completed(t)
                        }
                        log("http-416-restart total=$t part=$offset")
                        truncate(part)
                        return Outcome.Failed("416: повтор с нуля", 416)
                    }
                    code == 206 -> {
                        val cr = ContentRange.parse(resp.header("Content-Range"))
                        if (cr == null || cr.start != offset) {
                            log("http-206-mismatch range=${resp.header("Content-Range")} offset=$offset")
                            return Outcome.Failed("Content-Range не совпал со смещением", 206)
                        }
                        total = cr.total
                    }
                    code == 200 -> {
                        if (offset > 0) {
                            // Сервер проигнорировал Range и шлёт файл целиком.
                            log("http-range-ignored offset=$offset")
                            truncate(part)
                            offset = 0
                        }
                        val len = resp.body?.contentLength() ?: -1L
                        if (len > 0) total = len
                    }
                    else -> {
                        log("http-status code=$code")
                        return Outcome.Failed("HTTP $code", code)
                    }
                }
                val body = resp.body ?: return Outcome.Failed("пустой ответ", code)
                var written = offset
                var lastReport = 0L
                try {
                    body.byteStream().use { input ->
                        RandomAccessFile(part, "rw").use { raf ->
                            raf.seek(offset)
                            val buf = ByteArray(CHUNK)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                written += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= REPORT_EVERY_MS) {
                                    lastReport = now
                                    onProgress(written, total)
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    return Outcome.Cancelled
                } catch (e: IOException) {
                    if (!currentCoroutineContext().job.isActive) return Outcome.Cancelled
                    onProgress(written, total)
                    return Outcome.Failed("обрыв: ${e.message ?: e.javaClass.simpleName} на $written")
                }
                onProgress(written, total)
                if (total > 0 && written < total) {
                    return Outcome.Failed("соединение закрыто на $written из $total")
                }
                if (total > 0 && written > total) {
                    truncate(part)
                    return Outcome.Failed("получено больше ожидаемого, повтор с нуля")
                }
                return Outcome.Completed(if (total > 0) total else written)
            }
        } finally {
            handle.dispose()
        }
    }

    private fun truncate(part: File) {
        try {
            RandomAccessFile(part, "rw").use { it.setLength(0) }
        } catch (e: IOException) {
            part.delete()
        }
    }

    companion object {
        const val CHUNK = 256 * 1024
        const val REPORT_EVERY_MS = 400L
        val EXPIRED_CODES = setOf(401, 403, 404, 410)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
