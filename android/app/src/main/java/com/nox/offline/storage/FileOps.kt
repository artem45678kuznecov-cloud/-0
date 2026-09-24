package com.nox.offline.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Потоковое копирование: гигабайты не читаются в память никогда. */
object FileOps {
    const val BUFFER = 256 * 1024

    class NotEnoughSpace(val needed: Long, val free: Long) :
        IOException("Недостаточно места: нужно ${needed / (1024 * 1024)} МБ, свободно ${free / (1024 * 1024)} МБ")

    /**
     * Копирует [input] в [output] кусками по [BUFFER]. Между кусками
     * проверяется отмена корутины. Возвращает число скопированных байт.
     */
    suspend fun copy(input: InputStream, output: OutputStream, total: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long {
        val buf = ByteArray(BUFFER)
        var copied = 0L
        var lastReport = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            copied += n
            if (copied - lastReport >= 4L * BUFFER) {
                lastReport = copied
                onProgress(copied, total)
            }
        }
        output.flush()
        onProgress(copied, total)
        return copied
    }

    /** Запас на служебные данные файловой системы. */
    fun requireSpace(needed: Long, free: Long) {
        if (needed <= 0 || free < 0) return
        val margin = 16L * 1024 * 1024
        if (free < needed + margin) throw NotEnoughSpace(needed, free)
    }

    /** Имя файла без путей и опасных символов — для импорта и SAF. */
    fun safeName(name: String?, fallback: String = "video.mp4"): String {
        val base = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_").trim(' ', '.')
        if (base.isEmpty() || base == "." || base == "..") return fallback
        return base.take(150)
    }
}
