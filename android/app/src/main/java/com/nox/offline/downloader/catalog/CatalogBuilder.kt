package com.nox.offline.downloader.catalog

import java.util.Locale

/** Каталог конкретного видео: все варианты и короткий основной список. */
data class FormatCatalog(
    val analysis: Analysis,
    val languages: List<AudioLanguage>,
    /** Выбранный язык звука ('' — у видео одна звуковая дорожка или язык не указан). */
    val language: String,
    /** Все варианты, от лучшего качества к худшему. */
    val variants: List<Variant>,
    /** По одному лучшему варианту на ступень / частоту кадров / HDR. */
    val main: List<Variant>,
) {
    val details: VideoDetails get() = analysis.details

    fun find(key: String): Variant? = variants.firstOrNull { it.key == key }

    /** Остальные варианты той же ступени — для «Подробнее». */
    fun alternatives(v: Variant): List<Variant> = variants.filter { it.groupKey == v.groupKey && it.key != v.key }

    val hasSupported: Boolean get() = variants.any { it.support.ok }

    /**
     * Предварительный выбор: самый высокий поддерживаемый вариант не выше
     * [preferredHeight] (0 — без ограничения), который телефон, скорее всего,
     * воспроизведёт. Пользователь видит выбор и подтверждает его сам.
     */
    fun preselect(preferredHeight: Int): Variant? {
        val ok = main.filter { it.support.ok }
        if (ok.isEmpty()) return null
        val playable = ok.filter { it.playback != Playback.UNLIKELY }.ifEmpty { ok }
        if (preferredHeight <= 0) return playable.first()
        return playable.firstOrNull { it.tierHeight in 1..preferredHeight }
            ?: playable.lastOrNull()
    }
}

object CatalogBuilder {
    /** Стандартные ступени: высота и длинная сторона кадра 16:9. */
    private val TIERS = listOf(144 to 256, 240 to 426, 360 to 640, 480 to 854, 720 to 1280,
        1080 to 1920, 1440 to 2560, 2160 to 3840, 4320 to 7680)

    /**
     * Ступень качества по настоящим размерам кадра. Ориентация не важна:
     * вертикальное 1080×1920 — это 1080p, а не «1920p»; широкое 1920×800 —
     * тоже 1080p (так его подписывает и сам YouTube).
     */
    fun tier(width: Int, height: Int): Int {
        if (width > 0 && height > 0) {
            val long = maxOf(width, height)
            val short = minOf(width, height)
            for ((th, tl) in TIERS) {
                if (long <= tl * 1.02 && short <= th * 1.02) return th
            }
            return short
        }
        val h = if (height > 0) height else 0
        if (h == 0) return 0
        return TIERS.firstOrNull { (th, _) -> h <= th * 1.02 }?.first ?: h
    }

    fun tierLabel(th: Int): String = if (th > 0) "${th}p" else "Качество не указано"

    fun build(analysis: Analysis, caps: DeviceCaps, language: String? = null): FormatCatalog {
        val tracks = analysis.tracks
        val audios = tracks.filter { it.kind == TrackKind.AUDIO }
        val avs = tracks.filter { it.kind == TrackKind.AV }
        val videos = tracks.filter { it.kind == TrackKind.VIDEO }

        val languages = languagesOf(audios)
        val selected = when {
            languages.size <= 1 -> ""
            language != null && languages.any { it.code == language } -> language
            else -> defaultLanguage(audios, languages)
        }

        val out = ArrayList<Variant>()
        for (t in avs) out.add(single(t, caps, silent = false))
        if (audios.isEmpty()) {
            // Звука у видео нет вовсе: видео-дорожки и есть настоящий результат.
            if (avs.isEmpty()) for (t in videos) out.add(single(t, caps, silent = true))
        } else {
            for (v in videos) out.add(paired(v, audioFor(v, audios, selected, caps.sdk), caps, analysis.details.durationSec))
        }
        val sorted = out.sortedWith(compareByDescending<Variant> { it.tierHeight }
            .thenByDescending { Variant.fpsBucket(it.fps) }
            .thenBy { if (it.hdr) 1 else 0 }
            .thenByDescending { score(it) })
        val main = sorted.groupBy { it.groupKey }.values
            .map { group -> group.maxWith(compareBy<Variant> { score(it) }) }
            .sortedWith(compareByDescending<Variant> { it.tierHeight }
                .thenByDescending { Variant.fpsBucket(it.fps) }
                .thenBy { if (it.hdr) 1 else 0 })
        return FormatCatalog(analysis, languages, selected, sorted, main)
    }

