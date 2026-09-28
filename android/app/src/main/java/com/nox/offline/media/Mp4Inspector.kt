package com.nox.offline.media

/**
 * Проверка MP4 (ISO BMFF) по содержимому, только чтением.
 *
 * Что проверяется: цепочка боксов верхнего уровня до конца файла; индекс
 * `moov` и его таблицы (stsd, stts, stsz/stz2, stsc, stco/co64); каждый кадр
 * каждой дорожки — что он целиком лежит внутри файла и внутри области данных
 * `mdat`; для H.264/H.265 — цепочка длин NAL внутри каждого кадра (ровно на
 * этом условии Media3 выдаёт «Invalid NAL length», ERROR_CODE_PARSING_CONTAINER_MALFORMED).
 * Для 32-битных смещений (stco) в файле больше 4 ГБ отдельно ищется
 * «обёртывание» смещения через 2^32.
 *
 * Кадры не декодируются: проверка структурная. Это сказано в [FileCheck.limits].
 */
class Mp4Inspector(private val r: CachedReader, private val ctl: CheckControl) {

    class TopBox(val type: String, val pos: Long, val header: Int, val size: Long) {
        val end get() = pos + size
        val dataStart get() = pos + header
    }

    /** Разобранная дорожка: всё, что нужно для обхода кадров и для пересборки индекса. */
    class Track(
        val index: Int,
        val handler: String,
        val codec: String,
        val width: Int,
        val height: Int,
        val timescale: Long,
        val duration: Long,
        val sampleSizes: IntArray,
        val chunkOffsets: LongArray,
        val co64: Boolean,
        /** stsc: тройки first_chunk (с 1), samples_per_chunk, sample_description_index. */
        val stsc: IntArray,
        val sttsCounts: IntArray,
        val sttsDeltas: IntArray,
        /** Длина поля размера NAL (1, 2 или 4) для H.264/H.265, иначе 0. */
        val nalLength: Int,
        /** Смещение бокса stco/co64 внутри moov (от начала moov). */
        val chunkBoxOffsetInMoov: Int,
    ) {
        val sampleCount get() = sampleSizes.size
        val isVideo get() = handler == "vide"
        val durationMs get() = if (timescale > 0) duration * 1000 / timescale else 0
        fun label(): String = buildString {
            append(if (isVideo) "видео " else if (handler == "soun") "звук " else "$handler ")
            append(codec)
            if (width > 0) append(" ${width}×$height")
            append(", кадров $sampleCount, ${durationMs / 1000} с, смещения ${if (co64) "64-битные" else "32-битные"}")
        }
    }

    /** Результат разбора индекса — нужен и проверке, и пересборке. */
    class Index(
        val top: List<TopBox>,
        val moov: TopBox,
        val moovBytes: ByteArray,
        val tracks: List<Track>,
        /** Области данных: [начало полезной нагрузки mdat, конец). */
        val dataRegions: List<LongRange>,
        val mdatSizeWrapped: Boolean,
    )

    private val size = r.size
    private val details = ArrayList<String>()
    /** Длины NAL читаются в начале каждого кадра: маленькими порциями, а не окнами по килобайтам. */
    private val nal = CachedReader(r.source, 512)

    fun inspect(exactSize: Long): FileCheck {
        val limits = listOf("кадры не декодировались — проверена структура, а не изображение")
        val top = walkTop()
        details.addAll(top.notes)
        val moov = top.boxes.firstOrNull { it.type == "moov" && it.end <= size } ?: findTrailingMoov(top)
        if (top.boxes.any { it.type == "moof" }) {
            return result(FileCheck.Verdict.UNKNOWN, "Фрагментированный MP4: проверка кадров для такого вида пока не сделана.",
                limits = limits + "кадры фрагментированного MP4")
        }
        if (moov == null) {
            val truncated = top.truncated
            return if (truncated != null || top.problemAt >= 0) {
                result(FileCheck.Verdict.INCOMPLETE, "Нет индекса кадров (moov): файл не дописан или сборка не была завершена.",
                    problemOffset = truncated?.pos ?: top.problemAt, limits = limits)
            } else {
                result(FileCheck.Verdict.INCOMPLETE, "В файле нет индекса кадров (moov) — без него MP4 нельзя воспроизвести.", limits = limits)
            }
        }
        if (moov.size > MAX_MOOV) {
            return result(FileCheck.Verdict.UNKNOWN, "Индекс moov слишком велик для проверки (${moov.size / (1024 * 1024)} МБ).", limits = limits)
        }
        val index = try {
            parseIndex(top, moov)
        } catch (e: Mp4Broken) {
            return result(FileCheck.Verdict.STRUCTURE, "Индекс кадров (moov) повреждён: ${e.message}", problemOffset = moov.pos, limits = limits)
        }
        lastIndex = index
        if (index.tracks.isEmpty()) {
            return result(FileCheck.Verdict.STRUCTURE, "В индексе нет ни одной дорожки.", problemOffset = moov.pos, limits = limits)
        }
        if (index.tracks.any { it.codec == "encv" || it.codec == "enca" }) {
            details.add("дорожка зашифрована (encv/enca) — такой файл без ключа не воспроизводится")
        }
        return checkSamples(index, exactSize, limits)
    }

