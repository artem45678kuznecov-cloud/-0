package com.nox.offline.downloader.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * Модели каталога 0.3.0.
 *
 *  - [VideoDetails] и [Track] — что отдаёт источник (resolver.analyze);
 *  - [Variant] — вариант, который можно показать и выбрать: видео + звук
 *    или готовый файл, с пометками «NOX соберёт / телефон воспроизведёт»;
 *  - [DownloadPlan] — точный выбор, который сохраняется в задании.
 *
 * Идентичность варианта — format ID дорожек, а не позиция в списке.
 */

enum class TrackKind { AV, VIDEO, AUDIO }

enum class Transport { HTTP, HLS, DASH, OTHER }

data class Track(
    val id: String,
    val kind: TrackKind,
    val ext: String,
    val container: String,
    val vcodec: String = "",
    val vcodecRaw: String = "",
    val acodec: String = "",
    val acodecRaw: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val dynamicRange: String = "",
    val tbr: Double = 0.0,
    val vbr: Double = 0.0,
    val abr: Double = 0.0,
    val asr: Int = 0,
    val channels: Int = 0,
    val language: String = "",
    val languagePreference: Int = 0,
    val audioRole: String = "",
    val drc: Boolean = false,
    val filesize: Long = 0,
    val filesizeExact: Boolean = false,
    val filesizeApprox: Long = 0,
    val transport: Transport = Transport.HTTP,
    val chunkSize: Long = 0,
    val drm: Boolean = false,
    val note: String = "",
    val vkDirect: Boolean = false,
) {
    val hasVideo get() = kind != TrackKind.AUDIO
    val hasAudio get() = kind != TrackKind.VIDEO

    /** Известный размер: точный или оценка; 0 — неизвестен. */
    val knownSize: Long get() = if (filesize > 0) filesize else filesizeApprox

    val isHdr: Boolean get() = dynamicRange.isNotBlank() && !dynamicRange.equals("SDR", true)

    companion object {
        fun parse(o: JSONObject): Track? {
            val kind = when (o.optString("kind")) {
                "av" -> TrackKind.AV
                "video" -> TrackKind.VIDEO
                "audio" -> TrackKind.AUDIO
                else -> return null
            }
            val id = o.optString("id")
            if (id.isBlank()) return null
            return Track(
                id = id, kind = kind, ext = o.optString("ext"), container = o.optString("container"),
                vcodec = o.optString("vcodec"), vcodecRaw = o.optString("vcodec_raw"),
                acodec = o.optString("acodec"), acodecRaw = o.optString("acodec_raw"),
                width = o.optInt("width"), height = o.optInt("height"), fps = o.optDouble("fps", 0.0).orZero(),
                dynamicRange = o.optString("dynamic_range"),
                tbr = o.optDouble("tbr", 0.0).orZero(), vbr = o.optDouble("vbr", 0.0).orZero(),
                abr = o.optDouble("abr", 0.0).orZero(),
                asr = o.optInt("asr"), channels = o.optInt("channels"),
                language = o.optString("language"), languagePreference = o.optInt("language_preference"),
                audioRole = o.optString("audio_role"), drc = o.optBoolean("drc"),
                filesize = o.optLong("filesize"), filesizeExact = o.optBoolean("filesize_exact"),
                filesizeApprox = o.optLong("filesize_approx"),
                transport = when (o.optString("transport")) {
                    "http" -> Transport.HTTP
                    "hls" -> Transport.HLS
                    "dash" -> Transport.DASH
                    else -> Transport.OTHER
                },
                chunkSize = o.optLong("chunk_size"), drm = o.optBoolean("drm"),
                note = o.optString("note"), vkDirect = o.optBoolean("vk_direct"),
            )
        }

        private fun Double.orZero() = if (isNaN() || isInfinite()) 0.0 else this
    }
}

