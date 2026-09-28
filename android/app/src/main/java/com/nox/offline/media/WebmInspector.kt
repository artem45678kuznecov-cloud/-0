package com.nox.offline.media

/**
 * Проверка WebM/Matroska по содержимому, только чтением: EBML-заголовок,
 * Segment, SeekHead (ссылки должны вести на свои элементы), Info, Tracks,
 * Cues (каждая точка — на начало кластера), все кластеры и заголовки их
 * блоков (номер дорожки, границы). Полезная нагрузка кадров не декодируется.
 */
class WebmInspector(private val r: CachedReader, private val ctl: CheckControl) {
    private val size = r.size
    private val details = ArrayList<String>()

    private class Broken(val verdict: FileCheck.Verdict, message: String, val at: Long) : Exception(message)

    private class Element(val id: Long, val pos: Long, val header: Int, val dataSize: Long) {
        val unknownSize get() = dataSize < 0
        val dataStart get() = pos + header
        val end get() = if (dataSize < 0) Long.MAX_VALUE else dataStart + dataSize
    }

    private val trackCodecs = LinkedHashMap<Long, String>()
    private val trackLabels = ArrayList<String>()
    private var timecodeScale = 1_000_000L
    private var durationTicks = 0.0
    private var blocks = 0L
    private var clusters = 0L
    private var lastClusterTime = 0L

    fun inspect(exactSize: Long): FileCheck {
        val limits = listOf("кадры не декодировались — проверены структура и заголовки блоков, а не изображение")
        return try {
            run(exactSize, limits)
        } catch (b: Broken) {
            result(b.verdict, b.message ?: "", limits, b.at)
        } catch (e: java.io.EOFException) {
            result(FileCheck.Verdict.INCOMPLETE, "Файл обрывается посреди элемента: ${e.message}.", limits, size)
        }
    }

    private fun run(exactSize: Long, limits: List<String>): FileCheck {
        val ebml = element(0)
        if (ebml.id != ID_EBML) throw Broken(FileCheck.Verdict.UNKNOWN, "В начале файла нет заголовка EBML — это не WebM/Matroska.", 0)
        var docType = ""
        forChildren(ebml) { c -> if (c.id == ID_DOCTYPE) docType = string(c) }
        val seg = element(ebml.end)
        if (seg.id != ID_SEGMENT) throw Broken(FileCheck.Verdict.STRUCTURE, "После заголовка EBML нет элемента Segment.", ebml.end)
        val segEnd = if (seg.unknownSize) size else seg.end
        if (!seg.unknownSize && seg.end > size) {
            details.add("Segment объявлен до ${seg.end}, а файл кончается на $size (не хватает ${seg.end - size} байт)")
        }
        val segStart = seg.dataStart
        val level1 = ArrayList<Element>()
        var cues: Element? = null
        var pos = segStart
        while (pos < minOf(segEnd, size)) {
            ctl.check()
            ctl.progress((pos.toFloat() / size).coerceIn(0f, 1f))
            if (size - pos < 2) break
            val e = element(pos)
            val end = if (e.unknownSize) clusterEnd(e) else e.end
            if (end > size) {
                val what = if (e.id == ID_CLUSTER) "последний кластер" else "элемент ${hex(e.id)}"
                throw Broken(FileCheck.Verdict.INCOMPLETE,
                    "Файл обрывается: $what начинается на ${e.pos} и должен идти до $end, а файл кончается на $size.", e.pos)
            }
            level1.add(e)
            when (e.id) {
                ID_INFO -> readInfo(e)
                ID_TRACKS -> readTracks(e)
                ID_CUES -> cues = e
                ID_CLUSTER -> readCluster(e, end)
                ID_SEGMENT -> throw Broken(FileCheck.Verdict.STRUCTURE,
                    "В файле второй элемент Segment на ${e.pos} — плеер такое не поддерживает (Multiple Segment elements).", e.pos)
            }
            pos = end
        }
        if (trackCodecs.isEmpty()) throw Broken(FileCheck.Verdict.STRUCTURE, "В файле нет описания дорожек (Tracks).", segStart)
        // SeekHead: плеер требует SeekID и SeekPosition и прыгает по ним (например, к Cues).
        for (sh in level1.filter { it.id == ID_SEEK_HEAD }) checkSeekHead(sh, segStart)
        val cuesInfo = cues?.let { checkCues(it, segStart) }
        ctl.progress(1f)
        if (exactSize > 0 && size < exactSize) {
            return result(FileCheck.Verdict.INCOMPLETE, "Файл короче точного размера от источника: $size из $exactSize байт.", limits, size)
        }
        if (clusters == 0L) throw Broken(FileCheck.Verdict.INCOMPLETE, "В файле нет ни одного кластера с кадрами.", segStart)
        details.add("кластеров: $clusters, блоков: $blocks, ${cuesInfo ?: "индекса перемотки (Cues) нет — перемотка будет приблизительной"}")
        return result(FileCheck.Verdict.READABLE, "Структура WebM цела: все $clusters кластеров и $blocks блоков на своих местах.", limits, -1)
    }

