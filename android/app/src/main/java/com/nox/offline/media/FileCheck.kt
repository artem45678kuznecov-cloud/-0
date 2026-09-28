package com.nox.offline.media

/**
 * Итог проверки файла. Вывод строится только из прочитанного: если что-то
 * не проверялось, это видно в [details] и [limits], а не додумывается.
 */
data class FileCheck(
    val verdict: Verdict,
    /** Контейнер по содержимому файла, а не по расширению: «MP4», «WebM», «неизвестный». */
    val container: String,
    val summary: String,
    val details: List<String> = emptyList(),
    /** Чего проверка не делала — честно, в тексте для человека. */
    val limits: List<String> = emptyList(),
    val tracks: List<String> = emptyList(),
    val durationMs: Long = 0,
    val fileSize: Long = 0,
    /** Смещение первой найденной проблемы или -1. */
    val problemOffset: Long = -1,
    /** Время (мс) первой проблемы, если оно известно, или -1. */
    val problemTimeMs: Long = -1,
    val repair: Repair = Repair.NONE,
) {
    enum class Verdict(val title: String) {
        READABLE("Файл читается, проблема воспроизведения"),
        STRUCTURE("Обнаружено нарушение структуры"),
        INCOMPLETE("Файл неполный"),
        UNKNOWN("Причина пока не установлена"),
    }

    /** Что можно сделать с файлом локально, не трогая оригинал. */
    enum class Repair(val label: String) {
        NONE(""),
        /** MP4: индекс со смещениями, обёрнутыми через 4 ГБ, — пересобрать индекс (co64), кадры копируются как есть. */
        MP4_REBUILD_INDEX("Пересобрать индекс MP4"),
    }

    /** Текст для «Скопировать диагностику». */
    fun report(): String = buildString {
        append("Проверка файла: ").append(verdict.title).append('\n')
        append("Контейнер: ").append(container).append(", размер ").append(fileSize).append(" байт\n")
        append(summary).append('\n')
        if (durationMs > 0) append("Длительность по индексу: ").append(durationMs / 1000).append(" с\n")
        tracks.forEach { append("Дорожка: ").append(it).append('\n') }
        if (problemOffset >= 0) append("Смещение проблемы: ").append(problemOffset).append('\n')
        if (problemTimeMs >= 0) append("Время проблемы: ").append(problemTimeMs / 1000).append(" с\n")
        details.forEach { append("• ").append(it).append('\n') }
        limits.forEach { append("Не проверялось: ").append(it).append('\n') }
        if (repair != Repair.NONE) append("Доступно: ").append(repair.label).append('\n')
    }
}

/** Отмена проверки/восстановления пользователем. */
class CheckCancelled : Exception("проверка отменена")