data class VideoDetails(
    val pageUrl: String,
    val extractor: String,
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnail: String,
    val webpageUrl: String,
    /** 0.4.0: настоящие дорожки субтитров источника. */
    val subtitles: List<SourceSubtitle> = emptyList(),
    /** 0.4.0: главы, которые передал источник. */
    val chapters: List<SourceChapter> = emptyList(),
) {
    val isYouTube: Boolean get() = extractor.startsWith("Youtube", ignoreCase = true)

    /** Название источника для карточки. */
    val sourceLabel: String
        get() = when {
            isYouTube -> "YouTube"
            extractor.equals("VK", true) || extractor.startsWith("VK", true) -> "VK Видео"
            extractor.isBlank() -> "Сайт"
            else -> extractor
        }

    companion object {
        fun parse(pageUrl: String, o: JSONObject) = VideoDetails(
            pageUrl = pageUrl,
            extractor = o.optString("extractor"),
            videoId = o.optString("video_id"),
            title = o.optString("title").ifBlank { "Видео" },
            uploader = o.optString("uploader"),
            durationSec = o.optLong("duration"),
            thumbnail = o.optString("thumbnail"),
            webpageUrl = o.optString("webpage_url"),
            subtitles = o.optJSONArray("subtitles")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(SourceSubtitle::parse) }
            }.orEmpty(),
            chapters = o.optJSONArray("chapters")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(SourceChapter::parse) }
            }.orEmpty(),
        )
    }
}

enum class SizeKind { EXACT, APPROX, UNKNOWN }

/** Может ли NOX получить и собрать этот вариант на этом телефоне. */
sealed class Support {
    object Ok : Support()
    data class No(val reason: String) : Support()

    val ok: Boolean get() = this is Ok
}

/** Оценка воспроизведения на этом телефоне (не влияет на возможность скачать). */
enum class Playback { LIKELY, SOFTWARE, UNLIKELY, UNKNOWN }

data class Variant(
    /** Устойчивый ключ: «v:308+a:251» или «f:url1440». */
    val key: String,
    val video: Track,
    val audio: Track?,
    /** Ступень качества по настоящему кадру: «1440p». */
    val tier: String,
    val tierHeight: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val dynamicRange: String,
    val outputContainer: String,
    val outputExt: String,
    val sizeBytes: Long,
    val sizeKind: SizeKind,
    val support: Support,
    val playback: Playback,
    val playbackNote: String,
    val needsMerge: Boolean,
    val silent: Boolean,
) {
    val hdr: Boolean get() = dynamicRange.isNotBlank() && !dynamicRange.equals("SDR", true)

    /** Главная подпись: «1440p60», «720p», «1080p HDR». */
    val title: String
        get() = buildString {
            append(tier)
            if (fps > 30) append(fps)
        }

    /** Ключ группы основного списка: ступень, частота кадров, HDR. */
    val groupKey: String get() = "$tierHeight|${fpsBucket(fps)}|${if (hdr) dynamicRange.uppercase() else "SDR"}"

    val codecLabel: String
        get() = buildString {
            append(CodecNames.video(video.vcodec, video.vcodecRaw))
            if (audio != null) append(" + ").append(CodecNames.audio(audio.acodec, audio.acodecRaw))
            else if (video.kind == TrackKind.AV && video.acodec.isNotBlank()) append(" + ").append(CodecNames.audio(video.acodec, video.acodecRaw))
        }

    val resolutionLabel: String get() = if (width > 0 && height > 0) "$width×$height" else if (height > 0) "${height} строк" else ""

    companion object {
        fun fpsBucket(fps: Int): Int = when {
            fps <= 0 -> 0
            fps <= 31 -> 30
            fps <= 50 -> 50
            else -> 60
        }
    }
}

object CodecNames {
    fun video(c: String, raw: String = ""): String = when (c) {
        "h264" -> "H.264"
        "h265" -> "H.265"
        "vp9" -> "VP9"
        "vp8" -> "VP8"
        "av1" -> "AV1"
        "dv" -> "Dolby Vision"
        "" -> if (raw.isBlank()) "видео" else raw
        else -> c.uppercase()
    }

    fun audio(c: String, raw: String = ""): String = when (c) {
        "aac" -> "AAC"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "mp3" -> "MP3"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "" -> if (raw.isBlank()) "звук" else raw
        else -> c.uppercase()
    }

    fun container(c: String): String = when (c) {
        "mp4" -> "MP4"
        "webm" -> "WebM"
        "" -> "?"
        else -> c.uppercase()
    }
}

/** Звуковая дорожка-язык для выбора, если у видео их несколько. */
data class AudioLanguage(val code: String, val label: String, val original: Boolean)

/** Сырой ответ analyze: сведения, дорожки и служебное для диагностики. */
data class Analysis(
    val details: VideoDetails,
    val tracks: List<Track>,
    val analyzedAt: Long,
    val cached: Boolean,
    val warnings: List<String>,
    val jsRuns: Int,
    val jsFailed: Int,
    val jsMs: Long,
    val jsEngine: String,
    val ytDlpVersion: String,
    val ejsVersion: String,
)

