package com.nox.offline.subtitles

/**
 * Разбор SRT и WebVTT без сети и без Android: чистая функция для JVM-тестов.
 * Ограничен по объёму и числу реплик, чтобы битый или огромный файл не
 * подвесил приложение. Часы не ограничены 24 — многосуточные ролики
 * поддерживаются; время — Long в миллисекундах.
 */
object SubtitleParser {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    class SubtitleException(message: String) : Exception(message)

    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_CUES = 60_000
    private const val MAX_LINE = 4_000

    private val timing = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val tags = Regex("""<[^>]{1,200}>""")

    fun parseTime(t: String): Long {
        val parts = t.trim().replace(',', '.').split(':')
        val secPart = parts.last()
        val sec = secPart.substringBefore('.').toLong()
        val fracRaw = secPart.substringAfter('.', "0")
        val ms = fracRaw.padEnd(3, '0').take(3).toLong()
        var total = sec
        var mult = 60L
        for (p in parts.dropLast(1).reversed()) {
            total += p.toLong() * mult
            mult *= 60
        }
        return total * 1000 + ms
    }

    /** Формат по содержимому: WEBVTT-заголовок или SRT. */
    fun detectFormat(text: String): String =
        if (text.trimStart('﻿', ' ', '\n', '\r').startsWith("WEBVTT")) "vtt" else "srt"

    fun parse(text: String): List<Cue> {
        if (text.length > MAX_BYTES) throw SubtitleException("Файл субтитров слишком большой")
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = timing.find(lines[i].take(MAX_LINE))
            if (m == null) { i++; continue }
            val start = runCatching { parseTime(m.groupValues[1]) }.getOrNull()
            val end = runCatching { parseTime(m.groupValues[2]) }.getOrNull()
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (timing.containsMatchIn(lines[i])) break
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i].take(MAX_LINE))
                i++
            }
            if (start == null || end == null || end <= start) continue
            val clean = tags.replace(sb.toString(), "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").trim()
            if (clean.isEmpty()) continue
            cues.add(Cue(start, end, clean))
            if (cues.size > MAX_CUES) throw SubtitleException("Слишком много реплик в файле")
        }
        cues.sortBy { it.startMs }
        return cues
    }

    /**
     * Реплики на экране в момент [timeMs] с учётом сдвига [offsetMs]:
     * положительный сдвиг — субтитры показываются позже.
     */
    fun at(cues: List<Cue>, timeMs: Long, offsetMs: Long = 0): List<Cue> {
        if (cues.isEmpty()) return emptyList()
        val t = timeMs - offsetMs
        // Бинарный поиск последней реплики, начавшейся не позже t.
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return emptyList()
        val out = ArrayList<Cue>(2)
        var k = idx
        // Перекрывающиеся реплики: смотрим немного назад.
        while (k >= 0 && idx - k < 8) {
            val c = cues[k]
            if (t in c.startMs until c.endMs) out.add(0, c)
            k--
        }
        return out
    }

    /** Время записи для VTT: «HH:MM:SS.mmm» (часов может быть больше 24). */
    fun vttTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d.%03d".format(t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
    }
}