    // ------------------------------------------------------------------
    //  Элементы
    // ------------------------------------------------------------------

    /** Заголовок элемента: ID (1–4 байта с меткой длины) и размер (1–8 байт). */
    private fun element(pos: Long): Element {
        val first = r.u8(pos)
        if (first < 0) throw java.io.EOFException("нет заголовка элемента на $pos")
        val idLen = vintLength(first)
        if (idLen == 0 || idLen > 4) throw Broken(FileCheck.Verdict.STRUCTURE, "На смещении $pos нет корректного идентификатора элемента.", pos)
        val id = r.uint(pos, idLen)
        val sFirst = r.u8(pos + idLen)
        if (sFirst < 0) throw java.io.EOFException("нет размера элемента на ${pos + idLen}")
        val sLen = vintLength(sFirst)
        if (sLen == 0) throw Broken(FileCheck.Verdict.STRUCTURE, "Элемент ${hex(id)} на $pos: некорректное поле размера.", pos)
        var v = (sFirst and (0xFF shr sLen)).toLong()
        var allOnes = v == (0xFF shr sLen).toLong()
        for (i in 1 until sLen) {
            val b = r.u8(pos + idLen + i)
            if (b < 0) throw java.io.EOFException("размер элемента обрывается на $pos")
            if (b != 0xFF) allOnes = false
            v = (v shl 8) or b.toLong()
        }
        return Element(id, pos, idLen + sLen, if (allOnes) -1 else v)
    }

    private fun vintLength(first: Int): Int {
        for (i in 0 until 8) if (first and (0x80 shr i) != 0) return i + 1
        return 0
    }

    private inline fun forChildren(parent: Element, block: (Element) -> Unit) {
        var p = parent.dataStart
        val end = minOf(parent.end, size)
        while (p < end) {
            val c = element(p)
            if (c.unknownSize) throw Broken(FileCheck.Verdict.STRUCTURE, "Элемент ${hex(c.id)} внутри ${hex(parent.id)} без размера.", p)
            if (c.end > end) throw Broken(if (end == size && parent.end > size) FileCheck.Verdict.INCOMPLETE else FileCheck.Verdict.STRUCTURE,
                "Элемент ${hex(c.id)} на $p выходит за границу родителя ${hex(parent.id)} (${c.end} > $end).", p)
            block(c)
            p = c.end
        }
    }

    private fun uint(e: Element): Long = if (e.dataSize in 1..8) r.uint(e.dataStart, e.dataSize.toInt()) else 0

    private fun string(e: Element): String = if (e.dataSize in 1..256) String(r.bytes(e.dataStart, e.dataSize.toInt())).trimEnd('\u0000') else ""

    private fun float(e: Element): Double = when (e.dataSize) {
        4L -> java.lang.Float.intBitsToFloat(r.uint(e.dataStart, 4).toInt()).toDouble()
        8L -> java.lang.Double.longBitsToDouble(r.uint(e.dataStart, 8))
        else -> 0.0
    }

    /** Кластер без размера: до следующего элемента первого уровня. */
    private fun clusterEnd(e: Element): Long {
        if (e.id != ID_CLUSTER) throw Broken(FileCheck.Verdict.STRUCTURE, "Элемент ${hex(e.id)} на ${e.pos} без размера.", e.pos)
        var p = e.dataStart
        while (p < size) {
            val c = element(p)
            if (c.id in LEVEL1) return p
            if (c.unknownSize) throw Broken(FileCheck.Verdict.STRUCTURE, "Вложенный элемент без размера на $p.", p)
            p = c.end
        }
        return p
    }

