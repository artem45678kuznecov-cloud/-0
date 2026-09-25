package com.nox.offline.downloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    /** Перенаправления проходят вручную (см. [execute]). */
    private val noRedirects: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    sealed class Outcome {
        data class Completed(val totalBytes: Long) : Outcome()
        data class Expired(val code: Int) : Outcome()
        /**
         * [code] == [CODE_SIZE_MISMATCH]: источник отдаёт файл другого размера,
         * чем ожидалось ([expected]/[actual]) — это другой файл, докачивать нельзя.
         */
        data class Failed(val message: String, val code: Int = 0, val expected: Long = -1, val actual: Long = -1) : Outcome() {
            val isSizeMismatch: Boolean get() = code == CODE_SIZE_MISMATCH
        }
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

    /**
     * Скачать (или докачать) один файл в [part].
     *
     * [expectedTotal] — точный размер от источника, если он известен: ответ
     * с другим полным размером — это другой файл, и он не дописывается к
     * .part ни при каких условиях ([Outcome.Failed.isSizeMismatch]).
     * [chunkSize] > 0 — качать кусками `Range: bytes=a-b` (так требует YouTube,
     * иначе длинный ответ обрывается или режется по скорости).
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        part: File,
        expectedTotal: Long = -1,
        chunkSize: Long = 0,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Outcome {
        part.parentFile?.mkdirs()
        var offset = if (part.exists()) part.length() else 0L
        if (expectedTotal > 0) {
            if (offset == expectedTotal) {
                onProgress(offset, expectedTotal)
                return Outcome.Completed(expectedTotal)
            }
            if (offset > expectedTotal) {
                // Кусок длиннее файла — он испорчен; это та же дорожка, начинаем её заново.
                log("http-part-too-long part=$offset expected=$expectedTotal")
                truncate(part)
                offset = 0
            }
        }
        val chunked = chunkSize > 0 && expectedTotal > 0
        var lastReport = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val end = if (chunked) minOf(offset + chunkSize, expectedTotal) - 1 else -1L
            val range = when {
                end >= 0 -> "bytes=$offset-$end"
                offset > 0 -> "bytes=$offset-"
                else -> null
            }
            val exec = when (val r = execute(url, headers, range)) {
                is Exec.Response -> r
                is Exec.Error -> return r.outcome
            }
            try { exec.response.use { resp ->
                val code = resp.code
                var total = -1L
                var chunkEnd = -1L
                when {
                    code in EXPIRED_CODES -> {
                        log("http-expired code=$code")
                        return Outcome.Expired(code)
                    }
                    code == 416 -> {
                        val t = ContentRange.totalOf416(resp.header("Content-Range"))
                        if (t > 0 && offset == t && (expectedTotal <= 0 || t == expectedTotal)) {
                            log("http-416-complete total=$t")
                            return Outcome.Completed(t)
                        }
                        if (expectedTotal > 0 && t > 0 && t != expectedTotal) {
                            log("http-416-mismatch total=$t expected=$expectedTotal")
                            return mismatch(expectedTotal, t)
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
                        if (expectedTotal > 0 && cr.total > 0 && cr.total != expectedTotal) {
                            log("http-size-mismatch total=${cr.total} expected=$expectedTotal")
                            return mismatch(expectedTotal, cr.total)
                        }
                        total = if (cr.total > 0) cr.total else expectedTotal
                        chunkEnd = cr.end
                    }
                    code == 200 -> {
                        val len = resp.body?.contentLength() ?: -1L
                        if (expectedTotal > 0 && len > 0 && len != expectedTotal) {
                            log("http-size-mismatch len=$len expected=$expectedTotal")
                            return mismatch(expectedTotal, len)
                        }
                        if (offset > 0) {
                            // Сервер проигнорировал Range и шлёт файл целиком:
                            // полный файл поверх куска не дописывается никогда.
                            log("http-range-ignored offset=$offset")
                            truncate(part)
                            offset = 0
                        }
                        if (len > 0) total = len else if (expectedTotal > 0) total = expectedTotal
                    }
                    else -> {
                        log("http-status code=$code")
                        return Outcome.Failed("HTTP $code", code)
                    }
                }
                val body = resp.body ?: return Outcome.Failed("пустой ответ", code)
                val started = offset
                var written = offset
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
                if (code == 206 && chunkEnd >= 0 && total > 0 && chunkEnd + 1 < total) {
                    // Кусок из середины файла: должен прийти целиком, дальше — следующий.
                    if (written < chunkEnd + 1) {
                        return Outcome.Failed("соединение закрыто на $written (кусок до ${chunkEnd + 1})")
                    }
                    if (written > chunkEnd + 1) {
                        truncate(part)
                        return Outcome.Failed("получено больше запрошенного, повтор с нуля")
                    }
                    if (written == started) return Outcome.Failed("пустой кусок ответа")
                    offset = written
                    return@use
                }
                if (total > 0 && written < total) {
                    return Outcome.Failed("соединение закрыто на $written из $total")
                }
                if (total > 0 && written > total) {
                    truncate(part)
                    return Outcome.Failed("получено больше ожидаемого, повтор с нуля")
                }
                return Outcome.Completed(if (total > 0) total else written)
            } } finally {
                exec.cancelHandle.dispose()
            }
        }
    }

    private sealed class Exec {
        class Response(val response: okhttp3.Response, val cancelHandle: DisposableHandle) : Exec()
        class Error(val outcome: Outcome) : Exec()
    }

    /**
     * Запрос с ручными перенаправлениями: при переходе на другой хост (или порт)
     * Cookie и заголовки авторизации не пересылаются, переход с HTTPS на
     * HTTP запрещён. Подписанный адрес передаётся целиком, без обрезки.
     */
    private suspend fun execute(url: String, headers: Map<String, String>, range: String?): Exec {
        var current = url.toHttpUrlOrNull() ?: return Exec.Error(Outcome.Failed("неверный адрес"))
        var sendHeaders = headers
        repeat(MAX_REDIRECTS + 1) {
            val builder = Request.Builder().url(current).get()
            for ((k, v) in sendHeaders) {
                if (k.equals("Accept-Encoding", true) || k.equals("Range", true)) continue
                builder.header(k, v)
            }
            builder.header("Accept-Encoding", "identity")
            if (range != null) builder.header("Range", range)
            val call = noRedirects.newCall(builder.build())
            // Отмена корутины должна оборвать и блокирующее чтение сокета.
            val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                handle.dispose()
                if (!currentCoroutineContext().job.isActive) return Exec.Error(Outcome.Cancelled)
                return Exec.Error(Outcome.Failed("сеть: ${e.message ?: e.javaClass.simpleName}"))
            }
            if (!resp.isRedirect) {
                return Exec.Response(resp, handle)
            }
            val location = resp.header("Location")
            resp.close()
            handle.dispose()
            val next = location?.let { current.resolve(it) }
                ?: return Exec.Error(Outcome.Failed("перенаправление без адреса", resp.code))
            if (current.isHttps && !next.isHttps) {
                log("http-redirect-downgrade-blocked")
                return Exec.Error(Outcome.Failed("перенаправление на небезопасный адрес", resp.code))
            }
            if (!next.host.equals(current.host, ignoreCase = true) || next.port != current.port || next.scheme != current.scheme) {
                sendHeaders = sendHeaders.filterKeys { k -> SENSITIVE_HEADERS.none { it.equals(k, true) } }
                log("http-redirect host-changed")
            }
            current = next
        }
        return Exec.Error(Outcome.Failed("слишком много перенаправлений"))
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
        const val MAX_REDIRECTS = 5
        const val CODE_SIZE_MISMATCH = -2

        fun mismatch(expected: Long, actual: Long) =
            Outcome.Failed("размер файла у источника $actual, ожидался $expected", CODE_SIZE_MISMATCH, expected, actual)
        val SENSITIVE_HEADERS = listOf("Cookie", "Authorization", "Proxy-Authorization")

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