    // ---------------- варианты ----------------

    private fun single(t: Track, caps: DeviceCaps, silent: Boolean): Variant {
        val support = when {
            t.drm -> Support.No("Видео защищено DRM")
            t.transport != Transport.HTTP -> Support.No(transportReason(t.transport))
            else -> Support.Ok
        }
        val (size, kind) = sizeOf(listOf(t))
        val th = tier(t.width, t.height)
        val pb = playbackOf(t, caps)
        return Variant(
            key = "f:${t.id}", video = t, audio = null,
            tier = tierLabel(th), tierHeight = th, width = t.width, height = t.height,
            fps = t.fps.roundFps(), dynamicRange = t.dynamicRange.ifBlank { "SDR" },
            outputContainer = t.container.ifBlank { t.ext }, outputExt = extOf(t),
            sizeBytes = size, sizeKind = kind, support = support,
            playback = pb.first, playbackNote = notes(pb.second, hdrNote(t)),
            needsMerge = false, silent = silent,
        )
    }

    private fun paired(v: Track, a: Track?, caps: DeviceCaps, durationSec: Long): Variant {
        val th = tier(v.width, v.height)
        val pb = playbackOf(v, caps)
        val comps = listOfNotNull(v, a)
        val (size, kind) = sizeOf(comps)
        var container = v.container
        val support: Support = when {
            a == null -> Support.No("Нет подходящей звуковой дорожки для этого видео")
            v.drm || a.drm -> Support.No("Видео защищено DRM")
            v.transport != Transport.HTTP -> Support.No(transportReason(v.transport))
            a.transport != Transport.HTTP -> Support.No(transportReason(a.transport))
            else -> {
                val r = MuxRoutes.route(v, a, caps.sdk)
                if (r.container == null) Support.No(r.reason)
                else {
                    container = r.container
                    if (r.container == "mp4" && caps.sdk < MuxRoutes.SDK_MP4_64BIT && size >= MuxRoutes.MP4_32BIT_LIMIT)
                        Support.No("Файл больше 4 ГБ: до Android 11 система не собирает MP4 такого размера")
                    else Support.Ok
                }
            }
        }
        return Variant(
            key = "v:${v.id}+a:${a?.id ?: "-"}", video = v, audio = a,
            tier = tierLabel(th), tierHeight = th, width = v.width, height = v.height,
            fps = v.fps.roundFps(), dynamicRange = v.dynamicRange.ifBlank { "SDR" },
            outputContainer = container, outputExt = if (container == "webm") "webm" else "mp4",
            sizeBytes = size, sizeKind = kind, support = support,
            playback = pb.first, playbackNote = notes(pb.second, hdrNote(v)),
            needsMerge = true, silent = false,
        )
    }

    private fun transportReason(t: Transport) = when (t) {
        Transport.HLS -> "Передаётся потоком HLS по частям — NOX пока скачивает только цельные файлы"
        Transport.DASH -> "Передаётся сегментами DASH — NOX пока скачивает только цельные файлы"
        else -> "Неподдерживаемый способ передачи"
    }

    private fun extOf(t: Track): String = when {
        t.ext.isNotBlank() -> t.ext
        t.container == "webm" -> "webm"
        else -> "mp4"
    }

    private fun Double.roundFps(): Int = if (this <= 0.0) 0 else Math.round(this).toInt()

    /** Сумма размеров. Точно — только если все части точные. */
    fun sizeOf(parts: List<Track>): Pair<Long, SizeKind> {
        if (parts.isEmpty() || parts.any { it.knownSize <= 0 }) {
            return (parts.sumOf { it.knownSize }) to SizeKind.UNKNOWN
        }
        val total = parts.sumOf { it.knownSize }
        return total to if (parts.all { it.filesizeExact && it.filesize > 0 }) SizeKind.EXACT else SizeKind.APPROX
    }

    private fun playbackOf(t: Track, caps: DeviceCaps): Pair<Playback, String> {
        if (t.vcodec.isBlank() || (t.width <= 0 && t.height <= 0)) return Playback.UNKNOWN to ""
        return when (val p = caps.decode(t.vcodec, t.width, t.height, t.fps)) {
            Playback.LIKELY, Playback.UNKNOWN -> p to ""
            Playback.SOFTWARE -> p to "Только программный декодер: воспроизведение может подтормаживать"
            Playback.UNLIKELY -> p to "Телефон может не справиться с воспроизведением этого варианта (скачать можно)"
        }
    }

