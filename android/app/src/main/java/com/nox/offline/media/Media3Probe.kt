package com.nox.offline.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException

/**
 * Разбор файла тем же экстрактором Media3, что и у плеера NOX, — без
 * декодирования. Читаются кадры в начале, у сохранённой позиции, в середине
 * и у конца (через индекс перемотки). Так видно, на каком месте и с какой
 * причиной разборщик плеера останавливается, не открывая сам плеер.
 */
@UnstableApi
object Media3Probe {
    data class Result(val ok: Boolean, val lines: List<String>, val failure: String = "", val failureAtMs: Long = -1)

    private const val SAMPLES_PER_POINT = 120

    fun run(context: Context, uri: Uri, kind: ContainerCheck.Kind, savedPositionMs: Long, ctl: CheckControl): Result {
        val lines = ArrayList<String>()
        val make: () -> Extractor = when (kind) {
            // Тот же выбор, что у плеера: MP4 с огромным индексом — без таблиц в куче.
            ContainerCheck.Kind.MP4 -> { { NoxExtractorsFactory.largeMp4(context, uri) ?: Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0) } }
            ContainerCheck.Kind.WEBM -> { { MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0) } }
            ContainerCheck.Kind.UNKNOWN -> return Result(false, listOf("разборщик плеера: контейнер не MP4 и не WebM — не проверялся"))
        }
        val ds: DataSource = DefaultDataSource.Factory(context).createDataSource()
        val out = Output()
        val ex = make()
        var input: DefaultExtractorInput? = null
        var at = "начало"
        var atMs = 0L
        fun open(pos: Long): DefaultExtractorInput {
            runCatching { ds.close() }
            val len = ds.open(DataSpec.Builder().setUri(uri).setPosition(pos).build())
            return DefaultExtractorInput(ds, pos, if (len == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else pos + len)
        }
        try {
            input = open(0)
            if (!ex.sniff(input)) return Result(false, listOf("разборщик плеера не узнал контейнер"), "sniff вернул false", 0)
            input = open(0)
            ex.init(out)
            val ph = PositionHolder()
            fun drive(label: String) {
                at = label
                val start = out.samples
                while (out.samples - start < SAMPLES_PER_POINT) {
                    ctl.check()
                    val r = ex.read(input!!, ph)
                    if (r == Extractor.RESULT_SEEK) input = open(ph.position)
                    else if (r == Extractor.RESULT_END_OF_INPUT) break
                }
                lines.add("разборщик плеера, $label: прочитано ${out.samples - start} кадров, до ${out.lastUs / 1_000_000} с")
            }
            drive("начало")
            val sm = out.seekMap
            if (sm == null || !sm.isSeekable || sm.durationUs <= 0) {
                lines.add("разборщик плеера: индекса перемотки нет — середина и конец не проверялись")
                return Result(true, lines)
            }
            val points = listOfNotNull(
                savedPositionMs.takeIf { it > 5_000 }?.let { "сохранённая позиция" to it * 1000 },
                "середина" to sm.durationUs / 2,
                "конец" to sm.durationUs * 97 / 100,
            )
            for ((label, us) in points) {
                val sp = sm.getSeekPoints(us).first
                atMs = sp.timeUs / 1000
                ex.seek(sp.position, sp.timeUs)
                input = open(sp.position)
                drive("$label (${sp.timeUs / 1_000_000} с, смещение ${sp.position})")
                ctl.progress(0.9f)
            }
            return Result(true, lines)
        } catch (c: CheckCancelled) {
            throw c
        } catch (t: Throwable) {
            val chain = generateSequence(t) { it.cause }.take(4).joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
            lines.add("разборщик плеера остановился ($at, позиция в файле ${input?.position ?: -1}): $chain")
            return Result(false, lines, chain, atMs)
        } finally {
            runCatching { ds.close() }
            ex.release()
        }
    }

    private class Track : TrackOutput {
        private val scratch = ByteArray(1 shl 16)
        var onSample: (Long) -> Unit = {}
        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return n; throw EOFException() }
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = onSample(timeUs)
    }

    private class Output : ExtractorOutput {
        var samples = 0
        var lastUs = 0L
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = Track().also { t -> t.onSample = { us -> samples++; lastUs = maxOf(lastUs, us) } }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
    }
}