/** Понятная ошибка анализа или плана. */
data class ResolveError(
    val kind: String,
    val message: String,
    val detail: String = "",
    val stage: String = "",
) {
    /** Повтор имеет смысл только для сетевых и неизвестных сбоев. */
    val retryable: Boolean get() = kind in setOf("network", "extract-failed", "bridge", "json", "js-runtime", "timeout")
}

sealed class AnalyzeResult {
    data class Ok(val analysis: Analysis) : AnalyzeResult()
    data class Failed(val error: ResolveError) : AnalyzeResult()
}

/** Одна компонента плана со свежим прямым адресом. */
data class PlanComponent(
    val formatId: String,
    val url: String,
    val headers: Map<String, String>,
    val ext: String,
    val container: String,
    val vcodec: String,
    val acodec: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val filesize: Long,
    val filesizeExact: Boolean,
    val filesizeApprox: Long,
    val chunkSize: Long,
)

sealed class PlanResult {
    data class Ok(val video: PlanComponent, val audio: PlanComponent?, val details: VideoDetails?) : PlanResult()
    data class FormatGone(val missing: List<String>, val analysis: Analysis?) : PlanResult()
    data class Failed(val error: ResolveError) : PlanResult()
}

object CatalogJson {
    fun parseAnalysis(pageUrl: String, o: JSONObject): Analysis {
        val tracks = ArrayList<Track>()
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { Track.parse(it) }?.let(tracks::add)
        val js = o.optJSONObject("js") ?: JSONObject()
        val versions = o.optJSONObject("versions") ?: JSONObject()
        val warnings = ArrayList<String>()
        o.optJSONArray("warnings")?.let { w -> for (i in 0 until w.length()) warnings.add(w.optString(i)) }
        return Analysis(
            details = VideoDetails.parse(pageUrl, o.optJSONObject("details") ?: JSONObject()),
            tracks = tracks,
            analyzedAt = o.optLong("analyzed_at") * 1000L,
            cached = o.optBoolean("cached"),
            warnings = warnings,
            jsRuns = js.optInt("runs"), jsFailed = js.optInt("failed"), jsMs = js.optLong("ms"),
            jsEngine = js.optString("engine"),
            ytDlpVersion = versions.optString("yt_dlp"), ejsVersion = versions.optString("ejs"),
        )
    }

    fun parseError(o: JSONObject): ResolveError = ResolveError(
        kind = o.optString("kind", "extract-failed").ifBlank { "extract-failed" },
        message = o.optString("error").ifBlank { "Не удалось получить сведения о видео." },
        detail = o.optString("detail"),
        stage = o.optString("stage"),
    )

    fun parseAnalyze(pageUrl: String, raw: String): AnalyzeResult = try {
        val o = JSONObject(raw)
        if (o.optBoolean("ok")) AnalyzeResult.Ok(parseAnalysis(pageUrl, o)) else AnalyzeResult.Failed(parseError(o))
    } catch (e: Exception) {
        AnalyzeResult.Failed(ResolveError("json", "Внутренняя ошибка разбора ответа.", e.message.orEmpty().take(200)))
    }

    private fun component(o: JSONObject?): PlanComponent? {
        if (o == null) return null
        val headers = linkedMapOf<String, String>()
        o.optJSONObject("headers")?.let { h -> for (k in h.keys()) headers[k] = h.optString(k) }
        return PlanComponent(
            formatId = o.optString("format_id"), url = o.optString("url"), headers = headers,
            ext = o.optString("ext"), container = o.optString("container"),
            vcodec = o.optString("vcodec"), acodec = o.optString("acodec"),
            width = o.optInt("width"), height = o.optInt("height"), fps = o.optDouble("fps", 0.0).let { if (it.isNaN()) 0.0 else it },
            filesize = o.optLong("filesize"), filesizeExact = o.optBoolean("filesize_exact"),
            filesizeApprox = o.optLong("filesize_approx"), chunkSize = o.optLong("chunk_size"),
        )
    }

