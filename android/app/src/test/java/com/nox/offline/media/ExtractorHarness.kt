@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Прогон экстрактора Media3 по файлу на JVM — как его гоняет ProgressiveMediaPeriod:
 * чтение, RESULT_SEEK с переоткрытием на нужном смещении, перемотка через SeekMap.
 * Каждый сэмпл сводится к (время, флаги, размер, CRC32 данных); по дорожке
 * считается общий отпечаток, первые [keep] сэмплов сохраняются целиком.
 */
class ExtractorHarness(private val file: File, private val extractor: Extractor, private val keep: Int = 0) {
    data class Sample(val track: Int, val timeUs: Long, val flags: Int, val size: Int, val crc: Long)

    class Track(val id: Int, private val harness: ExtractorHarness) : TrackOutput {
        var format: Format? = null
        var count = 0L
        var digest = 17L
        var lastTimeUs = C.TIME_UNSET
        private val crc = CRC32()
        private var bytes = 0
        private val scratch = ByteArray(64 * 1024)

        override fun format(format: Format) { this.format = format }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return n
                throw EOFException()
            }
            crc.update(scratch, 0, n)
            bytes += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            crc.update(data.data, data.position, length)
            data.skipBytes(length)
            bytes += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val s = Sample(id, timeUs, flags, size, crc.value)
            check(bytes == size + offset) { "дорожка $id: данных $bytes, а размер сэмпла $size (+$offset)" }
            digest = digest * 1_000_003 + (timeUs * 31 + flags) * 17 + size * 7L + s.crc
            count++
            lastTimeUs = timeUs
            if (harness.kept.size < harness.keep) harness.kept.add(s)
            harness.onSample?.invoke(s)
            crc.reset()
            bytes = 0
        }
    }

    val tracks = sortedMapOf<Int, Track>()
    var seekMap: SeekMap? = null
    val kept = ArrayList<Sample>()
    var onSample: ((Sample) -> Unit)? = null
    /** Сколько раз экстрактор попросил переоткрыть файл на другом смещении. */
    var reloads = 0
    var tracksEnded = false

    private val output = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track(id, this@ExtractorHarness) }
        override fun endTracks() { tracksEnded = true }
        override fun seekMap(seekMap: SeekMap) { this@ExtractorHarness.seekMap = seekMap }
    }

    private val raf = RandomAccessFile(file, "r")
    private val length = raf.length()
    private var input = open(0)

    private fun open(position: Long): DefaultExtractorInput {
        val reader = object : DataReader {
            var pos = position
            override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
                if (len == 0) return 0
                raf.seek(pos)
                val n = raf.read(buffer, offset, len)
                if (n < 0) return C.RESULT_END_OF_INPUT
                pos += n
                return n
            }
        }
        return DefaultExtractorInput(reader, position, length)
    }

    init {
        check(extractor.sniff(input)) { "экстрактор не узнал ${file.name}" }
        input = open(0)
        extractor.init(output)
    }

    val samples: Long get() = tracks.values.sumOf { it.count }

    /** Читать, пока не получено [maxSamples] новых сэмплов или не кончился файл (или пока [until] не скажет хватит). */
    fun read(maxSamples: Long = Long.MAX_VALUE, until: () -> Boolean = { false }): Boolean {
        val start = samples
        val ph = PositionHolder()
        while (samples - start < maxSamples && !until()) {
            when (extractor.read(input, ph)) {
                Extractor.RESULT_SEEK -> { reloads++; input = open(ph.position) }
                Extractor.RESULT_END_OF_INPUT -> return false
            }
        }
        return true
    }

    /**
     * Перемотка как у ProgressiveMediaPeriod: смещение — из первой точки SeekMap,
     * а в seek() экстрактора уходит запрошенное время (не время точки).
     */
    fun seekTo(timeUs: Long): SeekMap.SeekPoints {
        val points = checkNotNull(seekMap).getSeekPoints(timeUs)
        extractor.seek(points.first.position, timeUs)
        input = open(points.first.position)
        return points
    }

    fun close() = raf.close()
}