    /** Индекс последней проверки — для пересборки. */
    var lastIndex: Index? = null
        private set

    // ------------------------------------------------------------------
    //  Верхний уровень
    // ------------------------------------------------------------------

    private class TopScan(val boxes: List<TopBox>, val truncated: TopBox?, val problemAt: Long, val notes: List<String>)

    private fun walkTop(): TopScan {
        val boxes = ArrayList<TopBox>()
        val notes = ArrayList<String>()
        var pos = 0L
        var truncated: TopBox? = null
        var problemAt = -1L
        while (pos < size) {
            ctl.check()
            if (size - pos < 8) {
                notes.add("в конце файла ${size - pos} лишних байт")
                break
            }
            val s32 = r.u32(pos)
            val type = r.fourcc(pos + 4)
            var header = 8
            var boxSize = s32
            if (s32 == 1L) {
                if (!r.has(pos, 16)) { truncated = TopBox(type, pos, 16, 16); break }
                boxSize = r.u64(pos + 8)
                header = 16
            } else if (s32 == 0L) {
                boxSize = size - pos
            }
            if (!printable(type)) {
                problemAt = pos
                notes.add("на смещении $pos вместо заголовка бокса — данные, не похожие на MP4")
                break
            }
            if (boxSize < header) {
                problemAt = pos
                notes.add("бокс «$type» на $pos объявлен размером $boxSize — меньше собственного заголовка")
                break
            }
            val box = TopBox(type, pos, header, boxSize)
            boxes.add(box)
            if (box.end > size) {
                truncated = box
                notes.add("бокс «$type» объявлен до ${box.end}, а файл кончается на $size (не хватает ${box.end - size} байт)")
                break
            }
            pos = box.end
        }
        return TopScan(boxes, truncated, problemAt, notes)
    }

    /**
     * moov в конце файла, если цепочка боксов оборвалась раньше (например,
     * размер mdat записан 32-битным полем и обернулся через 4 ГБ).
     */
    private fun findTrailingMoov(top: TopScan): TopBox? {
        if (top.problemAt < 0 && top.truncated == null) return null
        val from = maxOf(top.boxes.lastOrNull()?.dataStart ?: 0L, size - MAX_MOOV)
        val chunk = 1 shl 20
        var end = size
        while (end > from) {
            ctl.check()
            val start = maxOf(from, end - chunk)
            val buf = r.bytes(start, (end - start).toInt())
            for (i in buf.size - 4 downTo 4) {
                if (buf[i] == 'm'.code.toByte() && buf[i + 1] == 'o'.code.toByte() && buf[i + 2] == 'o'.code.toByte() &&
                    buf[i + 3] == 'v'.code.toByte()) {
                    val boxPos = start + i - 4
                    val s = r.u32(boxPos)
                    if (s >= 8 && boxPos + s == size) {
                        details.add("индекс moov найден в конце файла на $boxPos, хотя цепочка боксов до него не доходит")
                        return TopBox("moov", boxPos, 8, s)
                    }
                }
            }
            end = start + 8
            if (start == from) break
        }
        return null
    }

    // ------------------------------------------------------------------
    //  Индекс
    // ------------------------------------------------------------------

    private class Mp4Broken(message: String) : Exception(message)

