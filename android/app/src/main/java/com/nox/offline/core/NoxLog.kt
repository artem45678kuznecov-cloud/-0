package com.nox.offline.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Чёрный ящик NOX: кольцевой журнал событий в памяти.
 *
 * Сюда пишут загрузчик, сервисы и плеер. Никаких секретов: адреса
 * проходят через [SafeUrl.redact], cookie сюда не попадают вообще.
 * Содержимое отдаётся кнопкой «Скопировать диагностику» в настройках.
 *
 * Класс не зависит от Android, чтобы его можно было использовать и в
 * JVM-тестах; вывод в logcat подключается снаружи через [sink].
 */
object NoxLog {
    private const val LIMIT = 600
    private val entries = ArrayDeque<String>(LIMIT)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Куда дублировать строки (в приложении — android.util.Log). */
    @Volatile
    var sink: ((String) -> Unit)? = null

    @Synchronized
    fun event(kind: String, vararg fields: Pair<String, Any?>) {
        val body = if (fields.isEmpty()) "" else fields.joinToString(" ") { (k, v) -> "$k=${v ?: "-"}" }
        val line = "${stamp.format(Date())} $kind $body".trimEnd()
        if (entries.size >= LIMIT) entries.pollFirst()
        entries.addLast(line)
        sink?.invoke(line)
    }

    @Synchronized
    fun dump(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