    fun parsePlan(pageUrl: String, raw: String): PlanResult = try {
        val o = JSONObject(raw)
        when {
            o.optBoolean("ok") -> {
                val v = component(o.optJSONObject("video"))
                if (v == null || v.url.isBlank()) PlanResult.Failed(ResolveError("json", "Пустой план загрузки."))
                else PlanResult.Ok(v, component(o.optJSONObject("audio")),
                    o.optJSONObject("details")?.let { VideoDetails.parse(pageUrl, it) })
            }
            o.optString("kind") == "format-gone" -> {
                val missing = ArrayList<String>()
                o.optJSONArray("missing")?.let { m -> for (i in 0 until m.length()) missing.add(m.optString(i)) }
                PlanResult.FormatGone(missing, if (o.has("tracks")) parseAnalysis(pageUrl, o) else null)
            }
            else -> PlanResult.Failed(parseError(o))
        }
    } catch (e: Exception) {
        PlanResult.Failed(ResolveError("json", "Внутренняя ошибка разбора ответа.", e.message.orEmpty().take(200)))
    }
}


/** Дорожка субтитров у источника: авторская или автоматическая (распознанная речь). */
data class SourceSubtitle(val key: String, val lang: String, val name: String, val auto: Boolean, val ext: String) {
    /** «Русский», «English (автоматические)». */
    val label: String
        get() {
            val base = name.ifBlank { CatalogBuilder.languageName(lang).ifBlank { key } }
            return if (auto) "$base (автоматические)" else base
        }

    fun toJson(): JSONObject = JSONObject().put("key", key).put("lang", lang).put("name", name).put("auto", auto).put("ext", ext)

    companion object {
        fun parse(o: JSONObject): SourceSubtitle? {
            val key = o.optString("key")
            if (key.isBlank()) return null
            return SourceSubtitle(key, o.optString("lang"), o.optString("name"), o.optBoolean("auto"), o.optString("ext", "vtt"))
        }

        fun listToJson(list: List<SourceSubtitle>): String = org.json.JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<SourceSubtitle> = runCatching {
            val a = org.json.JSONArray(json)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::parse) }
        }.getOrDefault(emptyList())
    }
}

data class SourceChapter(val title: String, val startMs: Long, val endMs: Long) {
    companion object {
        fun parse(o: JSONObject): SourceChapter? {
            val s = o.optLong("start_ms", -1)
            val e = o.optLong("end_ms", -1)
            if (s < 0 || e <= s) return null
            return SourceChapter(o.optString("title"), s, e)
        }
    }
}

/**
 * «Только звук»: отдельная звуковая дорожка источника как есть — Opus в
 * WebM или AAC в M4A. Без перекодирования и без переименования в MP3.
 */
data class AudioVariant(
    val track: Track,
    val sizeBytes: Long,
    val sizeKind: SizeKind,
    val support: Support,
) {
    val key: String get() = "audio:${track.id}"
    val bitrateKbps: Int get() = track.abr.let { if (it > 0) it.toInt() else track.tbr.toInt() }
    val codecLabel: String get() = CodecNames.audio(track.acodec, track.acodecRaw)
    val outputExt: String get() = when {
        track.container == "webm" || track.ext == "webm" -> "webm"
        track.ext.isNotBlank() -> track.ext
        else -> "m4a"
    }
    val formatLabel: String get() = "$codecLabel · ${outputExt.uppercase()}"
    val title: String get() = listOfNotNull(
        if (bitrateKbps > 0) "$bitrateKbps кбит/с" else null,
        codecLabel,
    ).joinToString(" · ")
}

/** Одна запись плейлиста в порядке источника. */
data class PlaylistEntry(
    val index: Int,
    val videoId: String,
    val extractor: String,
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnail: String,
    /** '' — доступно; иначе причина (закрыто, удалено…). */
    val unavailable: String,
) {
    val sourceKey: String get() = if (videoId.isBlank()) "" else "${extractor.lowercase()}:$videoId"

    companion object {
        fun parse(o: JSONObject) = PlaylistEntry(
            index = o.optInt("index"), videoId = o.optString("id"), extractor = o.optString("extractor"),
            url = o.optString("url"), title = o.optString("title"), uploader = o.optString("uploader"),
            durationSec = o.optLong("duration"), thumbnail = o.optString("thumbnail"), unavailable = o.optString("unavailable"),
        )
    }
}

/** Страница плейлиста. */
data class PlaylistPage(
    val id: String,
    val title: String,
    val uploader: String,
    val webpageUrl: String,
    val extractor: String,
    /** Всего элементов по словам источника; 0 — неизвестно. */
    val count: Int,
    val start: Int,
    val entries: List<PlaylistEntry>,
    val hasMore: Boolean,
)