    private fun parseIndex(top: TopScan, moov: TopBox): Index {
        val bytes = r.bytes(moov.pos, moov.size.toInt())
        val m = Bytes(bytes)
        val tracks = ArrayList<Track>()
        for (trak in m.children(moov.header, bytes.size).filter { it.type == "trak" }) {
            ctl.check()
            tracks.add(parseTrak(m, trak, tracks.size))
        }
        // Области данных: полезная нагрузка каждого mdat. Если размер mdat обернулся
        // (moov найден в конце, а цепочка оборвалась сразу после mdat), область — до moov.
        val regions = ArrayList<LongRange>()
        var wrapped = false
        val mdats = top.boxes.filter { it.type == "mdat" }
        for ((i, md) in mdats.withIndex()) {
            val last = i == mdats.lastIndex
            if (last && top.problemAt >= 0 && moov.pos > md.end && top.problemAt == md.end) {
                val real = moov.pos - md.pos
                wrapped = (real and 0xFFFFFFFFL) == (md.size and 0xFFFFFFFFL)
                details.add(if (wrapped) "размер mdat записан 32-битным полем и обернулся: записано ${md.size}, на деле $real"
                else "размер mdat (${md.size}) не совпадает с началом индекса moov")
                regions.add(md.dataStart until moov.pos)
            } else {
                regions.add(md.dataStart until minOf(md.end, size))
            }
        }
        return Index(top.boxes, moov, bytes, tracks, regions, wrapped)
    }

    private fun parseTrak(m: Bytes, trak: Bytes.Box, number: Int): Track {
        val mdia = m.child(trak, "mdia") ?: throw Mp4Broken("в дорожке ${number + 1} нет mdia")
        val hdlr = m.child(mdia, "hdlr") ?: throw Mp4Broken("в дорожке ${number + 1} нет hdlr")
        val handler = m.fourcc(hdlr.dataStart + 8)
        val mdhd = m.child(mdia, "mdhd") ?: throw Mp4Broken("в дорожке ${number + 1} нет mdhd")
        val v1 = m.u8(mdhd.dataStart) == 1
        val timescale = if (v1) m.u32(mdhd.dataStart + 20) else m.u32(mdhd.dataStart + 12)
        val duration = if (v1) m.u64(mdhd.dataStart + 24) else m.u32(mdhd.dataStart + 16)
        val stbl = m.child(m.child(mdia, "minf") ?: throw Mp4Broken("нет minf"), "stbl") ?: throw Mp4Broken("нет stbl")

        val stsd = m.child(stbl, "stsd") ?: throw Mp4Broken("нет описания кодека (stsd)")
        val entryPos = stsd.dataStart + 8
        need(m, entryPos + 8 <= stsd.end, "stsd пуст")
        val entrySize = m.u32(entryPos).toInt()
        val codec = m.fourcc(entryPos + 4)
        var width = 0
        var height = 0
        var nal = 0
        if (handler == "vide" && entrySize >= 86) {
            width = m.u16(entryPos + 8 + 24)
            height = m.u16(entryPos + 8 + 26)
            val entryEnd = minOf(entryPos + entrySize, stsd.end)
            for (c in m.children(entryPos + 8 + 78, entryEnd)) {
                when (c.type) {
                    "avcC" -> nal = (m.u8(c.dataStart + 4) and 3) + 1
                    "hvcC" -> nal = (m.u8(c.dataStart + 21) and 3) + 1
                }
            }
        }

        val sizes: IntArray = m.child(stbl, "stsz")?.let { b ->
            val constant = m.u32(b.dataStart + 4)
            val count = m.u32(b.dataStart + 8).toInt()
            if (constant > 0) IntArray(count) { constant.toInt() } else {
                need(m, b.dataStart + 12 + 4L * count <= b.end, "таблица размеров кадров (stsz) короче заявленного")
                IntArray(count) { m.u32(b.dataStart + 12 + 4 * it).toInt() }
            }
        } ?: m.child(stbl, "stz2")?.let { b ->
            val field = m.u8(b.dataStart + 7)
            val count = m.u32(b.dataStart + 8).toInt()
            val base = b.dataStart + 12
            IntArray(count) { i ->
                when (field) {
                    4 -> (m.u8(base + i / 2) shr (if (i % 2 == 0) 4 else 0)) and 0xF
                    8 -> m.u8(base + i)
                    16 -> m.u16(base + 2 * i)
                    else -> throw Mp4Broken("stz2 с полем $field бит")
                }
            }
        } ?: throw Mp4Broken("нет таблицы размеров кадров (stsz)")

        val stco = m.child(stbl, "stco")
        val co64 = m.child(stbl, "co64")
        val chunkBox = co64 ?: stco ?: throw Mp4Broken("нет таблицы смещений (stco/co64)")
        val chunkCount = m.u32(chunkBox.dataStart + 4).toInt()
        val width8 = if (co64 != null) 8 else 4
        need(m, chunkBox.dataStart + 8 + width8.toLong() * chunkCount <= chunkBox.end, "таблица смещений короче заявленного")
        val offsets = LongArray(chunkCount) { i ->
            if (co64 != null) m.u64(chunkBox.dataStart + 8 + 8 * i) else m.u32(chunkBox.dataStart + 8 + 4 * i)
        }

        val stscBox = m.child(stbl, "stsc") ?: throw Mp4Broken("нет таблицы кадров в чанках (stsc)")
        val stscCount = m.u32(stscBox.dataStart + 4).toInt()
        need(m, stscBox.dataStart + 8 + 12L * stscCount <= stscBox.end, "таблица stsc короче заявленного")
        val stsc = IntArray(stscCount * 3) { m.u32(stscBox.dataStart + 8 + 4 * it).toInt() }
        need(m, stscCount == 0 || stsc[0] == 1, "stsc начинается не с первого чанка")

        val sttsBox = m.child(stbl, "stts")
        val sttsCount = sttsBox?.let { m.u32(it.dataStart + 4).toInt() } ?: 0
        if (sttsBox != null) need(m, sttsBox.dataStart + 8 + 8L * sttsCount <= sttsBox.end, "таблица времени (stts) короче заявленного")
        val counts = IntArray(sttsCount) { m.u32(sttsBox!!.dataStart + 8 + 8 * it).toInt() }
        val deltas = IntArray(sttsCount) { m.u32(sttsBox!!.dataStart + 12 + 8 * it).toInt() }

        return Track(number, handler, codec, width, height, timescale, duration, sizes, offsets, co64 != null, stsc,
            counts, deltas, nal, chunkBox.pos)
    }

