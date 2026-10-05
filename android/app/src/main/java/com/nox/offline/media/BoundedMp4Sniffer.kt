package com.nox.offline.media

/**
 * Распознавание MP4 по заголовкам боксов с ограниченной памятью.
 *
 * Стандартный Sniffer из Media3 1.5.1, встретив moov, расширяет область поиска
 * на весь его размер и пропускает содержимое через advancePeekPosition. У
 * DefaultExtractorInput это не перемотка: все просмотренные байты копируются в
 * растущий peekBuffer и остаются там до чтения (resetPeekPosition только
 * обнуляет позицию просмотра). У марафона с moov ~217 МиБ в начале файла это
 * запрос ~228 МБ Java-кучи ещё до первой таблицы сэмплов.
 *
 * Здесь заголовки читаются по смещениям из отдельного источника только на
 * чтение ([ByteSource], pread), содержимое боксов пропускается переходом к
 * следующему смещению и не читается. В памяти одновременно: окно чтения
 * [READ_WINDOW] байт, до [MAX_FTYP_BRANDS] брендов ftyp (4 КиБ) и несколько
 * чисел — независимо от размеров moov и mdat.
 *
 * Правила те же, что у Media3: совместимый бренд ftyp (или файл QuickTime без
 * ftyp, но с mdat); фрагментированный файл — если есть moof или mvex на
 * верхнем уровне либо mvex среди детей moov. В отличие от Media3, поиск не
 * обрывается на первых 4 КиБ: moov в конце файла, после mdat, тоже находится.
 */
object BoundedMp4Sniffer {
    /** Окно позиционного чтения заголовков. Единственный буфер распознавания. */
    const val READ_WINDOW = 4 * 1024

    /** Сколько байт сверяется через ExtractorInput плеера (начало файла). */
    const val INPUT_PEEK_BYTES = 32

    /**
     * Предел памяти распознавания: окно чтения + бренды ftyp + служебное.
     * С запасом для объектов JVM; проверяется тестом.
     */
    const val MEMORY_BUDGET_BYTES = 64 * 1024

    const val MAX_TOP_LEVEL_BOXES = 65_536
    const val MAX_MOOV_CHILDREN = 1_000_000
    const val MAX_FTYP_BRANDS = 1024

    /** Как в Sniffer Media3 1.5.1 (COMPATIBLE_BRANDS). */
    private val COMPATIBLE_BRANDS = intArrayOf(
        0x69736f6d, 0x69736f32, 0x69736f33, 0x69736f34, 0x69736f35, 0x69736f36, 0x69736f39, // isom iso2..iso9
        0x61766331, 0x68766331, 0x68657631, 0x61763031, // avc1 hvc1 hev1 av01
        0x6d703431, 0x6d703432, // mp41 mp42
        0x33673261, 0x33673262, 0x33677236, 0x33677336, 0x33676536, 0x33676736, // 3g2a 3g2b 3gr6 3gs6 3ge6 3gg6
        0x4d345620, 0x4d344120, 0x66347620, 0x6b646469, 0x4d345650, // "M4V " "M4A " "f4v " kddi M4VP
        0x71742020, 0x4d534e56, 0x64627931, 0x69736d6c, 0x70696666, // "qt  " MSNV dby1 isml piff
    )

    private const val TYPE_FTYP = 0x66747970
    private const val TYPE_MOOV = 0x6d6f6f76
    private const val TYPE_MOOF = 0x6d6f6f66
    private const val TYPE_MVEX = 0x6d766578
    private const val TYPE_MDAT = 0x6d646174

    /** Итог распознавания. [failure] — почему файл не принят (для журнала), или null. */
    class Scan(
        /** Структура MP4 и совместимый бренд (или QuickTime без ftyp, но с mdat). */
        val isMp4: Boolean,
        val fragmented: Boolean,
        /** Начало и полный размер moov (с заголовком) или -1. */
        val moovPos: Long,
        val moovSize: Long,
        /** mdat встретился до moov (moov в конце файла). */
        val moovAfterMdat: Boolean,
        val failure: String?,
        /** Сколько заголовков прочитано (для журнала и тестов). */
        val boxes: Int,
    ) {
        /** Обычный (не фрагментированный) MP4 с индексом — то, что читает [LargeMp4Extractor]. */
        val unfragmentedWithMoov: Boolean get() = isMp4 && !fragmented && moovPos >= 0

        override fun toString() = "Scan(mp4=$isMp4, fragmented=$fragmented, moov=$moovPos+$moovSize, " +
            "moovAfterMdat=$moovAfterMdat, boxes=$boxes, failure=$failure)"
    }

    /** Заголовок бокса; один на весь проход — без объекта на каждый из сотен тысяч боксов. */
    private class Header {
        var size = 0L
        var type = 0
        var headerSize = 0
    }

