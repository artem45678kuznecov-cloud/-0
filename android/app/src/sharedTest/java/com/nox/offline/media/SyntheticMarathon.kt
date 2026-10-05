package com.nox.offline.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Синтетический «марафон» для тестов: настоящий MP4 на многие часы из
 * короткого ролика (H.264 с B-кадрами + AAC).
 *
 * Кадры ролика записываются в mdat один раз, а индекс (moov) описывает их
 * повтор столько раз, сколько нужно для [Options.durationSec]: у каждого
 * сэмпла своя запись в stsz, ctts, co64, ключевые — в stss, звук разложен по
 * чанкам по 1 и по 2 сэмпла (в stsc по две записи на повтор). Таблицы
 * настоящие и полные — у 70-часового файла это ~18 млн сэмплов и moov
 * ~217 МиБ, как у фильма пользователя; данные файла при этом — меньше 1 МиБ
 * (плюс moov). Декодер получает настоящие кадры: картинка и звук есть на
 * любой позиции, а каждый повтор начинается с IDR.
 *
 * [Options.holeBytes] — разреженная «дыра» в начале mdat: кадры оказываются
 * дальше 4 ГиБ (смещения co64 больше 2^32), на диске дыра места не занимает.
 * Генератор пишет потоком: в памяти — описание ролика и буфер 1 МиБ.
 */
class SyntheticMarathon(private val clip: ByteArray) {
    data class Options(
        val durationSec: Long = 251_288,
        val moovFirst: Boolean = true,
        val holeBytes: Long = 0,
        /** 64-битные заголовки (size = 1) у moov, trak, mdia, minf, stbl и mdat. */
        val largeHeaders: Boolean = false,
        /** В каждом повторе первые N чанков звука — по одному сэмплу, остальные — по два. */
        val audioSingles: Int = 91,
        /** co64 (иначе stco — только если все смещения меньше 2^32). */
        val co64: Boolean = true,
        /** Правка (edit list) у видео, как пишет ffmpeg: начало — с первого показываемого кадра. */
        val videoEdit: Boolean = true,
        /** Дополнительные боксы внутри moov перед дорожками: тип, размер и сколько раз (содержимое — нули). */
        val extraMoovBoxes: List<Triple<String, Long, Int>> = emptyList(),
        /** Нули в конце stsd видео (описание кодека раздувается, записи те же). */
        val videoStsdPadding: Long = 0,
    )

    /** Дорожка ролика: всё, что нужно для повторов. */
    class Track(
        val handler: String,
        val timescale: Long,
        val width: Int,
        val height: Int,
        val hdlr: ByteArray,
        val mediaHeader: ByteArray,
        val dinf: ByteArray,
        val stsd: ByteArray,
        val sizes: IntArray,
        val offsets: LongArray,
        val deltas: IntArray,
        val ctts: IntArray?,
        val sync: BooleanArray?,
        val editMediaTime: Long,
    ) {
        val count get() = sizes.size
        val durationTicks: Long get() = deltas.sumOf { it.toLong() }
    }

    val ftyp: ByteArray
    val video: Track
    val audio: Track
    /** Сколько первых сэмплов звука ролика пропускается (задержка кодера AAC), чтобы повтор звука был ровно как у видео. */
    val audioSkip: Int
    /** Длительность одного повтора, мкс. */
    val repUs: Long

    init {
        val c = Clip(clip)
        ftyp = c.raw(c.find(0, clip.size, "ftyp")!!)
        val moov = c.find(0, clip.size, "moov")!!
        val traks = c.children(moov).filter { it.type == "trak" }.map { c.track(it) }
        video = traks.first { it.handler == "vide" }
        audio = traks.first { it.handler == "soun" }
        val repTicksVideo = video.durationTicks
        repUs = repTicksVideo * 1_000_000 / video.timescale
        val audioPerRep = (repUs * audio.timescale / 1_000_000 / audio.deltas[0]).toInt()
        audioSkip = audio.count - audioPerRep
        require(audioSkip >= 0) { "звука в ролике меньше, чем видео" }
        require(audio.deltas.drop(audioSkip).all { it == audio.deltas[0] }) { "у звука ролика непостоянный шаг" }
        require(video.sync == null || video.sync[0]) { "ролик должен начинаться с ключевого кадра" }
    }