    private fun need(m: Bytes, ok: Boolean, what: String) {
        if (!ok) throw Mp4Broken(what)
    }

    // ------------------------------------------------------------------
    //  Кадры
    // ------------------------------------------------------------------

    private class Finding(val kind: FileCheck.Verdict, val text: String, val offset: Long, val timeMs: Long)

    private fun checkSamples(index: Index, exactSize: Long, limits: List<String>): FileCheck {
        val total = index.tracks.sumOf { it.sampleCount.toLong() }.coerceAtLeast(1)
        var done = 0L
        var first: Finding? = null
        var anyWrap = false
        var wrapStillBad = false
        var nalBad = 0L
        var outside = 0L
        var beyond = 0L
        for (t in index.tracks) {
            val w = SampleWalk(t, index.dataRegions)
            while (w.next()) {
                if ((done and 0x3FF) == 0L) { ctl.check(); ctl.progress(done.toFloat() / total) }
                done++
                val off = w.offset
                val end = off + w.size
                if (w.wrapped) anyWrap = true
                val problem: Finding? = when {
                    end > size -> { beyond++; Finding(FileCheck.Verdict.INCOMPLETE,
                        "кадр ${w.sample + 1} дорожки ${t.index + 1} лежит на $off…$end, а файл кончается на $size", off, w.timeMs()) }
                    !inRegions(off, end, index.dataRegions) -> { outside++; Finding(FileCheck.Verdict.STRUCTURE,
                        "кадр ${w.sample + 1} дорожки ${t.index + 1} на $off вне области данных mdat", off, w.timeMs()) }
                    t.nalLength > 0 && w.size > 0 -> {
                        val bad = nalProblem(off, w.size, t.nalLength)
                        if (bad != null) { nalBad++; Finding(FileCheck.Verdict.STRUCTURE,
                            "кадр ${w.sample + 1} дорожки ${t.index + 1} на $off: $bad", off, w.timeMs()) } else null
                    }
                    else -> null
                }
                if (problem != null && w.wrapped) wrapStillBad = true
                if (problem != null && first == null) first = problem
            }
            if (w.sample + 1 < t.sampleCount) {
                val f = Finding(FileCheck.Verdict.STRUCTURE,
                    "в таблицах чанков дорожки ${t.index + 1} описано ${w.sample + 1} кадров из ${t.sampleCount}", -1, -1)
                if (first == null) first = f
            }
        }
        ctl.progress(1f)
        val duration = index.tracks.maxOf { it.durationMs }
        val tracks = index.tracks.map { it.label() }
        if (exactSize > 0 && size < exactSize) {
            return result(FileCheck.Verdict.INCOMPLETE, "Файл короче точного размера от источника: $size из $exactSize байт.",
                tracks = tracks, duration = duration, limits = limits)
        }
        if (anyWrap) {
            details.add("смещения кадров записаны 32-битными (stco), а данные идут дальше 4 ГБ: плеер читает кадры после 4 ГБ не с того места")
            return if (!wrapStillBad && first == null) {
                result(FileCheck.Verdict.STRUCTURE,
                    "Индекс кадров записан с 32-битными смещениями, а файл больше 4 ГБ. Сами кадры на месте — индекс можно пересобрать без перекодирования.",
                    tracks = tracks, duration = duration, limits = limits, repair = FileCheck.Repair.MP4_REBUILD_INDEX)
            } else {
                result(first?.kind ?: FileCheck.Verdict.STRUCTURE, "Индекс с 32-битными смещениями в файле больше 4 ГБ, и часть кадров не находится даже с поправкой: ${first?.text}",
                    tracks = tracks, duration = duration, limits = limits, offset = first?.offset ?: -1, timeMs = first?.timeMs ?: -1)
            }
        }
        if (index.mdatSizeWrapped) {
            // Кадры в порядке, но плеер не дойдёт до индекса: цепочка боксов обрывается.
            return result(FileCheck.Verdict.STRUCTURE,
                "Размер блока данных (mdat) записан 32-битным полем и обернулся через 4 ГБ — плеер не находит индекс. Кадры на месте, индекс можно пересобрать.",
                tracks = tracks, duration = duration, limits = limits, repair = FileCheck.Repair.MP4_REBUILD_INDEX)
        }
        val f = first
        if (f != null) {
            val counts = listOfNotNull(
                beyond.takeIf { it > 0 }?.let { "за концом файла: $it кадров" },
                outside.takeIf { it > 0 }?.let { "вне mdat: $it" },
                nalBad.takeIf { it > 0 }?.let { "с неверной длиной NAL: $it" },
            )
            details.addAll(counts)
            val summary = when (f.kind) {
                FileCheck.Verdict.INCOMPLETE -> "Файл обрывается: индекс описывает кадры, которых в файле нет (первый — на ${f.timeMs / 1000} с)."
                else -> "Кадры не совпадают с индексом: ${f.text}."
            }
            return result(f.kind, summary, tracks = tracks, duration = duration, limits = limits, offset = f.offset, timeMs = f.timeMs)
        }
        val tail = details.any { it.contains("лишних байт") }
        return result(FileCheck.Verdict.READABLE,
            "Структура MP4 цела: все ${total} кадров на своих местах${if (tail) " (в конце есть лишние байты — это не мешает)" else ""}.",
            tracks = tracks, duration = duration, limits = limits)
    }

