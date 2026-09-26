package com.nox.offline.core

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Где лежат файлы NOX на Android.
 *
 * App-specific external storage: Android/data/com.nox.offline/files/NOX/.
 * Разрешений на него не нужно, «доступ ко всем файлам» не запрашивается.
 * Если внешнего хранилища нет (редкий случай), используется внутреннее.
 *
 *   Downloads/  .part и незавершённые файлы
 *   Media/      готовые видео
 *   Covers/     обложки
 *   Metadata/   журнал и служебные файлы
 *   Subtitles/  дорожки субтитров
 */
class Storage(context: Context) {
    val root: File = (context.getExternalFilesDir(null) ?: context.filesDir).let { File(it, "NOX") }
    val downloads: File get() = ensure(File(root, "Downloads"))
    val media: File get() = ensure(File(root, "Media"))
    val covers: File get() = ensure(File(root, "Covers"))
    val metadata: File get() = ensure(File(root, "Metadata"))
    /** Субтитры (скачанные и свои) — внутри NOX, чтобы не зависеть от прав на папки. */
    val subtitles: File get() = ensure(File(root, "Subtitles"))

    private fun ensure(dir: File): File {
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    data class Space(val freeBytes: Long, val totalBytes: Long, val usedByNox: Long)

    fun space(): Space {
        val free: Long
        val total: Long
        try {
            val stat = StatFs(ensure(root).absolutePath)
            free = stat.availableBytes
            total = stat.totalBytes
        } catch (e: Exception) {
            return Space(-1, -1, sizeOf(root))
        }
        return Space(free, total, sizeOf(root))
    }

    private fun sizeOf(dir: File): Long {
        var sum = 0L
        dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum
    }
}