    /** Итоговые числа файла. */
    class Layout(val reps: Int, val videoSamples: Long, val audioSamples: Long, val moovSize: Long, val fileSize: Long,
                 val moovPos: Long, val dataStart: Long, val durationUs: Long)

    fun write(out: File, o: Options = Options()): Layout {
        val reps = ((o.durationSec * 1_000_000 + repUs - 1) / repUs).toInt()
        val vPer = video.count
        val aPer = audio.count - audioSkip
        require(o.audioSingles in 0..aPer && (aPer - o.audioSingles) % 2 == 0) { "audioSingles: остаток звука должен делиться на пары" }
        val aChunksPerRep = o.audioSingles + (aPer - o.audioSingles) / 2

        // Данные одного повтора: видео (чанк = кадр) и звук (чанки по 1–2 сэмпла) вперемешку по времени.
        class Piece(val timeUs: Long, val video: Boolean, val first: Int, val n: Int)
        val units = ArrayList<Piece>()
        var t = 0L
        for (j in 0 until vPer) {
            units += Piece(t * 1_000_000 / video.timescale, true, j, 1)
            t += video.deltas[j]
        }
        var s = 0
        while (s < aPer) {
            val n = if (s < o.audioSingles) 1 else 2
            units += Piece(s.toLong() * audio.deltas[0] * 1_000_000 / audio.timescale, false, s, n)
            s += n
        }
        units.sortWith(compareBy<Piece> { it.timeUs }.thenBy { if (it.video) 0 else 1 })
        val vChunkRel = LongArray(vPer)
        val aChunkRel = LongArray(aChunksPerRep)
        var rel = 0L
        var ai = 0
        for (u in units) {
            if (u.video) vChunkRel[u.first] = rel else aChunkRel[ai++] = rel
            for (k in 0 until u.n) rel += if (u.video) video.sizes[u.first + k] else audio.sizes[audioSkip + u.first + k]
        }
        val dataBytes = rel

        val totalV = reps.toLong() * vPer
        val totalA = reps.toLong() * aPer
        require(totalV < Int.MAX_VALUE && totalA < Int.MAX_VALUE)
        val vTicks = reps * video.durationTicks
        val aTicks = totalA * audio.deltas[0]
        val movieTimescale = 1000L
        val durationMs = reps * repUs / 1000

        // Таблицы (размеры известны заранее — moov пишется потоком).
        val vStts = rle(totalV) { i -> video.deltas[(i % vPer).toInt()].toLong() }
        val vCtts = video.ctts?.let { c -> rle(totalV) { i -> c[(i % vPer).toInt()].toLong() } }
        val vSyncPerRep = video.sync?.count { it } ?: vPer
        val vStss = if (video.sync == null) null else reps.toLong() * vSyncPerRep
        val offsetBytes = if (o.co64) 8 else 4

        // Таблицы — всегда с 32-битным заголовком: 64-битный у листовых боксов не принимает и Media3.
        fun full(type: String, entries: Long, entryBytes: Int, extra: Int = 0, w: (Out) -> Unit) =
            Box(type, 4L + 4 + extra + entries * entryBytes, false, w)

        // Смещения записываются после того, как известно начало данных.
        var dataStart = 0L
        val vStsd = if (o.videoStsdPadding == 0L) Box.raw(video.stsd) else {
            val raw = video.stsd
            Box("stsd", raw.size - 8L + o.videoStsdPadding, false) { out -> out.bytes(raw, 8, raw.size - 8); out.zeros(o.videoStsdPadding) }
        }
        val vTables = listOfNotNull(
            vStsd,
            full("stts", vStts.entries, 8) { out -> out.i32(0); out.i32(vStts.entries.toInt()); vStts.write(out) },
            vStss?.let { n -> full("stss", n, 4) { out ->
                out.i32(0); out.i32(n.toInt())
                for (r in 0 until reps) for (j in 0 until vPer) if (video.sync!![j]) out.i32(r * vPer + j + 1)
            } },
            vCtts?.let { c -> full("ctts", c.entries, 8) { out -> out.i32(0); out.i32(c.entries.toInt()); c.write(out) } },
            full("stsc", 1, 12) { out -> out.i32(0); out.i32(1); out.i32(1); out.i32(1); out.i32(1) },
            full("stsz", totalV, 4, extra = 4) { out ->
                out.i32(0); out.i32(0); out.i32(totalV.toInt())
                for (r in 0 until reps) for (j in 0 until vPer) out.i32(video.sizes[j])
            },
            full(if (o.co64) "co64" else "stco", totalV, offsetBytes) { out ->
                out.i32(0); out.i32(totalV.toInt())
                for (r in 0 until reps) for (j in 0 until vPer) out.offset(dataStart + vChunkRel[j], o.co64)
            },
        )
        val aStscEntries = reps.toLong() * (if (o.audioSingles in 1 until aPer) 2 else 1)
        val aTables = listOf(
            Box.raw(audio.stsd),
            full("stts", 1, 8) { out -> out.i32(0); out.i32(1); out.i32(totalA.toInt()); out.i32(audio.deltas[0]) },
            full("stsc", aStscEntries, 12) { out ->
                out.i32(0); out.i32(aStscEntries.toInt())
                for (r in 0 until reps) {
                    val base = r * aChunksPerRep + 1
                    if (o.audioSingles > 0) { out.i32(base); out.i32(1); out.i32(1) }
                    if (o.audioSingles < aPer) { out.i32(base + o.audioSingles); out.i32(2); out.i32(1) }
                }
            },
            full("stsz", totalA, 4, extra = 4) { out ->
                out.i32(0); out.i32(0); out.i32(totalA.toInt())
                for (r in 0 until reps) for (j in 0 until aPer) out.i32(audio.sizes[audioSkip + j])
            },
            full(if (o.co64) "co64" else "stco", reps.toLong() * aChunksPerRep, offsetBytes) { out ->
                out.i32(0); out.i32(reps * aChunksPerRep)
                for (r in 0 until reps) for (c in 0 until aChunksPerRep) out.offset(dataStart + aChunkRel[c], o.co64)
            },
        )
        val big = o.largeHeaders
        fun trak(tr: Track, id: Int, ticks: Long, tables: List<Box>, edit: Boolean): Box {
            val trackMs = ticks * movieTimescale / tr.timescale
            val tkhd = Box("tkhd", 96, false) { out ->
                out.i32(0x01000003); out.i64(0); out.i64(0); out.i32(id); out.i32(0); out.i64(trackMs); out.i64(0)
                out.i16(0); out.i16(0); out.i16(if (tr.handler == "soun") 0x0100 else 0); out.i16(0)
                for (m in intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)) out.i32(m)
                out.i32(tr.width shl 16); out.i32(tr.height shl 16)
            }
            val mdhd = Box("mdhd", 36, false) { out ->
                out.i32(0x01000000); out.i64(0); out.i64(0); out.i32(tr.timescale.toInt()); out.i64(ticks); out.i16(0x55c4); out.i16(0)
            }
            val edts = if (!edit) null else Box.container("edts", false, listOf(Box("elst", 4 + 4 + 20, false) { out ->
                out.i32(0x01000000); out.i32(1); out.i64(trackMs); out.i64(tr.editMediaTime); out.i16(1); out.i16(0)
            }))
            val stbl = Box.container("stbl", big, tables)
            val minf = Box.container("minf", big, listOf(Box.raw(tr.mediaHeader), Box.raw(tr.dinf), stbl))
            val mdia = Box.container("mdia", big, listOf(mdhd, Box.raw(tr.hdlr), minf))
            return Box.container("trak", big, listOfNotNull(tkhd, edts, mdia))
        }
        val mvhd = Box("mvhd", 112, false) { out ->
            out.i32(0x01000000); out.i64(0); out.i64(0); out.i32(movieTimescale.toInt()); out.i64(durationMs)
            out.i32(0x00010000); out.i16(0x0100); out.i16(0); out.i64(0)
            for (m in intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)) out.i32(m)
            repeat(6) { out.i32(0) }
            out.i32(3)
        }
        val extras = o.extraMoovBoxes.map { (type, size, count) ->
            val one = Box(type, size - 8, false) { out -> out.zeros(size - 8) }
            if (count == 1) one else Seq(one, count)
        }
        val moov = Box.container("moov", big, listOf(mvhd) + extras + listOf(
            trak(video, 1, vTicks, vTables, o.videoEdit && video.editMediaTime > 0),
            trak(audio, 2, aTicks, aTables, false),
        ))
        val mdatHeader = if (big) 16 else 8
        val mdatSize = mdatHeader + o.holeBytes + dataBytes
        val moovPos = if (o.moovFirst) ftyp.size.toLong() else ftyp.size + mdatSize
        val mdatPos = if (o.moovFirst) ftyp.size + moov.size else ftyp.size.toLong()
        dataStart = mdatPos + mdatHeader + o.holeBytes
        require(o.co64 || dataStart + dataBytes < (1L shl 32)) { "stco: смещения не помещаются в 32 бита" }

        out.delete()
        Out(out).use { w ->
            w.bytes(ftyp)
            fun mdat() {
                if (big) { w.i32(1); w.type("mdat"); w.i64(mdatSize) } else { w.i32(mdatSize.toInt()); w.type("mdat") }
                w.hole(o.holeBytes)
                for (u in units) for (k in 0 until u.n) {
                    if (u.video) w.bytes(video.sample(clip, u.first + k)) else w.bytes(audio.sample(clip, audioSkip + u.first + k))
                }
            }
            if (o.moovFirst) { moov.write(w); mdat() } else { mdat(); moov.write(w) }
        }
        val layout = Layout(reps, totalV, totalA, moov.size, out.length(), moovPos, dataStart, reps * repUs)
        check(layout.fileSize == ftyp.size + moov.size + mdatSize) { "размер файла ${layout.fileSize} не сходится с расчётом" }
        return layout
    }

    private fun Track.sample(clip: ByteArray, i: Int): ByteArray = clip.copyOfRange(offsets[i].toInt(), (offsets[i] + sizes[i]).toInt())

    // ------------------------------------------------------------------
    //  Повторяющиеся значения — записи (count, value) без массива на сэмпл
    // ------------------------------------------------------------------

    private class Rle(val entries: Long, val write: (Out) -> Unit)

    private fun rle(n: Long, value: (Long) -> Long): Rle {
        var entries = 0L
        var prev = Long.MIN_VALUE
        for (i in 0 until n) { val v = value(i); if (v != prev) { entries++; prev = v } }
        return Rle(entries) { out ->
            var cur = value(0)
            var count = 0L
            for (i in 0 until n) {
                val v = value(i)
                if (v != cur) { out.i32(count.toInt()); out.i32(cur.toInt()); cur = v; count = 0 }
                count++
            }
            out.i32(count.toInt()); out.i32(cur.toInt())
        }
    }

    // ------------------------------------------------------------------
    //  Боксы с заранее известным размером
    // ------------------------------------------------------------------

    private open class Box(val type: String, val payload: Long, val large: Boolean, val writePayload: (Out) -> Unit) {
        open val size: Long get() = (if (large) 16 else 8) + payload

        open fun write(out: Out) {
            if (large) { out.i32(1); out.type(type); out.i64(size) } else { require(size < (1L shl 32)); out.i32(size.toInt()); out.type(type) }
            val start = out.written
            writePayload(out)
            check(out.written - start == payload) { "$type: записано ${out.written - start} байт вместо $payload" }
        }

        companion object {
            fun container(type: String, large: Boolean, children: List<Box>) =
                Box(type, children.sumOf { it.size }, large) { out -> children.forEach { it.write(out) } }

            /** Бокс ролика как есть (с заголовком). */
            fun raw(bytes: ByteArray): Box {
                val type = String(bytes, 4, 4, Charsets.ISO_8859_1)
                return Box(type, bytes.size - 8L, false) { out -> out.bytes(bytes, 8, bytes.size - 8) }
            }
        }
    }

    /** [count] одинаковых боксов подряд, без общего заголовка. */
    private class Seq(private val item: Box, private val count: Int) : Box(item.type, 0, false, {}) {
        override val size: Long get() = item.size * count
        override fun write(out: Out) = repeat(count) { item.write(out) }
    }

    /** Потоковая запись с буфером 1 МиБ; «дыра» — переход вперёд без записи (разреженный файл). */
    private class Out(file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "rw")
        private val ch: FileChannel = raf.channel
        private val buf = ByteBuffer.allocate(1 shl 20)
        var written = 0L
            private set

        private fun room(n: Int) { if (buf.remaining() < n) flush() }
        private fun flush() { buf.flip(); while (buf.hasRemaining()) ch.write(buf); buf.clear() }
        fun i16(v: Int) { room(2); buf.putShort(v.toShort()); written += 2 }
        fun i32(v: Int) { room(4); buf.putInt(v); written += 4 }
        fun i64(v: Long) { room(8); buf.putLong(v); written += 8 }
        fun type(t: String) { room(4); buf.put(t.toByteArray(Charsets.ISO_8859_1)); written += 4 }
        fun offset(v: Long, wide: Boolean) = if (wide) i64(v) else i32(v.toInt())
        fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) {
            var o = off
            var left = len
            while (left > 0) { room(1); val n = minOf(left, buf.remaining()); buf.put(b, o, n); o += n; left -= n }
            written += len
        }
        fun zeros(n: Long) { var left = n; while (left > 0) { room(1); val k = minOf(left, buf.remaining().toLong()).toInt(); buf.put(ByteArray(k)); left -= k }; written += n }
        fun hole(n: Long) { if (n <= 0) return; flush(); ch.position(ch.position() + n); written += n }
        override fun close() { flush(); if (ch.size() < written) { ch.position(written - 1); ch.write(ByteBuffer.wrap(ByteArray(1))) }; raf.close() }
    }

    // ------------------------------------------------------------------
    //  Разбор ролика (MP4 от ffmpeg: stco, stsc, stts, ctts, stss)
    // ------------------------------------------------------------------

    private class Node(val type: String, val pos: Int, val size: Int, val header: Int) {
        val dataStart get() = pos + header
        val end get() = pos + size
    }

    private class Clip(val d: ByteArray) {
        fun u32(p: Int): Long = ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or
            ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)
        fun u64(p: Int): Long = (u32(p) shl 32) or u32(p + 4)
        fun type(p: Int) = String(d, p, 4, Charsets.ISO_8859_1)
        fun raw(n: Node) = d.copyOfRange(n.pos, n.end)

        fun nodes(from: Int, to: Int): List<Node> {
            val list = ArrayList<Node>()
            var p = from
            while (p + 8 <= to) {
                var size = u32(p)
                var h = 8
                if (size == 1L) { size = u64(p + 8); h = 16 } else if (size == 0L) size = (to - p).toLong()
                list += Node(type(p + 4), p, size.toInt(), h)
                p += size.toInt()
            }
            return list
        }

        fun children(n: Node) = nodes(n.dataStart, n.end)
        fun find(from: Int, to: Int, type: String) = nodes(from, to).firstOrNull { it.type == type }
        fun child(n: Node, type: String) = children(n).firstOrNull { it.type == type }
        fun path(n: Node, vararg types: String): Node? = types.fold(n as Node?) { acc, t -> acc?.let { child(it, t) } }

        fun track(trak: Node): Track {
            val mdia = child(trak, "mdia")!!
            val hdlr = child(mdia, "hdlr")!!
            val handler = type(hdlr.dataStart + 8)
            val mdhd = child(mdia, "mdhd")!!
            val v1 = d[mdhd.dataStart].toInt() == 1
            val timescale = u32(mdhd.dataStart + if (v1) 20 else 12)
            val tkhd = child(trak, "tkhd")!!
            val width = (u32(tkhd.end - 8) ushr 16).toInt()
            val height = (u32(tkhd.end - 4) ushr 16).toInt()
            val elst = path(trak, "edts", "elst")
            val editMediaTime = if (elst == null) 0 else {
                val ev1 = d[elst.dataStart].toInt() == 1
                if (ev1) u64(elst.dataStart + 16) else u32(elst.dataStart + 12)
            }
            val minf = child(mdia, "minf")!!
            val stbl = child(minf, "stbl")!!
            val mediaHeader = children(minf).first { it.type == "vmhd" || it.type == "smhd" }
            val stsz = child(stbl, "stsz")!!
            val n = u32(stsz.dataStart + 8).toInt()
            val fixed = u32(stsz.dataStart + 4).toInt()
            val sizes = IntArray(n) { if (fixed != 0) fixed else u32(stsz.dataStart + 12 + 4 * it).toInt() }
            val stco = child(stbl, "stco")
            val co64 = child(stbl, "co64")
            val chunkBox = stco ?: co64!!
            val chunks = u32(chunkBox.dataStart + 4).toInt()
            val chunkOffsets = LongArray(chunks) { if (stco != null) u32(stco.dataStart + 8 + 4 * it) else u64(co64!!.dataStart + 8 + 8 * it) }
            val stsc = child(stbl, "stsc")!!
            val stscN = u32(stsc.dataStart + 4).toInt()
            val offsets = LongArray(n)
            var sample = 0
            for (e in 0 until stscN) {
                val first = u32(stsc.dataStart + 8 + 12 * e).toInt() - 1
                val spc = u32(stsc.dataStart + 8 + 12 * e + 4).toInt()
                val next = if (e + 1 < stscN) u32(stsc.dataStart + 8 + 12 * (e + 1)).toInt() - 1 else chunks
                for (c in first until next) {
                    var off = chunkOffsets[c]
                    repeat(spc) { if (sample < n) { offsets[sample] = off; off += sizes[sample]; sample++ } }
                }
            }
            val stts = child(stbl, "stts")!!
            val deltas = IntArray(n)
            var k = 0
            for (e in 0 until u32(stts.dataStart + 4).toInt()) {
                val count = u32(stts.dataStart + 8 + 8 * e).toInt()
                val delta = u32(stts.dataStart + 12 + 8 * e).toInt()
                repeat(count) { deltas[k++] = delta }
            }
            val cttsBox = child(stbl, "ctts")
            val ctts = cttsBox?.let { b ->
                val out = IntArray(n)
                var i = 0
                for (e in 0 until u32(b.dataStart + 4).toInt()) {
                    val count = u32(b.dataStart + 8 + 8 * e).toInt()
                    val off = u32(b.dataStart + 12 + 8 * e).toInt()
                    repeat(count) { out[i++] = off }
                }
                out
            }
            val stssBox = child(stbl, "stss")
            val sync = stssBox?.let { b ->
                val out = BooleanArray(n)
                for (e in 0 until u32(b.dataStart + 4).toInt()) out[u32(b.dataStart + 8 + 4 * e).toInt() - 1] = true
                out
            }
            return Track(handler, timescale, width, height, raw(hdlr), raw(mediaHeader), raw(child(minf, "dinf")!!),
                raw(child(stbl, "stsd")!!), sizes, offsets, deltas, ctts, sync, editMediaTime)
        }
    }

    companion object {
        /** Ролик из ресурсов тестов (JVM) — на устройстве берётся из assets. */
        fun clipFromResources(): ByteArray =
            SyntheticMarathon::class.java.getResourceAsStream("/media/marathon-clip.mp4")!!.use { it.readBytes() }
    }
}