    private fun inRegions(start: Long, end: Long, regions: List<LongRange>): Boolean {
        if (regions.isEmpty()) return true
        for (rg in regions) if (start >= rg.first && end <= rg.last + 1) return true
        return false
    }

    /** Та же проверка, что в Mp4Extractor: длина NAL не меньше 1 и NAL не выходит за кадр. */
    private fun nalProblem(offset: Long, sampleSize: Int, fieldLength: Int): String? {
        var p = offset
        val end = offset + sampleSize
        var n = 0
        while (p < end) {
            if (p + fieldLength > end) return "поле длины NAL выходит за границу кадра"
            val len = nal.uint(p, fieldLength)
            if (len < 1) return "длина NAL №${n + 1} равна $len (Invalid NAL length)"
            val next = p + fieldLength + len
            if (next > end) return "NAL №${n + 1} длиной $len выходит за границу кадра на ${next - end} байт"
            p = next
            n++
            if (n > 10_000) return "в кадре больше 10 000 NAL"
        }
        return null
    }

    private fun result(
        verdict: FileCheck.Verdict, summary: String, tracks: List<String> = emptyList(), duration: Long = 0,
        limits: List<String> = emptyList(), offset: Long = -1, timeMs: Long = -1,
        repair: FileCheck.Repair = FileCheck.Repair.NONE, problemOffset: Long = offset,
    ) = FileCheck(verdict, "MP4", summary, details.toList(), limits, tracks, duration, size,
        if (problemOffset >= 0) problemOffset else offset, timeMs, repair)

    companion object {
        const val MAX_MOOV = 256L * 1024 * 1024
        private const val WRAP = 1L shl 32

        private fun printable(t: String) = t.length == 4 && t.all { it.code in 0x20..0x7E }
    }

