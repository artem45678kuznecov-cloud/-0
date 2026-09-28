package com.nox.offline.media

import java.io.OutputStream

/**
 * Пересборка индекса MP4 без перекодирования.
 *
 * Новый файл: ftyp (как был) → moov (тот же, но таблицы смещений всех
 * дорожек — 64-битные co64 с правильными смещениями) → mdat с 64-битным
 * заголовком → байты кадров, скопированные из оригинала как есть.
 * Изображение, звук, разрешение, HDR-метаданные и прочие боксы индекса не
 * меняются: пересчитываются только смещения. Прочие боксы верхнего уровня
 * (free, uuid и т. п.) не переносятся — это сказано в [notes].
 *
 * Оригинал только читается. Запись идёт последовательно в [OutputStream],
 * поэтому подходит и для файла, и для документа SAF.
 */
class Mp4Rebuild(private val src: ByteSource, private val index: Mp4Inspector.Index) {
    val notes = ArrayList<String>()
    private val m = Bytes(index.moovBytes)
    private val ftyp = index.top.firstOrNull { it.type == "ftyp" }

    private val payloadSize: Long = index.dataRegions.sumOf { it.last + 1 - it.first }

    /** Смещения первого кадра каждого чанка, как они на самом деле лежат в оригинале. */
    private val trueChunkOffsets: List<LongArray> = index.tracks.map { unwrapped(it) }

    private fun unwrapped(t: Mp4Inspector.Track): LongArray {
        val maxData = index.dataRegions.maxOfOrNull { it.last + 1 } ?: Long.MAX_VALUE
        var add = 0L
        var prev = -1L
        return LongArray(t.chunkOffsets.size) { i ->
            val raw = t.chunkOffsets[i]
            if (!t.co64 && prev >= 0 && raw < prev && prev - raw > WRAP / 2 && raw + add + WRAP <= maxData) add += WRAP
            prev = raw
            raw + add
        }
    }

    /** Новое смещение байта оригинала: области данных идут подряд в одном mdat. */
    private fun mapOffset(old: Long, payloadStart: Long): Long {
        var acc = 0L
        for (rg in index.dataRegions) {
            if (old >= rg.first && old <= rg.last) return payloadStart + acc + (old - rg.first)
            acc += rg.last + 1 - rg.first
        }
        throw IllegalStateException("смещение $old вне областей данных")
    }

    private val ftypBytes: ByteArray by lazy {
        val f = ftyp
        if (f != null && f.size in 8..4096) {
            val b = ByteArray(f.size.toInt())
            src.read(f.pos, b)
            b
        } else {
            notes.add("в оригинале нет ftyp — записан стандартный")
            byteArrayOf(0, 0, 0, 20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 2, 0,
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())
        }
    }

    /** Размер результата — для проверки свободного места до начала записи. */
    fun outputSize(): Long = ftypBytes.size + buildMoov(0).size + 16L + payloadSize

    fun write(out: OutputStream, ctl: CheckControl) {
        val moovProbe = buildMoov(0)
        val payloadStart = ftypBytes.size + moovProbe.size + 16L
        val moov = buildMoov(payloadStart)
        check(moov.size == moovProbe.size) { "размер индекса изменился при пересчёте" }
        out.write(ftypBytes)
        out.write(moov)
        val header = ByteArray(16)
        put32(header, 0, 1)
        header[4] = 'm'.code.toByte(); header[5] = 'd'.code.toByte(); header[6] = 'a'.code.toByte(); header[7] = 't'.code.toByte()
        put64(header, 8, 16 + payloadSize)
        out.write(header)
        val buf = ByteArray(1 shl 20)
        var copied = 0L
        for (rg in index.dataRegions) {
            var p = rg.first
            val end = rg.last + 1
            while (p < end) {
                ctl.check()
                val n = src.read(p, buf, 0, minOf(buf.size.toLong(), end - p).toInt())
                if (n <= 0) throw java.io.EOFException("оригинал обрывается на $p")
                out.write(buf, 0, n)
                p += n
                copied += n
                if ((copied and ((1L shl 26) - 1)) < n) ctl.progress(copied.toFloat() / payloadSize)
            }
        }
        out.flush()
        ctl.progress(1f)
    }

    // ------------------------------------------------------------------
    //  Новый moov
    // ------------------------------------------------------------------

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl")

    private fun buildMoov(payloadStart: Long): ByteArray {
        val moovBox = Bytes.Box("moov", 0, index.moov.header, index.moovBytes.size)
        val out = java.io.ByteArrayOutputStream(index.moovBytes.size + 1024)
        writeBox(moovBox, out, payloadStart, trackIndex = -1)
        return out.toByteArray()
    }

    private var trakCounter = 0

    private fun writeBox(box: Bytes.Box, out: java.io.ByteArrayOutputStream, payloadStart: Long, trackIndex: Int) {
        if (box.type == "moov") trakCounter = 0
        when {
            box.type == "stco" || box.type == "co64" -> out.write(co64(trackIndex, payloadStart))
            box.type in containers -> {
                val t = if (box.type == "trak") trakCounter++ else trackIndex
                val body = java.io.ByteArrayOutputStream()
                for (c in m.children(box.dataStart, box.end)) writeBox(c, body, payloadStart, t)
                val content = body.toByteArray()
                val hdr = ByteArray(8)
                put32(hdr, 0, 8L + content.size)
                for (i in 0 until 4) hdr[4 + i] = box.type[i].code.toByte()
                out.write(hdr)
                out.write(content)
            }
            else -> out.write(index.moovBytes, box.pos, box.size)
        }
    }

    private fun co64(track: Int, payloadStart: Long): ByteArray {
        val offsets = trueChunkOffsets[track]
        val b = ByteArray(16 + 8 * offsets.size)
        put32(b, 0, b.size.toLong())
        b[4] = 'c'.code.toByte(); b[5] = 'o'.code.toByte(); b[6] = '6'.code.toByte(); b[7] = '4'.code.toByte()
        put32(b, 12, offsets.size.toLong())
        for (i in offsets.indices) put64(b, 16 + 8 * i, if (payloadStart == 0L) 0 else mapOffset(offsets[i], payloadStart))
        return b
    }

    companion object {
        private const val WRAP = 1L shl 32

        private fun put32(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 4) b[at + i] = (v shr (24 - 8 * i)).toByte()
        }

        private fun put64(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 8) b[at + i] = (v shr (56 - 8 * i)).toByte()
        }
    }
}
