package com.nox.offline.media

/**
 * «Проверить файл»: определить контейнер по первым байтам (не по расширению)
 * и проверить его структуру. Только чтение; большой фильм не читается целиком.
 */
object ContainerCheck {
    private val MP4_TOP = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pdin", "styp", "sidx", "moof", "uuid")

    enum class Kind { MP4, WEBM, UNKNOWN }

    fun kindOf(src: ByteSource): Kind {
        val head = ByteArray(12)
        val n = src.read(0, head, 0, 12)
        if (n >= 4 && (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
            (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3) return Kind.WEBM
        if (n >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_TOP) return Kind.MP4
        return Kind.UNKNOWN
    }

    /**
     * [exactSize] — точный размер файла от источника, если он действительно
     * известен (не оценка), иначе -1.
     */
    fun check(src: ByteSource, exactSize: Long = -1, ctl: CheckControl = CheckControl.NONE): FileCheck {
        if (src.size == 0L) return FileCheck(FileCheck.Verdict.INCOMPLETE, "неизвестный", "Файл пустой (0 байт).", fileSize = 0)
        val reader = CachedReader(src)
        return when (kindOf(src)) {
            Kind.MP4 -> Mp4Inspector(reader, ctl).inspect(exactSize)
            Kind.WEBM -> WebmInspector(reader, ctl).inspect(exactSize)
            Kind.UNKNOWN -> {
                val head = ByteArray(16)
                val n = src.read(0, head, 0, 16)
                val hex = head.take(n).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                FileCheck(FileCheck.Verdict.UNKNOWN, "неизвестный",
                    "Начало файла не похоже ни на MP4, ни на WebM — возможно, это не видео или файл записан не с начала.",
                    details = listOf("первые байты: $hex"), fileSize = src.size, problemOffset = 0)
            }
        }
    }
}