    /**
     * Обход кадров дорожки в порядке чанков. Для 32-битных смещений при
     * уменьшении смещения больше чем на 2 ГБ добавляется 2^32 — так видно
     * обёртку. [rawOffset] — как в таблице (так читает плеер), [offset] — с поправкой.
     */
    class SampleWalk(private val t: Track, regions: List<LongRange>) {
        private val maxData = regions.maxOfOrNull { it.last + 1 } ?: Long.MAX_VALUE
        var sample = -1
            private set
        var size = 0
            private set
        var rawOffset = 0L
            private set
        var offset = 0L
            private set
        var wrapped = false
            private set
        private var chunk = -1
        private var leftInChunk = 0
        private var chunkRawNext = 0L
        private var chunkNext = 0L
        private var wrapAdd = 0L
        private var prevChunkRaw = -1L
        private var stscIdx = 0
        private var sttsIdx = 0
        private var sttsLeft = if (t.sttsCounts.isNotEmpty()) t.sttsCounts[0] else 0
        private var ticks = 0L

        fun timeMs(): Long = if (t.timescale > 0) ticks * 1000 / t.timescale else -1

        fun next(): Boolean {
            if (sample >= 0) advanceTime()
            if (sample + 1 >= t.sampleCount) return false
            while (leftInChunk == 0) {
                chunk++
                if (chunk >= t.chunkOffsets.size) return false
                while (stscIdx + 1 < t.stsc.size / 3 && t.stsc[(stscIdx + 1) * 3] - 1 <= chunk) stscIdx++
                leftInChunk = if (t.stsc.isEmpty()) 0 else t.stsc[stscIdx * 3 + 1]
                val raw = t.chunkOffsets[chunk]
                if (!t.co64 && prevChunkRaw >= 0 && raw < prevChunkRaw && prevChunkRaw - raw > WRAP / 2 && raw + wrapAdd + WRAP <= maxData) {
                    wrapAdd += WRAP
                }
                prevChunkRaw = raw
                chunkRawNext = raw
                chunkNext = raw + wrapAdd
            }
            sample++
            leftInChunk--
            size = t.sampleSizes[sample]
            rawOffset = chunkRawNext
            offset = chunkNext
            wrapped = wrapAdd > 0
            chunkRawNext += size
            chunkNext += size
            return true
        }

        private fun advanceTime() {
            if (sttsIdx >= t.sttsCounts.size) return
            ticks += t.sttsDeltas[sttsIdx].toLong() and 0xFFFFFFFFL
            sttsLeft--
            while (sttsLeft <= 0 && sttsIdx + 1 < t.sttsCounts.size) {
                sttsIdx++
                sttsLeft = t.sttsCounts[sttsIdx]
            }
        }
    }
}

/** Разбор боксов внутри массива (moov целиком в памяти). */
class Bytes(val b: ByteArray) {
    class Box(val type: String, val pos: Int, val header: Int, val size: Int) {
        val end get() = pos + size
        val dataStart get() = pos + header
    }

    fun u8(p: Int) = if (p in b.indices) b[p].toInt() and 0xFF else throw IndexOutOfBoundsException("moov: $p")
    fun u16(p: Int) = (u8(p) shl 8) or u8(p + 1)
    fun u32(p: Int): Long = (u8(p).toLong() shl 24) or (u8(p + 1).toLong() shl 16) or (u8(p + 2).toLong() shl 8) or u8(p + 3).toLong()
    fun u64(p: Int): Long = (u32(p) shl 32) or u32(p + 4)
    fun fourcc(p: Int) = String(CharArray(4) { (u8(p + it)).toChar() })

    fun children(from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var s = u32(p)
            var h = 8
            if (s == 1L) { s = u64(p + 8); h = 16 }
            if (s == 0L) s = (to - p).toLong()
            if (s < h || p + s > to) break
            out.add(Box(fourcc(p + 4), p, h, s.toInt()))
            p += s.toInt()
        }
        return out
    }

    fun child(parent: Box, type: String): Box? = children(parent.dataStart, parent.end).firstOrNull { it.type == type }
}

/** Отмена и прогресс долгой проверки. */
interface CheckControl {
    /** Бросает [CheckCancelled], если пользователь отменил. */
    fun check()
    fun progress(fraction: Float)

    companion object {
        val NONE = object : CheckControl {
            override fun check() {}
            override fun progress(fraction: Float) {}
        }
    }
}
