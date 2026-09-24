package com.nox.offline.downloader

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
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
    )

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var ytDlpVersion: String = ""
        private set

    private fun python(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
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

    /** Блокирующий разбор. Никогда не бросает: ошибка приходит в Result. */
    fun resolve(pageUrl: String, quality: Quality): Result {
        NoxLog.event("resolve-start", "host" to SafeUrl.host(pageUrl), "quality" to quality.key)
        val started = System.currentTimeMillis()
        val raw = try {
            python().getModule("resolver").callAttr("resolve", pageUrl, quality.key).toString()
        } catch (e: Throwable) {
            val msg = "python: ${e.javaClass.simpleName}: ${e.message?.take(300)}"
            lastError = msg
            NoxLog.event("resolve-error", "kind" to "bridge", "error" to msg)
            return Result(ok = false, error = msg, kind = "bridge")
        }
        return try {
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) {
                val err = json.optString("error", "разбор не удался")
                lastError = err
                NoxLog.event("resolve-error", "kind" to json.optString("kind"), "error" to err.take(200))
                Result(ok = false, error = err, kind = json.optString("kind"))
            } else {
                val headers = mutableMapOf<String, String>()
                json.optJSONObject("headers")?.let { h ->
                    for (key in h.keys()) headers[key] = h.optString(key)
                }
                val r = Result(
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
                    headers = headers,
                    extractor = json.optString("extractor"),
                )
                NoxLog.event(
                    "resolve-success",
                    "format" to r.formatId, "height" to r.height,
                    "size" to r.filesize, "host" to SafeUrl.host(r.directUrl),
                    "headers" to headers.keys.joinToString(","),
                    "ms" to (System.currentTimeMillis() - started),
                )
                r
            }
        } catch (e: Exception) {
            val msg = "bad resolver json: ${e.message}"
            lastError = msg
            NoxLog.event("resolve-error", "kind" to "json", "error" to msg)
            Result(ok = false, error = msg, kind = "json")
        }
    }
}
