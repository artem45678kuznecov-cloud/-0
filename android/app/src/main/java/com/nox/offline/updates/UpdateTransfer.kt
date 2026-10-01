package com.nox.offline.updates

import com.nox.offline.downloader.HttpDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Передача APK обновления: одна управляемая загрузка конкретного выпуска.
 *
 * Поверх [HttpDownloader] (Range, 206/200/416, точный размер из манифеста):
 *  - обрыв, таймаут, ошибка сети — скачанная часть остаётся, повтор через
 *    2, 4, 8 … 60 с; без сети — ожидание сети, а не попытки впустую;
 *  - 401/403/404/410 (истекла временная ссылка GitHub, сервер отказал) —
 *    часть НЕ удаляется: манифест читается заново, и если это тот же выпуск
 *    (версия, размер, SHA-256), передача продолжается со свежей ссылки;
 *  - у источника другой размер — это другой файл: часть сбрасывается;
 *  - каждая попытка измеряется: сколько байт пришло из сети, сколько из них
 *    повторных, время передачи и пауз; по каждому запросу — DNS, соединение,
 *    TLS, первый байт (хост без параметров ссылки), код и Range.
 *
 * Класс не знает об Android: его проверяют JVM-тесты с MockWebServer.
 */
class UpdateTransfer(
    baseClient: OkHttpClient,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Есть ли сеть (подождать её, если нет). false — сети нет и ждать здесь нельзя. */
    private val awaitNetwork: suspend () -> Boolean = { true },
    private val log: (String) -> Unit = {},
) {
    enum class Phase { CONNECTING, DOWNLOADING, STALLED, RETRY_WAIT, WAITING_NETWORK }

    data class Progress(
        val received: Long,
        val total: Long,
        /** Фактическая скорость за последние секунды, байт/с (0 — данных не было). */
        val bytesPerSecond: Long,
        /** Примерно осталось, с (-1 — не оценить). */
        val etaSeconds: Long,
        val phase: Phase,
        /** Для RETRY_WAIT: через сколько секунд повтор. */
        val retryInSeconds: Int = 0,
        val note: String = "",
    )

    /** Что спросить у источника, когда ссылка перестала работать. */
    sealed class Refresh {
        /** Тот же выпуск (версия, размер, SHA-256) — можно продолжать с этой ссылки. */
        data class Same(val url: String) : Refresh()
        /** Опубликована другая версия — эта передача больше не нужна. */
        object Changed : Refresh()
        /** Источник сейчас не ответил. */
        data class Unavailable(val message: String) : Refresh()
    }

    sealed class Result {
        object Done : Result()
        object Cancelled : Result()
        object Obsolete : Result()
        /** Сети нет: носитель остановится и вернётся, когда она появится. */
        object NoNetwork : Result()
        data class Failed(val message: String, val retryable: Boolean) : Result()
    }

    /** Замеры передачи — для экрана и «Скопировать диагностику». */
    class Stats {
        var attempts = 0
        /** Байт тела ответов, пришедших из сети за все попытки. */
        var networkBytes = 0L
        var transferMs = 0L
        var pauseMs = 0L
        var startPart = 0L
        val requests = ArrayDeque<String>()

        fun repeatedBytes(finalSize: Long): Long = maxOf(0L, networkBytes - (finalSize - startPart))

        internal fun request(line: String) {
            if (requests.size >= MAX_REQUESTS) requests.removeFirst()
            requests.addLast(line)
        }
    }

    private val counter = AtomicLong()
    private val stats = Stats()
    val currentStats: Stats get() = stats

    /** Клиент обновлений: считает байты тела и времена каждого запроса; клиент видеозагрузок не трогается. */
    private val client: OkHttpClient = baseClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val resp = chain.proceed(chain.request())
            val body = resp.body ?: return@addNetworkInterceptor resp
            resp.newBuilder().body(CountingBody(body, counter)).build()
        }
        .eventListenerFactory { Timings() }
        .build()

    private val http = HttpDownloader(client, log)

    /**
     * Скачать в [part] файл размером [size]. [refresh] перечитывает манифест,
     * когда ссылка перестала работать. Прогресс — не чаще раза в 400 мс.
     */
    suspend fun run(url: String, part: File, size: Long, refresh: suspend () -> Refresh, onProgress: (Progress) -> Unit): Result {
        var current = url
        var backoff = FIRST_BACKOFF_MS
        var failuresWithoutProgress = 0
        var refusals = 0
        stats.startPart = if (part.exists()) part.length() else 0L
        val speed = Speedometer(now)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!awaitNetwork()) return Result.NoNetwork
            stats.attempts++
            val before = if (part.exists()) part.length() else 0L
            if (before >= size && size > 0) {
                // Готовая часть: проверит SHA-256 и подпись вызывающий.
                onProgress(Progress(before, size, 0, 0, Phase.DOWNLOADING))
                return Result.Done
            }
            speed.restart(before)
            onProgress(Progress(before, size, 0, -1, Phase.CONNECTING))
            val start = now()
            val bytesAtStart = counter.get()
            val out = try {
                http.download(current, mapOf("Accept" to "application/vnd.android.package-archive"), part, expectedTotal = size) { done, total ->
                    val bps = speed.add(done)
                    val stalled = speed.stalledMs() >= STALL_MS
                    val eta = if (bps > 0 && total > 0) (total - done) / bps else -1
                    onProgress(Progress(done, if (total > 0) total else size, bps, eta,
                        if (stalled) Phase.STALLED else Phase.DOWNLOADING,
                        note = if (stalled) "данные не приходят ${speed.stalledMs() / 1000} с" else ""))
                }
            } catch (e: CancellationException) {
                stats.transferMs += now() - start
                stats.networkBytes += counter.get() - bytesAtStart
                throw e
            }
            stats.transferMs += now() - start
            stats.networkBytes += counter.get() - bytesAtStart
            val after = if (part.exists()) part.length() else 0L
            when (out) {
                is HttpDownloader.Outcome.Completed -> return Result.Done
                HttpDownloader.Outcome.Cancelled -> return Result.Cancelled
                is HttpDownloader.Outcome.Expired -> {
                    // Скачанное не удаляем: ссылка временная, файл тот же.
                    refusals++
                    log("update-refused code=${out.code} part=$after")
                    when (val r = refresh()) {
                        is Refresh.Same -> current = r.url
                        Refresh.Changed -> return Result.Obsolete
                        is Refresh.Unavailable -> log("update-refresh-unavailable ${r.message}")
                    }
                    if (refusals >= MAX_REFUSALS) {
                        return Result.Failed("сервер обновлений отказывает (HTTP ${out.code}); скачанные ${after / 1_048_576} МБ сохранены", true)
                    }
                }
                is HttpDownloader.Outcome.Failed -> {
                    if (out.isSizeMismatch) {
                        part.delete()
                        return Result.Failed("размер файла у источника ${out.actual} байт, а в манифесте $size — это другой файл", true)
                    }
                    log("update-attempt-failed ${out.message}")
                }
            }
            if (after > before) { failuresWithoutProgress = 0; backoff = FIRST_BACKOFF_MS } else failuresWithoutProgress++
            if (failuresWithoutProgress >= MAX_FAILURES_WITHOUT_PROGRESS) {
                return Result.Failed("передача не продвигается: ${(out as? HttpDownloader.Outcome.Failed)?.message ?: "нет данных"}", true)
            }
            val wait = backoff
            backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
            var left = wait
            while (left > 0) {
                onProgress(Progress(after, size, 0, -1, Phase.RETRY_WAIT, retryInSeconds = ((left + 999) / 1000).toInt(),
                    note = (out as? HttpDownloader.Outcome.Failed)?.message.orEmpty()))
                val step = minOf(left, 1000L)
                sleep(step)
                left -= step
            }
            stats.pauseMs += wait
        }
    }

    /** Скорость за последние секунды по точкам прогресса. */
    private class Speedometer(private val now: () -> Long) {
        private val times = LongArray(64)
        private val bytes = LongArray(64)
        private var n = 0
        private var head = 0
        private var lastGrowth = 0L
        private var lastBytes = 0L

        fun restart(at: Long) {
            n = 0; head = 0
            lastBytes = at
            lastGrowth = now()
            push(lastGrowth, at)
        }

        private fun push(t: Long, b: Long) {
            times[head] = t; bytes[head] = b
            head = (head + 1) % times.size
            if (n < times.size) n++
        }

        fun add(done: Long): Long {
            val t = now()
            if (done > lastBytes) { lastGrowth = t; lastBytes = done }
            push(t, done)
            // Самая старая точка не дальше WINDOW_MS назад.
            var oldest = (head - n + times.size) % times.size
            var k = n
            while (k > 1 && t - times[oldest] > WINDOW_MS) { oldest = (oldest + 1) % times.size; k-- }
            val dt = t - times[oldest]
            return if (dt <= 0) 0 else (done - bytes[oldest]) * 1000 / dt
        }

        fun stalledMs(): Long = now() - lastGrowth
    }

    /** Тело ответа, которое считает пришедшие из сети байты. */
    private class CountingBody(private val delegate: ResponseBody, private val counter: AtomicLong) : ResponseBody() {
        private val counted: BufferedSource = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) counter.addAndGet(n)
                return n
            }
        }.buffer()

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = counted
    }

    /** Времена одного запроса: DNS, соединение, TLS, первый байт, тело. */
    private inner class Timings : EventListener() {
        private var start = 0L
        private var dns = -1L
        private var connect = -1L
        private var tls = -1L
        private var mark = 0L
        private var headers = -1L
        private var code = 0
        private var bytes = 0L

        override fun callStart(call: Call) { start = now() }
        override fun dnsStart(call: Call, domainName: String) { mark = now() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { dns = now() - mark }
        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { mark = now() }
        override fun secureConnectStart(call: Call) { connect = now() - mark; mark = now() }
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) { tls = now() - mark }
        override fun responseHeadersEnd(call: Call, response: Response) { headers = now() - start; code = response.code }
        override fun responseBodyEnd(call: Call, byteCount: Long) { bytes = byteCount }
        override fun callEnd(call: Call) = record(call, null)
        override fun callFailed(call: Call, ioe: IOException) = record(call, ioe)

        private fun record(call: Call, error: IOException?) {
            val r = call.request()
            val total = now() - start
            val parts = buildList {
                add("${r.method} ${r.url.host}")
                if (code > 0) add("HTTP $code")
                r.header("Range")?.let { add("Range $it") }
                if (dns >= 0) add("DNS $dns мс")
                if (connect >= 0) add("соединение $connect мс")
                if (tls >= 0) add("TLS $tls мс")
                if (headers >= 0) add("первый ответ $headers мс")
                if (bytes > 0) add("получено ${bytes / 1024} КБ за $total мс")
                if (error != null) add("сбой: ${error.javaClass.simpleName}: ${error.message?.take(80)}")
            }
            stats.request(parts.joinToString(", "))
        }
    }

    companion object {
        const val FIRST_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        /** Сколько попыток подряд без единого нового байта — потом честная ошибка. */
        const val MAX_FAILURES_WITHOUT_PROGRESS = 12
        const val MAX_REFUSALS = 6
        const val STALL_MS = 8_000L
        const val WINDOW_MS = 5_000L
        const val MAX_REQUESTS = 30
    }
}
