package com.nox.offline.downloader

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
import com.nox.offline.downloader.catalog.AnalyzeResult
import com.nox.offline.downloader.catalog.CatalogBuilder
import com.nox.offline.downloader.catalog.CatalogJson
import com.nox.offline.downloader.catalog.PlanResult
import com.nox.offline.downloader.catalog.ResolveError
import org.json.JSONObject

/**
 * Мост в Python: единственное место, где приложение зовёт yt-dlp.
 *
 * Python получает ссылку и качество, возвращает JSON с прямым адресом,
 * белым списком заголовков и метаданными. Дальше Python в передаче файла
 * не участвует. Вызов блокирующий и долгий (сеть) — только с IO-потока.
 */
class YtDlpResolver(private val context: Context) {

    data class Result(
        val ok: Boolean,
        val error: String = "",
        val kind: String = "",
        val title: String = "",
        val videoId: String = "",
        val formatId: String = "",
        val directUrl: String = "",
        val ext: String = "mp4",
        val height: Int = 0,
        val filesize: Long = 0,
        val durationSec: Long = 0,
        val thumbnail: String = "",
        val headers: Map<String, String> = emptyMap(),
        val extractor: String = "",
        val uploader: String = "",
        /** progressive | split */
        val mode: String = "progressive",
        val audioUrl: String = "",
        val audioFormatId: String = "",
        val audioHeaders: Map<String, String> = emptyMap(),
        val audioFilesize: Long = 0,
    ) {
        val isSplit: Boolean get() = mode == "split"

        companion object {
            /** Разбор ответа resolver.py. Чистая функция — проверяется JVM-тестом. */
            fun parse(raw: String): Result {
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    return Result(ok = false, error = json.optString("error", "разбор не удался"), kind = json.optString("kind"))
                }
                fun map(name: String): Map<String, String> {
                    val out = linkedMapOf<String, String>()
                    json.optJSONObject(name)?.let { h -> for (key in h.keys()) out[key] = h.optString(key) }
                    return out
                }
                return Result(
                    ok = true,
                    title = json.optString("title", "Видео"),
                    videoId = json.optString("video_id"),
                    formatId = json.optString("format_id"),
                    directUrl = json.optString("direct_url"),
                    ext = json.optString("ext", "mp4").ifBlank { "mp4" },
                    height = json.optInt("height", 0),
                    filesize = json.optLong("filesize", 0),
                    durationSec = json.optLong("duration", 0),
                    thumbnail = json.optString("thumbnail"),
                    headers = map("headers"),
                    extractor = json.optString("extractor"),
                    uploader = json.optString("uploader"),
                    mode = json.optString("mode", "progressive").ifBlank { "progressive" },
                    audioUrl = json.optString("audio_url"),
                    audioFormatId = json.optString("audio_format_id"),
                    audioHeaders = map("audio_headers"),
                    audioFilesize = json.optLong("audio_filesize", 0),
                )
            }
        }
    }

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var ytDlpVersion: String = ""
        private set

    private fun python(): Python {
        // До трёх разборов могут идти одновременно: старт интерпретатора
        // должен случиться ровно один раз.
        synchronized(YtDlpResolver::class.java) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
        }
        return Python.getInstance()
    }

    fun version(): String {
        if (ytDlpVersion.isNotEmpty()) return ytDlpVersion
        return try {
            val v = python().getModule("resolver").callAttr("ytdlp_version").toString()
            ytDlpVersion = v
            v
        } catch (e: Exception) {
            "unavailable: ${e.javaClass.simpleName}"
        }
    }

    /**
     * Блокирующий разбор. Никогда не бросает: ошибка приходит в Result.
     *
     * [allowSplit] = false — поведение v0.1.0. [preferFormat]/[preferAudio] —
     * форматы уже начатой загрузки: resolver.py выберет их же, если они есть.
     */
    fun resolve(
        pageUrl: String,
        quality: Quality,
        allowSplit: Boolean = false,
        preferFormat: String = "",
        preferAudio: String = "",
    ): Result {
        NoxLog.event("resolve-start", "host" to SafeUrl.host(pageUrl), "quality" to quality.key, "split" to allowSplit)
        val started = System.currentTimeMillis()
        val raw = try {
            python().getModule("resolver")
                .callAttr("resolve", pageUrl, quality.key, allowSplit, preferFormat, preferAudio).toString()
        } catch (e: Throwable) {
            val msg = "python: ${e.javaClass.simpleName}: ${e.message?.take(300)}"
            lastError = msg
            NoxLog.event("resolve-error", "kind" to "bridge", "error" to msg)
            return Result(ok = false, error = msg, kind = "bridge")
        }
        return try {
            val r = Result.parse(raw)
            if (!r.ok) {
                lastError = r.error
                NoxLog.event("resolve-error", "kind" to r.kind, "error" to r.error.take(200))
            } else {
                NoxLog.event(
                    "resolve-success",
                    "mode" to r.mode, "format" to r.formatId, "audio" to r.audioFormatId.ifBlank { null },
                    "height" to r.height, "size" to r.filesize, "host" to SafeUrl.host(r.directUrl),
                    "headers" to r.headers.keys.joinToString(","),
                    "ms" to (System.currentTimeMillis() - started),
                )
            }
            r
        } catch (e: Exception) {
            val msg = "bad resolver json: ${e.message}"
            lastError = msg
            NoxLog.event("resolve-error", "kind" to "json", "error" to msg)
            Result(ok = false, error = msg, kind = "json")
        }
    }

    // ------------------------------------------------------------------
    //  0.3.0: анализ и точный план
    // ------------------------------------------------------------------

    private fun call(fn: String, vararg args: Any?): String =
        python().getModule("resolver").callAttr(fn, *args).toString()

    /**
     * Один разбор страницы: сведения и все дорожки. Блокирующий, зовётся
     * не из UI-потока. Никогда не бросает: ошибка приходит в результате.
     */
    fun analyze(pageUrl: String, fresh: Boolean = false): AnalyzeResult {
        val host = SafeUrl.host(pageUrl)
        NoxLog.event("analyze-start", "host" to host, "fresh" to fresh)
        val started = System.currentTimeMillis()
        val raw = try {
            call("analyze", pageUrl, fresh)
        } catch (e: Throwable) {
            val msg = "python: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
            lastError = msg
            NoxLog.event("analyze-error", "stage" to "bridge", "error" to msg)
            return AnalyzeResult.Failed(ResolveError("bridge", "Внутренняя ошибка разбора ссылки.", msg))
        }
        val r = CatalogJson.parseAnalyze(pageUrl, raw)
        val ms = System.currentTimeMillis() - started
        when (r) {
            is AnalyzeResult.Ok -> {
                val a = r.analysis
                NoxLog.event("analyze-ok", "host" to host, "source" to a.details.extractor, "tracks" to a.tracks.size,
                    "maxTier" to a.tracks.maxOfOrNull { CatalogBuilder.tier(it.width, it.height) },
                    "cached" to a.cached, "js" to "${a.jsRuns}/${a.jsFailed}/${a.jsMs}ms", "ms" to ms)
            }
            is AnalyzeResult.Failed -> {
                lastError = "${r.error.kind}: ${r.error.detail.take(200)}"
                NoxLog.event("analyze-error", "host" to host, "stage" to r.error.stage, "kind" to r.error.kind,
                    "detail" to r.error.detail.take(160), "ms" to ms)
            }
        }
        return r
    }

    /** Свежие адреса ровно для выбранных format ID. Подмены формата не бывает. */
    fun plan(pageUrl: String, videoFormat: String, audioFormat: String, maxAgeSec: Int? = null): PlanResult {
        val started = System.currentTimeMillis()
        val raw = try {
            call("plan", pageUrl, videoFormat, audioFormat, maxAgeSec)
        } catch (e: Throwable) {
            val msg = "python: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
            lastError = msg
            NoxLog.event("plan-error", "stage" to "bridge", "error" to msg)
            return PlanResult.Failed(ResolveError("bridge", "Внутренняя ошибка разбора ссылки.", msg))
        }
        val r = CatalogJson.parsePlan(pageUrl, raw)
        val ms = System.currentTimeMillis() - started
        when (r) {
            is PlanResult.Ok -> NoxLog.event("plan-ok", "video" to r.video.formatId, "audio" to r.audio?.formatId,
                "vsize" to r.video.filesize, "asize" to r.audio?.filesize, "host" to SafeUrl.host(r.video.url),
                "chunk" to r.video.chunkSize, "ms" to ms)
            is PlanResult.FormatGone -> NoxLog.event("plan-format-gone", "missing" to r.missing.joinToString(","), "ms" to ms)
            is PlanResult.Failed -> {
                lastError = "${r.error.kind}: ${r.error.detail.take(200)}"
                NoxLog.event("plan-error", "stage" to r.error.stage, "kind" to r.error.kind,
                    "detail" to r.error.detail.take(160), "ms" to ms)
            }
        }
        return r
    }

    /** Забыть кэш разбора этой ссылки (после отказа по адресу из кэша). */
    fun forget(pageUrl: String) {
        runCatching { call("forget", pageUrl) }
    }

    /** Состояние встроенного JS-движка для диагностики. */
    fun jsStatus(): String = try {
        call("js_status")
    } catch (e: Throwable) {
        "unavailable: ${e.javaClass.simpleName}"
    }
}
