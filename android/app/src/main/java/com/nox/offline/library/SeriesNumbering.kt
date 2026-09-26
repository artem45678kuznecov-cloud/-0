package com.nox.offline.library

/**
 * Предложение номера сезона и серии по названию файла. Только
 * подсказка: пользователь видит её и исправляет, библиотека сама ничего
 * не переименовывает и не пересортировывает.
 */
object SeriesNumbering {
    data class Guess(val season: Int, val episode: Int)

    private val patterns: List<Pair<Regex, (MatchResult) -> Guess>> = listOf(
        // S02E05, s2e5, S02 E05
        Regex("""(?iu)\bs(\d{1,2})\s*[ ._-]?\s*e(\d{1,4})\b""") to { m -> Guess(m.i(1), m.i(2)) },
        // 2x05
        Regex("""(?iu)\b(\d{1,2})x(\d{1,3})\b""") to { m -> Guess(m.i(1), m.i(2)) },
        // «2 сезон 5 серия», «сезон 2 серия 5»
        Regex("""(?iu)(\d{1,2})\s*(?:-?й\s*)?сезон\D{0,12}?(\d{1,4})\s*(?:-?я\s*)?серия""") to { m -> Guess(m.i(1), m.i(2)) },
        Regex("""(?iu)сезон\s*(\d{1,2})\D{0,12}?серия\s*(\d{1,4})""") to { m -> Guess(m.i(1), m.i(2)) },
        Regex("""(?iu)season\s*(\d{1,2})\D{0,12}?episode\s*(\d{1,4})""") to { m -> Guess(m.i(1), m.i(2)) },
        // «5 серия», «серия 5», «Episode 5», «Ep. 5», «#5»
        Regex("""(?iu)(\d{1,4})\s*(?:-?я\s*)?серия""") to { m -> Guess(0, m.i(1)) },
        Regex("""(?iu)серия\s*(\d{1,4})""") to { m -> Guess(0, m.i(1)) },
        Regex("""(?iu)\b(?:episode|ep)\.?\s*(\d{1,4})\b""") to { m -> Guess(0, m.i(1)) },
        Regex("""#(\d{1,4})\b""") to { m -> Guess(0, m.i(1)) },
    )

    private fun MatchResult.i(g: Int) = groupValues[g].toInt()

    /** Сезон 0 — не распознан (подставится текущий сезон), серия 0 — не распознана. */
    fun guess(title: String): Guess? {
        for ((re, f) in patterns) {
            val m = re.find(title) ?: continue
            val g = f(m)
            if (g.episode in 1..9999 && g.season in 0..99) return g
        }
        return null
    }
}