    private fun hdrNote(t: Track): String =
        if (t.isHdr) "HDR (${t.dynamicRange}): на экране без HDR цвета могут выглядеть иначе" else ""

    private fun notes(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" ")

    // ---------------- звук ----------------

    private fun languagesOf(audios: List<Track>): List<AudioLanguage> {
        val byCode = audios.filter { it.language.isNotBlank() }.groupBy { it.language }
        return byCode.map { (code, list) ->
            AudioLanguage(code, languageName(code), list.any { it.audioRole == "original" || it.languagePreference > 0 })
        }.sortedWith(compareByDescending<AudioLanguage> { it.original }.thenBy { it.label })
    }

    private fun defaultLanguage(audios: List<Track>, languages: List<AudioLanguage>): String {
        val best = audios.filter { it.language.isNotBlank() }.maxWithOrNull(
            compareBy<Track> { roleScore(it.audioRole) }.thenBy { it.languagePreference })
        return best?.language ?: languages.first().code
    }

    fun languageName(code: String): String {
        val base = code.substringBefore('-').substringBefore('_')
        val name = runCatching { Locale.forLanguageTag(code).getDisplayLanguage(Locale("ru")) }.getOrDefault("")
        return when {
            name.isNotBlank() && !name.equals(code, true) && !name.equals(base, true) -> name.replaceFirstChar { it.titlecase(Locale("ru")) }
            else -> code
        }
    }

    private fun roleScore(role: String) = when (role) {
        "original" -> 3
        "default" -> 2
        "" -> 1
        "dubbed" -> 0
        else -> -1 // descriptive
    }

    /**
     * Звук к видео: та же «семья» контейнера (MP4 ↔ M4A, WebM ↔ WebM), затем
     * выбранный язык (или оригинальный), не DRC, затем лучший битрейт.
     * Язык никогда не выбирается «по битрейту» среди дубляжей.
     */
    private fun audioFor(v: Track, audios: List<Track>, language: String, sdk: Int): Track? {
        val family = audios.filter { MuxRoutes.sameFamily(v, it) }
        if (family.isEmpty()) return audios.maxWithOrNull(audioRank())
        val lang = if (language.isNotBlank()) family.filter { it.language == language }.ifEmpty { family } else family
        val routable = lang.filter { MuxRoutes.route(v, it, sdk).container != null }.ifEmpty { lang }
        return routable.maxWithOrNull(audioRank())
    }

    private fun audioRank() = compareBy<Track> { it.transport == Transport.HTTP }
        .thenBy { !it.drc }
        .thenBy { roleScore(it.audioRole) }
        .thenBy { it.languagePreference }
        .thenBy { if (it.abr > 0) it.abr else it.tbr }
        .thenBy { it.knownSize }

    // ---------------- порядок ----------------

    private fun playbackScore(p: Playback) = when (p) {
        Playback.LIKELY -> 3
        Playback.UNKNOWN -> 2
        Playback.SOFTWARE -> 1
        Playback.UNLIKELY -> 0
    }

    private fun codecScore(v: Variant): Int {
        val c = v.video.vcodec
        return if (v.tierHeight <= 1080) when (c) {
            "h264" -> 4; "vp9" -> 3; "h265" -> 2; "av1" -> 1; else -> 0
        } else when (c) {
            "vp9" -> 4; "av1" -> 3; "h265" -> 2; "h264" -> 1; else -> 0
        }
    }

    /** Чем больше, тем лучше вариант для основного списка. */
    private fun score(v: Variant): Long {
        var s = 0L
        if (v.support.ok) s += 1_000_000_000L
        // Среди недоступных показываем цельный файл: его причина — про телефон, а не про поток.
        if (v.video.transport == Transport.HTTP && (v.audio?.transport ?: Transport.HTTP) == Transport.HTTP) s += 500_000_000L
        s += playbackScore(v.playback) * 100_000_000L
        s += codecScore(v) * 10_000_000L
        if (!v.needsMerge) s += 5_000_000L
        val br = if (v.video.vbr > 0) v.video.vbr else v.video.tbr
        s += minOf(br.toLong(), 4_999_999L)
        return s
    }
}