    /**
     * Прочитать заголовок бокса на [pos] в [h]; false — за концом. Размер 0 — до
     * [end]; размер 1 — 64-битный (без знака; больше Long.MAX_VALUE — size = -1).
     */
    private fun header(r: CachedReader, pos: Long, end: Long, h: Header): Boolean {
        if (pos < 0 || pos + 8 > end) return false
        var size = r.u32(pos)
        h.type = r.u32(pos + 4).toInt()
        h.headerSize = 8
        if (size == 1L) {
            if (pos + 16 > end) return false
            val hi = r.u32(pos + 8)
            val lo = r.u32(pos + 12)
            h.headerSize = 16
            size = if (hi and 0x80000000L != 0L) -1 else (hi shl 32) or lo
        } else if (size == 0L) {
            size = end - pos
        }
        h.size = size
        return true
    }

    private fun fourcc(type: Int): String = String(byteArrayOf((type ushr 24).toByte(), (type ushr 16).toByte(),
        (type ushr 8).toByte(), type.toByte()), Charsets.ISO_8859_1)

    private fun isCompatibleBrand(brand: Int): Boolean {
        if (brand ushr 8 == 0x00336770) return true // "3gp*"
        return COMPATIBLE_BRANDS.contains(brand)
    }

    /** Распознать файл [src] с начала. Читает только заголовки боксов. */
    fun scan(src: ByteSource): Scan {
        val r = CachedReader(src, READ_WINDOW)
        val size = src.size
        var pos = 0L
        var boxes = 0
        var topBoxes = 0
        var goodType = false
        var sawFtyp = false
        var fragmented = false
        var moovPos = -1L
        var moovSize = -1L
        var sawMdat = false
        var moovAfterMdat = false
        fun fail(why: String) = Scan(false, fragmented, moovPos, moovSize, moovAfterMdat, why, boxes)
        val h = Header()
        val ch = Header()

        while (pos < size) {
            if (topBoxes >= MAX_TOP_LEVEL_BOXES) return fail("больше $MAX_TOP_LEVEL_BOXES боксов верхнего уровня")
            if (!header(r, pos, size, h)) break
            topBoxes++
            boxes++
            if (h.size < 0) return fail("размер бокса ${fourcc(h.type)} больше 2^63")
            if (h.size < h.headerSize) return fail("размер бокса ${fourcc(h.type)} (${h.size}) меньше заголовка")
            val end = if (h.size > size - pos) size + 1 else pos + h.size // за концом файла: дальше не идём
            when (h.type) {
                TYPE_FTYP -> if (!sawFtyp) {
                    sawFtyp = true
                    val payload = h.size - h.headerSize
                    if (payload < 8) return fail("ftyp короче 8 байт")
                    if (pos + h.headerSize + 8 > size) return fail("ftyp обрезан")
                    val major = r.u32(pos + h.headerSize).toInt()
                    if (isCompatibleBrand(major)) goodType = true
                    // Совместимые бренды: не больше MAX_FTYP_BRANDS, только в пределах файла.
                    val count = minOf((payload - 8) / 4, MAX_FTYP_BRANDS.toLong()).toInt()
                    var p = pos + h.headerSize + 8
                    var i = 0
                    while (!goodType && i < count && p + 4 <= size) {
                        if (isCompatibleBrand(r.u32(p).toInt())) goodType = true
                        p += 4
                        i++
                    }
                    if (!goodType) return fail("бренды ftyp не поддерживаются (основной ${fourcc(major)})")
                }
                TYPE_MDAT -> {
                    // QuickTime без ftyp: mdat — признак MP4 (как в Media3).
                    goodType = true
                    sawMdat = true
                }
                TYPE_MOOF, TYPE_MVEX -> {
                    fragmented = true
                    break
                }
                TYPE_MOOV -> if (moovPos < 0) {
                    if (end > size) return fail("moov обрезан: заявлено ${h.size} байт, в файле до конца ${size - pos}")
                    moovPos = pos
                    moovSize = h.size
                    moovAfterMdat = sawMdat
                    // Дети moov: только заголовки — есть ли mvex.
                    var c = pos + h.headerSize
                    var children = 0
                    while (c < end) {
                        if (children >= MAX_MOOV_CHILDREN) return fail("больше $MAX_MOOV_CHILDREN боксов в moov")
                        if (!header(r, c, end, ch)) return fail("moov: обрезанный заголовок на $c")
                        children++
                        if (ch.size < 0 || ch.size < ch.headerSize) return fail("moov: неверный размер бокса ${fourcc(ch.type)} на $c")
                        if (ch.size > end - c) return fail("moov: бокс ${fourcc(ch.type)} выходит за moov")
                        if (ch.type == TYPE_MVEX) {
                            fragmented = true
                            break
                        }
                        c += ch.size
                    }
                    boxes += children
                    if (fragmented) break
                }
            }
            if (end > size) break
            pos = end
        }
        if (!goodType) return fail(if (sawFtyp) "бренды ftyp не поддерживаются" else "нет ftyp и mdat — не MP4")
        return Scan(true, fragmented, moovPos, moovSize, moovAfterMdat, null, boxes)
    }
}