    // ------------------------------------------------------------------
    //  Содержимое
    // ------------------------------------------------------------------

    private fun readInfo(e: Element) = forChildren(e) { c ->
        when (c.id) {
            ID_TIMECODE_SCALE -> timecodeScale = uint(c).takeIf { it > 0 } ?: 1_000_000L
            ID_DURATION -> durationTicks = float(c)
        }
    }

    private fun readTracks(e: Element) = forChildren(e) { entry ->
        if (entry.id != ID_TRACK_ENTRY) return@forChildren
        var number = 0L
        var codec = ""
        var w = 0L
        var h = 0L
        var encrypted = false
        forChildren(entry) { c ->
            when (c.id) {
                ID_TRACK_NUMBER -> number = uint(c)
                ID_CODEC_ID -> codec = string(c)
                ID_VIDEO -> forChildren(c) { v -> if (v.id == ID_PIXEL_WIDTH) w = uint(v) else if (v.id == ID_PIXEL_HEIGHT) h = uint(v) }
                ID_CONTENT_ENCODINGS -> encrypted = true
            }
        }
        if (number == 0L) throw Broken(FileCheck.Verdict.STRUCTURE, "Дорожка без номера (TrackNumber) на ${entry.pos}.", entry.pos)
        trackCodecs[number] = codec
        trackLabels.add("$codec${if (w > 0) " ${w}×$h" else ""}${if (encrypted) ", с кодированием содержимого" else ""}")
        if (codec.isNotBlank() && codec !in MEDIA3_CODECS) details.add("кодек дорожки $number «$codec» плеер NOX не знает")
    }

    private fun readCluster(e: Element, end: Long) {
        clusters++
        var p = e.dataStart
        while (p < end) {
            if ((blocks and 0xFF) == 0L) ctl.check()
            val c = element(p)
            if (c.unknownSize || c.end > end) {
                throw Broken(if (c.end > size) FileCheck.Verdict.INCOMPLETE else FileCheck.Verdict.STRUCTURE,
                    "В кластере на ${e.pos} элемент ${hex(c.id)} на $p выходит за границу кластера.", p)
            }
            when (c.id) {
                ID_CLUSTER_TIMECODE -> lastClusterTime = uint(c)
                ID_SIMPLE_BLOCK -> block(c)
                ID_BLOCK_GROUP -> forChildren(c) { b -> if (b.id == ID_BLOCK) block(b) }
            }
            p = c.end
        }
    }

    private fun block(b: Element) {
        blocks++
        val first = r.u8(b.dataStart)
        val len = vintLength(first)
        if (len == 0 || len > 8 || b.dataSize < len + 3) {
            throw Broken(FileCheck.Verdict.STRUCTURE, "Блок на ${b.pos}: неверный заголовок (номер дорожки).", b.pos)
        }
        val track = r.uint(b.dataStart, len) and ((1L shl (7 * len)) - 1)
        if (track !in trackCodecs) {
            throw Broken(FileCheck.Verdict.STRUCTURE, "Блок на ${b.pos} относится к несуществующей дорожке $track.", b.pos)
        }
    }

    private fun checkSeekHead(sh: Element, segStart: Long) = forChildren(sh) { seek ->
        if (seek.id != ID_SEEK) return@forChildren
        var sid = -1L
        var spos = -1L
        forChildren(seek) { c ->
            if (c.id == ID_SEEK_ID) sid = uint(c) else if (c.id == ID_SEEK_POSITION) spos = uint(c)
        }
        if (sid < 0 || spos < 0) {
            throw Broken(FileCheck.Verdict.STRUCTURE,
                "В оглавлении (SeekHead) на ${seek.pos} нет SeekID или SeekPosition — плеер на этом останавливается.", seek.pos)
        }
        val target = segStart + spos
        if (target >= size) {
            throw Broken(FileCheck.Verdict.INCOMPLETE, "Оглавление ведёт к ${hex(sid)} на $target, а файл кончается на $size.", seek.pos)
        }
        val at = element(target)
        if (at.id != sid) {
            throw Broken(FileCheck.Verdict.STRUCTURE,
                "Оглавление ведёт к ${hex(sid)} на $target, а там ${hex(at.id)} — плеер прыгнет не туда.", target)
        }
    }

