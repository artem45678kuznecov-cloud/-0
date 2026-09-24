package com.nox.offline.core

import java.io.File

/**
 * Имена файлов медиатеки: `<название> [<id>].mp4`, как в nox_download.py.
 * Ничего, что могло бы сломать файловую систему или выйти из каталога.
 */
object FileNames {
    private val forbidden = Regex("[\\\\/:*?\"<>|]")
    private val control = Regex("[\\x00-\\x1f]")
    private val spaces = Regex("\\s+")

    fun sanitize(text: String?, limit: Int = 120): String {
        var t = (text ?: "").trim()
        t = forbidden.replace(t, "_")
        t = control.replace(t, "")
        t = spaces.replace(t, " ").trim(' ', '.')
        if (t.isEmpty()) t = "video"
        return t.take(limit).trim(' ', '.')
    }

    fun targetName(title: String?, videoId: String?, ext: String?): String {
        val cleanExt = sanitize(ext ?: "mp4", 8).trimStart('.').ifEmpty { "mp4" }
        val name = sanitize(title)
        val rawId = (videoId ?: "").trim()
        val vid = if (rawId.isEmpty()) "" else sanitize(rawId, 40)
        val stem = if (vid.isEmpty()) name else "$name [$vid]"
        return "$stem.$cleanExt"
    }

    /** Если такой файл уже есть — добавить счётчик, а не перезаписать. */
    fun unique(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (n < 1000) {
            val f = File(dir, "$stem ($n)$ext")
            if (!f.exists()) return f
            n++
        }
        return File(dir, "$stem (${System.currentTimeMillis()})$ext")
    }

    fun partOf(finalFile: File): File = File(finalFile.parentFile, finalFile.name + ".part")
}
