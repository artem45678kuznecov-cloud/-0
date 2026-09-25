package com.nox.offline.core

/**
 * MIME по настоящему контейнеру файла. Нужен при записи через SAF: если
 * тип не совпадает с расширением, провайдер папки может дописать своё
 * расширение («видео.webm.mp4»), а другие приложения не откроют файл.
 */
object MediaTypes {
    fun mimeForExt(ext: String): String = when (ext.lowercase().trimStart('.')) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    fun mimeForName(name: String): String = mimeForExt(name.substringAfterLast('.', ""))
}