    private fun checkCues(cues: Element, segStart: Long): String {
        var points = 0
        var bad = 0
        var firstBad = -1L
        forChildren(cues) { cp ->
            if (cp.id != ID_CUE_POINT) return@forChildren
            points++
            forChildren(cp) { tp ->
                if (tp.id != ID_CUE_TRACK_POSITIONS) return@forChildren
                forChildren(tp) { c ->
                    if (c.id == ID_CUE_CLUSTER_POSITION) {
                        val target = segStart + uint(c)
                        val ok = target < size && element(target).id == ID_CLUSTER
                        if (!ok) { bad++; if (firstBad < 0) firstBad = target }
                    }
                }
            }
        }
        if (bad > 0) {
            throw Broken(FileCheck.Verdict.STRUCTURE,
                "Индекс перемотки (Cues): $bad из $points точек ведут не на начало кластера (первая — на $firstBad).", firstBad)
        }
        return "индекс перемотки (Cues): $points точек, все ведут на кластеры"
    }

    private fun result(verdict: FileCheck.Verdict, summary: String, limits: List<String>, at: Long): FileCheck {
        val durationMs = (durationTicks * timecodeScale / 1_000_000).toLong()
        return FileCheck(verdict, "WebM/Matroska", summary, details.toList(), limits, trackLabels.toList(), durationMs, size,
            problemOffset = at)
    }

    private fun hex(id: Long) = "0x" + java.lang.Long.toHexString(id).uppercase()

    companion object {
        private const val ID_EBML = 0x1A45DFA3L
        private const val ID_DOCTYPE = 0x4282L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_SEEK_HEAD = 0x114D9B74L
        private const val ID_SEEK = 0x4DBBL
        private const val ID_SEEK_ID = 0x53ABL
        private const val ID_SEEK_POSITION = 0x53ACL
        private const val ID_INFO = 0x1549A966L
        private const val ID_TIMECODE_SCALE = 0x2AD7B1L
        private const val ID_DURATION = 0x4489L
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_TRACK_ENTRY = 0xAEL
        private const val ID_TRACK_NUMBER = 0xD7L
        private const val ID_CODEC_ID = 0x86L
        private const val ID_VIDEO = 0xE0L
        private const val ID_PIXEL_WIDTH = 0xB0L
        private const val ID_PIXEL_HEIGHT = 0xBAL
        private const val ID_CONTENT_ENCODINGS = 0x6D80L
        private const val ID_CUES = 0x1C53BB6BL
        private const val ID_CUE_POINT = 0xBBL
        private const val ID_CUE_TRACK_POSITIONS = 0xB7L
        private const val ID_CUE_CLUSTER_POSITION = 0xF1L
        private const val ID_CLUSTER = 0x1F43B675L
        private const val ID_CLUSTER_TIMECODE = 0xE7L
        private const val ID_SIMPLE_BLOCK = 0xA3L
        private const val ID_BLOCK_GROUP = 0xA0L
        private const val ID_BLOCK = 0xA1L
        private val LEVEL1 = setOf(ID_SEEK_HEAD, ID_INFO, ID_TRACKS, ID_CUES, ID_CLUSTER, 0x1254C367L, 0x1043A770L, 0x1941A469L, ID_SEGMENT)
        private val MEDIA3_CODECS = setOf("V_VP8", "V_VP9", "V_AV1", "V_MPEG2", "V_MPEG4/ISO/SP", "V_MPEG4/ISO/ASP", "V_MPEG4/ISO/AP",
            "V_MPEG4/ISO/AVC", "V_MPEGH/ISO/HEVC", "V_MS/VFW/FOURCC", "V_THEORA", "A_VORBIS", "A_OPUS", "A_AAC", "A_MPEG/L2",
            "A_MPEG/L3", "A_AC3", "A_EAC3", "A_TRUEHD", "A_DTS", "A_DTS/EXPRESS", "A_DTS/LOSSLESS", "A_FLAC", "A_MS/ACM",
            "A_PCM/INT/LIT", "A_PCM/INT/BIG", "A_PCM/FLOAT/IEEE", "S_TEXT/UTF8", "S_TEXT/ASS", "S_TEXT/WEBVTT", "S_VOBSUB",
            "S_HDMV/PGS", "S_DVBSUB")
    }
}
