package com.nox.offline.core

/**
 * Разбор вставленного текста на ссылки: по одной на строку или вперемешку
 * с текстом (как приходит из «Поделиться»). Дубликаты внутри списка
 * отмечаются, строки без ссылки — тоже, чтобы пользователь видел всё.
 */
object LinkParser {
    enum class Kind { LINK, DUPLICATE, NOT_A_LINK }

    data class Line(val raw: String, val url: String?, val kind: Kind)

    private val urlRegex = Regex("https?://[^\\s<>\"'«»]+")

    fun lines(text: String): List<Line> {
        val out = ArrayList<Line>()
        val seen = HashSet<String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val found = urlRegex.findAll(line).map { it.value.trimEnd('.', ',', ')', ']', ';') }.toList()
            if (found.isEmpty()) {
                out.add(Line(line, null, Kind.NOT_A_LINK)); continue
            }
            for (u in found) {
                val key = normalize(u)
                out.add(Line(u, u, if (seen.add(key)) Kind.LINK else Kind.DUPLICATE))
            }
        }
        return out
    }

    /** Уникальные ссылки по порядку. */
    fun links(text: String): List<String> = lines(text).filter { it.kind == Kind.LINK }.mapNotNull { it.url }

    /** Ключ для сравнения: без схемы, «www.», «m.» и хвостового «/». */
    fun normalize(url: String): String =
        url.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").removePrefix("m.")
            .trimEnd('/')
}
