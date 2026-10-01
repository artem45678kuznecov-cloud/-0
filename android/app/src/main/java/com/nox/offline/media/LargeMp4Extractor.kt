package com.nox.offline.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4Box
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mp4.Track
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.nox.offline.core.NoxLog
import java.io.IOException
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Экстрактор MP4 без фрагментов для фильмов с огромным индексом.
 *
 * Делает то же, что Mp4Extractor из Media3 1.5.1, но таблицы сэмплов не
 * разворачиваются в Java-кучу: они остаются в файле ([Mp4SampleTables]) и
 * читаются окнами через отдельный дескриптор [openSource] только на чтение.
 * Формат дорожек, правки (edit lists), сведения для бесшовного звука и
 * метаданные берутся из тех же разборщиков Media3 (BoxParser), времена
 * считаются теми же формулами — плеер получает те же сэмплы.
 *
 * Что этот путь не поддерживает (шифрование, TrueHD, AC-4, PCM, подписи
 * CEA-608 в cdat, несогласованные таблицы), уходит в обычный Mp4Extractor —
 * с перемоткой источника в начало файла до выдачи первой дорожки.
 */
@UnstableApi
class LargeMp4Extractor(private val openSource: () -> ByteSource) : Extractor, SeekMap {
    private val subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var rawOutput: ExtractorOutput = ExtractorOutput.PLACEHOLDER
    private var output: ExtractorOutput = ExtractorOutput.PLACEHOLDER
    private var delegate: Extractor? = null
    private var fallbackPending = false
    private var source: ByteSource? = null

    // Разбор боксов — как в Mp4Extractor.
    private var state = STATE_HEADER
    private val header = ParsableByteArray(Mp4Box.LONG_HEADER_SIZE)
    private var atomType = 0
    private var atomSize = 0L
    private var headerRead = 0
    private var atomData: ParsableByteArray? = null
    private val containers = ArrayDeque<Mp4Box.ContainerBox>()
    private val tables = IdentityHashMap<Mp4Box.ContainerBox, MutableMap<Int, TableBox>>()
    private var seenFtyp = false
    private var isQuickTime = false

    // Дорожки и чтение сэмплов.
    private var tracks: Array<LargeTrack> = emptyArray()
    private var firstVideoTrackIndex = C.INDEX_UNSET
    private var durationUs = C.TIME_UNSET
    /** Средний поток всех дорожек (байт на микросекунду) — оценка отставания в байтах по времени. */
    private var bytesPerUs = 0.0
    private var sampleTrack = C.INDEX_UNSET
    private var sampleBytesRead = 0
    private var sampleBytesWritten = 0
    private var nalRemaining = 0
    private val nalStartCode = ParsableByteArray(NalUnitUtil.NAL_START_CODE)
    private val nalPrefix = ParsableByteArray(5)

    /** Почему работает обычный Mp4Extractor (для журнала и тестов), или null. */
    var fallbackReason: String? = null
        private set

    override fun sniff(input: ExtractorInput): Boolean =
        Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0).sniff(input)

    override fun init(output: ExtractorOutput) {
        rawOutput = output
        this.output = SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
    }

    override fun seek(position: Long, timeUs: Long) {
        delegate?.let { it.seek(position, timeUs); return }
        containers.clear()
        headerRead = 0
        sampleTrack = C.INDEX_UNSET
        sampleBytesRead = 0
        sampleBytesWritten = 0
        nalRemaining = 0
        if (position == 0L) {
            // Media3 в этом случае разбирает moov заново; таблицы уже есть — только к первому сэмплу.
            if (state != STATE_SAMPLE) { tables.clear(); enterHeaderState() }
            else for (t in tracks) t.moveTo(0)
        } else {
            for (t in tracks) t.moveTo(t.syncIndexForSeek(timeUs))
        }
    }

    override fun release() {
        delegate?.release()
        closeSource()
    }

    private fun closeSource() {
        runCatching { source?.close() }
        source = null
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        delegate?.let { return it.read(input, seekPosition) }
        while (true) {
            val seek = when (state) {
                STATE_HEADER -> if (readHeader(input)) false else return Extractor.RESULT_END_OF_INPUT
                STATE_PAYLOAD -> readPayload(input, seekPosition)
                else -> return readSample(input, seekPosition)
            }
            if (fallbackPending) {
                // Обычный Mp4Extractor начинает с начала файла.
                fallbackPending = false
                seekPosition.position = 0
                return Extractor.RESULT_SEEK
            }
            if (seek) return Extractor.RESULT_SEEK
        }
    }

    // ------------------------------------------------------------------
    //  Боксы
    // ------------------------------------------------------------------

    private fun enterHeaderState() {
        state = STATE_HEADER
        headerRead = 0
    }

    private fun readHeader(input: ExtractorInput): Boolean {
        if (headerRead == 0) {
            if (!input.readFully(header.data, 0, Mp4Box.HEADER_SIZE, true)) return false
            headerRead = Mp4Box.HEADER_SIZE
            header.position = 0
            atomSize = header.readUnsignedInt()
            atomType = header.readInt()
        }
        if (atomSize == Mp4Box.DEFINES_LARGE_SIZE.toLong()) {
            val rest = Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE
            input.readFully(header.data, Mp4Box.HEADER_SIZE, rest)
            headerRead += rest
            atomSize = header.readUnsignedLongToLong()
        } else if (atomSize == Mp4Box.EXTENDS_TO_END_SIZE.toLong()) {
            var end = input.length
            if (end == C.LENGTH_UNSET.toLong()) containers.peek()?.let { end = it.endPosition }
            if (end != C.LENGTH_UNSET.toLong()) atomSize = end - input.position + headerRead
        }
        if (atomSize < headerRead) {
            throw ParserException.createForUnsupportedContainerFeature("Atom size less than header length (unsupported).")
        }
        when {
            atomType in CONTAINERS -> {
                val end = input.position + atomSize - headerRead
                if (atomSize != headerRead.toLong() && atomType == Mp4Box.TYPE_meta) skipMetaHeaderRemainder(input)
                containers.push(Mp4Box.ContainerBox(atomType, end))
                if (atomSize == headerRead.toLong()) processAtomEnded(end) else enterHeaderState()
            }
            atomType in TABLES && containers.isNotEmpty() -> {
                // Таблицу сэмплов не читаем в память — запоминаем, где она в файле.
                tables.getOrPut(containers.peek()!!) { HashMap() }[atomType] = TableBox(input.position, atomSize - headerRead)
                atomData = null
                state = STATE_PAYLOAD
            }
            atomType in LEAVES -> {
                if (headerRead != Mp4Box.HEADER_SIZE || atomSize > Int.MAX_VALUE) {
                    throw ParserException.createForUnsupportedContainerFeature("Unsupported leaf atom size")
                }
                val data = ParsableByteArray(atomSize.toInt())
                System.arraycopy(header.data, 0, data.data, 0, Mp4Box.HEADER_SIZE)
                atomData = data
                state = STATE_PAYLOAD
            }
            else -> {
                atomData = null
                state = STATE_PAYLOAD
            }
        }
        return true
    }

    /** true — нужно переоткрыть источник с позиции в [positionHolder]. */
    private fun readPayload(input: ExtractorInput, positionHolder: PositionHolder): Boolean {
        val payload = atomSize - headerRead
        val end = input.position + payload
        var seek = false
        val data = atomData
        if (data != null) {
            input.readFully(data.data, headerRead, payload.toInt())
            if (atomType == Mp4Box.TYPE_ftyp) {
                seenFtyp = true
                isQuickTime = isQuickTimeBrand(data)
            } else if (containers.isNotEmpty()) {
                containers.peek()!!.add(Mp4Box.LeafBox(atomType, data))
            }
        } else {
            // Файлы QuickTime без ftyp перед mdat — как в Media3.
            if (!seenFtyp && atomType == Mp4Box.TYPE_mdat) isQuickTime = true
            if (payload < RELOAD_MINIMUM_SEEK_DISTANCE) input.skipFully(payload.toInt())
            else { positionHolder.position = input.position + payload; seek = true }
        }
        processAtomEnded(end)
        return seek && state != STATE_SAMPLE
    }

    private fun processAtomEnded(end: Long) {
        while (containers.isNotEmpty() && containers.peek()!!.endPosition == end) {
            val box = containers.pop()
            if (box.type == Mp4Box.TYPE_moov) {
                processMoov(box)
                containers.clear()
                tables.clear()
                if (delegate == null) state = STATE_SAMPLE
            } else if (containers.isNotEmpty()) {
                containers.peek()!!.add(box)
            }
        }
        if (state != STATE_SAMPLE) enterHeaderState()
    }

    private fun skipMetaHeaderRemainder(input: ExtractorInput) {
        val scratch = ParsableByteArray(8)
        input.peekFully(scratch.data, 0, 8)
        BoxParser.maybeSkipRemainingMetaBoxHeaderBytes(scratch)
        input.skipFully(scratch.position)
        input.resetPeekPosition()
    }

    private fun isQuickTimeBrand(ftyp: ParsableByteArray): Boolean {
        ftyp.position = Mp4Box.HEADER_SIZE
        val major = ftyp.readInt()
        if (major == BRAND_QUICKTIME) return true
        if (major == BRAND_HEIC) return false
        ftyp.skipBytes(4)
        while (ftyp.bytesLeft() > 0) {
            val brand = ftyp.readInt()
            if (brand == BRAND_QUICKTIME) return true
            if (brand == BRAND_HEIC) return false
        }
        return false
    }

    // ------------------------------------------------------------------
    //  moov → дорожки
    // ------------------------------------------------------------------

    private class Plan(val track: Track, val tables: Mp4SampleTables, val timeline: Mp4Timeline)

    private fun fallback(reason: String) {
        fallbackReason = reason
        NoxLog.event("mp4-large-fallback", "reason" to reason.take(120))
        closeSource()
        val d = Mp4Extractor(subtitleParserFactory, 0)
        d.init(rawOutput)
        delegate = d
        fallbackPending = true
    }

    private fun processMoov(moov: Mp4Box.ContainerBox) {
        val mvhd = moov.getLeafBoxOfType(Mp4Box.TYPE_mvhd)
            ?: throw ParserException.createForMalformedContainer("moov без mvhd", null)
        val mdtaMetadata = moov.getContainerBoxOfType(Mp4Box.TYPE_meta)?.let { BoxParser.parseMdtaFromMeta(it) }
        val gapless = GaplessInfoHolder()
        val udtaMetadata = moov.getLeafBoxOfType(Mp4Box.TYPE_udta)?.let { BoxParser.parseUdta(it) }
        if (udtaMetadata != null) gapless.setFromMetadata(udtaMetadata)
        val mvhdMetadata = Metadata(BoxParser.parseMvhd(mvhd.data))

        val plans = ArrayList<Plan>()
        try {
            val src = source ?: openSource().also { source = it }
            for (trak in moov.containerChildren) {
                if (trak.type != Mp4Box.TYPE_trak) continue
                var track = BoxParser.parseTrak(trak, mvhd, C.TIME_UNSET, null, false, isQuickTime) ?: continue
                unsupportedReason(track)?.let { throw UnsupportedTables(it) }
                val stbl = trak.getContainerBoxOfType(Mp4Box.TYPE_mdia)?.getContainerBoxOfType(Mp4Box.TYPE_minf)
                    ?.getContainerBoxOfType(Mp4Box.TYPE_stbl) ?: throw UnsupportedTables("нет stbl")
                val t = tables[stbl] ?: throw UnsupportedTables("нет таблиц сэмплов")
                if (t[Mp4Box.TYPE_stsz] == null) throw UnsupportedTables("размеры сэмплов в stz2")
                val longOffsets = t[Mp4Box.TYPE_stco] == null
                val st = Mp4SampleTables(src, t.getValue(Mp4Box.TYPE_stsz),
                    t[Mp4Box.TYPE_stco] ?: t[Mp4Box.TYPE_co64] ?: throw UnsupportedTables("нет смещений чанков"), longOffsets,
                    t[Mp4Box.TYPE_stsc] ?: throw UnsupportedTables("нет stsc"),
                    t[Mp4Box.TYPE_stts] ?: throw UnsupportedTables("нет stts"),
                    t[Mp4Box.TYPE_ctts], t[Mp4Box.TYPE_stss])
                if (track.type == C.TRACK_TYPE_VIDEO && track.mediaDurationUs > 0) {
                    val frameRate = st.sampleCount / (track.mediaDurationUs / 1_000_000f)
                    track = track.copyWithFormat(track.format.buildUpon().setFrameRate(frameRate).build())
                }
                val timeline = Mp4Timeline.build(track, st, gapless)
                if (timeline.hasPrerollSamples) {
                    track = track.copyWithFormat(track.format.buildUpon().setHasPrerollSamples(true).build())
                }
                plans += Plan(track, st, timeline)
            }
        } catch (e: UnsupportedTables) {
            fallback(e.message ?: "не поддерживается")
            return
        } catch (e: IOException) {
            fallback("таблицы не читаются из файла: ${e.message}")
            return
        }

        // Дальше — как Mp4Extractor.processMoovAtom.
        val list = ArrayList<LargeTrack>()
        var totalBytes = 0L
        var trackIndex = 0
        for (p in plans) {
            if (p.timeline.sampleCount == 0) continue
            val track = p.track
            val trackDurationUs = if (track.durationUs != C.TIME_UNSET) track.durationUs else p.timeline.durationUs
            durationUs = maxOf(durationUs, trackDurationUs)
            val out = output.track(trackIndex++, track.type)
            val fb = track.format.buildUpon().setMaxInputSize(p.timeline.maximumSize + 3 * 10)
            if (track.type == C.TRACK_TYPE_VIDEO) {
                if (track.format.frameRate == Format.NO_VALUE.toFloat() && trackDurationUs > 0 && p.timeline.sampleCount > 0) {
                    fb.setFrameRate(p.timeline.sampleCount / (trackDurationUs / 1_000_000f))
                }
                fb.setRoleFlags(track.format.roleFlags)
            }
            if (track.type == C.TRACK_TYPE_AUDIO && gapless.hasGaplessInfo()) {
                fb.setEncoderDelay(gapless.encoderDelay).setEncoderPadding(gapless.encoderPadding)
            }
            formatMetadata(track.type, mdtaMetadata, fb, udtaMetadata, mvhdMetadata)
            out.format(fb.build())
            if (track.type == C.TRACK_TYPE_VIDEO && firstVideoTrackIndex == C.INDEX_UNSET) firstVideoTrackIndex = list.size
            totalBytes += p.tables.totalBytes
            list += LargeTrack(track, p.tables, p.timeline, out).also { it.moveTo(0) }
        }
        tracks = list.toTypedArray()
        bytesPerUs = if (durationUs > 0) totalBytes.toDouble() / durationUs else 0.0
        NoxLog.event("mp4-large", "tracks" to tracks.size, "samples" to tracks.sumOf { it.timeline.sampleCount.toLong() },
            "durationS" to durationUs / 1_000_000)
        output.endTracks()
        output.seekMap(this)
    }

    private fun unsupportedReason(track: Track): String? {
        if (track.sampleTransformation != Track.TRANSFORMATION_NONE) return "подписи CEA-608 в cdat"
        if (track.format.drmInitData != null || track.getSampleDescriptionEncryptionBox(0) != null) return "шифрование"
        return when (track.format.sampleMimeType) {
            MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AC4, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_MLAW, MimeTypes.AUDIO_ALAW ->
                "кодек ${track.format.sampleMimeType}"
            else -> null
        }
    }

    /** Как MetadataUtil.setFormatMetadata из Media3 (класс закрыт пакетом). */
    private fun formatMetadata(type: Int, mdta: Metadata?, builder: Format.Builder, vararg additional: Metadata?) {
        var m = Metadata()
        if (mdta != null) {
            for (i in 0 until mdta.length()) {
                val entry = mdta.get(i)
                if (entry is MdtaMetadataEntry) {
                    if (entry.key == MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS) {
                        if (type == C.TRACK_TYPE_VIDEO) m = m.copyWithAppendedEntries(entry)
                    } else {
                        m = m.copyWithAppendedEntries(entry)
                    }
                }
            }
        }
        for (a in additional) m = m.copyWithAppendedEntriesFrom(a)
        if (m.length() > 0) builder.setMetadata(m)
    }

    // ------------------------------------------------------------------
    //  Сэмплы — как Mp4Extractor.readSample
    // ------------------------------------------------------------------

    private fun readSample(input: ExtractorInput, positionHolder: PositionHolder): Int {
        val inputPosition = input.position
        if (sampleTrack == C.INDEX_UNSET) {
            sampleTrack = nextTrack(inputPosition)
            if (sampleTrack == C.INDEX_UNSET) return Extractor.RESULT_END_OF_INPUT
        }
        val t = tracks[sampleTrack]
        val out = t.output
        val position = t.offset
        var sampleSize = t.size
        val skip = position - inputPosition + sampleBytesRead
        if (skip < 0 || skip >= RELOAD_MINIMUM_SEEK_DISTANCE) {
            positionHolder.position = position
            return Extractor.RESULT_SEEK
        }
        input.skipFully(skip.toInt())
        val nalLength = t.track.nalUnitLengthFieldLength
        if (nalLength != 0) {
            val prefix = nalPrefix.data
            prefix[0] = 0; prefix[1] = 0; prefix[2] = 0
            val prefixLength = nalLength + 1
            val diff = 4 - nalLength
            // Длины NAL в файле заменяются стартовыми кодами — декодер ждёт их.
            while (sampleBytesWritten < sampleSize) {
                if (nalRemaining == 0) {
                    input.readFully(prefix, diff, prefixLength)
                    sampleBytesRead += prefixLength
                    nalPrefix.position = 0
                    val nal = nalPrefix.readInt()
                    if (nal < 1) throw ParserException.createForMalformedContainer("Invalid NAL length", null)
                    nalRemaining = nal - 1
                    nalStartCode.position = 0
                    out.sampleData(nalStartCode, 4)
                    out.sampleData(nalPrefix, 1)
                    sampleBytesWritten += 5
                    sampleSize += diff
                } else {
                    val written = out.sampleData(input, nalRemaining, false)
                    sampleBytesRead += written
                    sampleBytesWritten += written
                    nalRemaining -= written
                }
            }
        } else {
            while (sampleBytesWritten < sampleSize) {
                val written = out.sampleData(input, sampleSize - sampleBytesWritten, false)
                sampleBytesRead += written
                sampleBytesWritten += written
                nalRemaining -= written
            }
        }
        out.sampleMetadata(t.timeUs, t.flags, sampleSize, 0, null)
        t.next()
        sampleTrack = C.INDEX_UNSET
        sampleBytesRead = 0
        sampleBytesWritten = 0
        nalRemaining = 0
        return Extractor.RESULT_CONTINUE
    }

    /**
     * Какую дорожку читать дальше — как Mp4Extractor.getTrackIndexOfNextReadSample:
     * ближайший по файлу сэмпл без переоткрытия источника, если ни одна дорожка
     * не отстала больше чем на 10 МБ. Отставание Media3 считает накопленными
     * размерами сэмплов (массив на каждый сэмпл), здесь — по времени и
     * среднему потоку файла.
     */
    private fun nextTrack(inputPosition: Long): Int {
        var preferredSkip = Long.MAX_VALUE
        var preferredReload = true
        var preferred = C.INDEX_UNSET
        var preferredTime = Long.MAX_VALUE
        var minTime = Long.MAX_VALUE
        var minTimeReload = true
        var minTimeTrack = C.INDEX_UNSET
        for (i in tracks.indices) {
            val t = tracks[i]
            if (t.done) continue
            val time = t.timeUs
            val skip = t.offset - inputPosition
            val reload = skip < 0 || skip >= RELOAD_MINIMUM_SEEK_DISTANCE
            if ((!reload && preferredReload) || (reload == preferredReload && skip < preferredSkip)) {
                preferredReload = reload
                preferredSkip = skip
                preferred = i
                preferredTime = time
            }
            if (time < minTime) {
                minTime = time
                minTimeReload = reload
                minTimeTrack = i
            }
        }
        return if (minTime == Long.MAX_VALUE || !minTimeReload ||
            (preferredTime - minTime) * bytesPerUs < MAXIMUM_READ_AHEAD_BYTES_STREAM) preferred else minTimeTrack
    }

    // ------------------------------------------------------------------
    //  SeekMap — как Mp4Extractor.getSeekPoints
    // ------------------------------------------------------------------

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val list = tracks
        if (list.isEmpty()) return SeekMap.SeekPoints(SeekPoint.START)
        val firstTimeUs: Long
        var firstOffset: Long
        var secondTimeUs = C.TIME_UNSET
        var secondOffset = C.INDEX_UNSET.toLong()
        val main = firstVideoTrackIndex
        if (main != C.INDEX_UNSET) {
            val look = list[main].lookup()
            val index = look.syncForSeek(timeUs)
            if (index == C.INDEX_UNSET) return SeekMap.SeekPoints(SeekPoint.START)
            val sampleTimeUs = look.timeUs(index)
            firstTimeUs = sampleTimeUs
            firstOffset = look.offset(index)
            if (sampleTimeUs < timeUs && index < list[main].timeline.sampleCount - 1) {
                val second = look.laterOrEqualSync(timeUs)
                if (second != C.INDEX_UNSET && second != index) {
                    secondTimeUs = look.timeUs(second)
                    secondOffset = look.offset(second)
                }
            }
        } else {
            firstTimeUs = timeUs
            firstOffset = Long.MAX_VALUE
        }
        for (i in list.indices) {
            if (i == main) continue
            val look = list[i].lookup()
            firstOffset = look.adjustOffset(firstTimeUs, firstOffset)
            if (secondTimeUs != C.TIME_UNSET) secondOffset = look.adjustOffset(secondTimeUs, secondOffset)
        }
        val first = SeekPoint(firstTimeUs, firstOffset)
        return if (secondTimeUs == C.TIME_UNSET) SeekMap.SeekPoints(first)
        else SeekMap.SeekPoints(first, SeekPoint(secondTimeUs, secondOffset))
    }

    companion object {
        private const val STATE_HEADER = 0
        private const val STATE_PAYLOAD = 1
        private const val STATE_SAMPLE = 2
        private const val RELOAD_MINIMUM_SEEK_DISTANCE = 256 * 1024L
        private const val MAXIMUM_READ_AHEAD_BYTES_STREAM = 10 * 1024 * 1024L
        private const val BRAND_QUICKTIME = 0x71742020 // "qt  "
        private const val BRAND_HEIC = 0x68656963 // "heic"

        private val CONTAINERS = setOf(Mp4Box.TYPE_moov, Mp4Box.TYPE_trak, Mp4Box.TYPE_mdia, Mp4Box.TYPE_minf,
            Mp4Box.TYPE_stbl, Mp4Box.TYPE_edts, Mp4Box.TYPE_meta)
        private val TABLES = setOf(Mp4Box.TYPE_stts, Mp4Box.TYPE_stss, Mp4Box.TYPE_ctts, Mp4Box.TYPE_stsc,
            Mp4Box.TYPE_stsz, Mp4Box.TYPE_stz2, Mp4Box.TYPE_stco, Mp4Box.TYPE_co64)
        private val LEAVES = setOf(Mp4Box.TYPE_mdhd, Mp4Box.TYPE_mvhd, Mp4Box.TYPE_hdlr, Mp4Box.TYPE_stsd,
            Mp4Box.TYPE_elst, Mp4Box.TYPE_tkhd, Mp4Box.TYPE_ftyp, Mp4Box.TYPE_udta, Mp4Box.TYPE_keys, Mp4Box.TYPE_ilst)
    }
}

/** Дорожка при чтении: курсор по файлу и номер сэмпла с учётом правок. */
@UnstableApi
internal class LargeTrack(val track: Track, val tables: Mp4SampleTables, val timeline: Mp4Timeline, val output: TrackOutput) {
    private val cursor = tables.Cursor()
    /** Номер сэмпла после правок (sampleIndex у Media3). */
    private var v = 0
    private var segment: Mp4Timeline.Segment? = null

    val done: Boolean get() = v >= timeline.sampleCount
    val offset: Long get() = cursor.offset
    val size: Int get() = cursor.size
    val timeUs: Long get() = timeline.timeUs(segment!!, cursor.pts)
    val flags: Int
        get() {
            var f = if (cursor.sync) C.BUFFER_FLAG_KEY_FRAME else 0
            if (v == timeline.sampleCount - 1) f = f or C.BUFFER_FLAG_LAST_SAMPLE
            return f
        }

    fun moveTo(index: Int) {
        v = index
        if (done) return
        val s = timeline.segmentOf(index)
        segment = s
        cursor.positionAt(s.start + (index - s.firstVirtual))
    }

    fun next() {
        v++
        if (done) return
        val s = segment!!
        val orig = s.start + (v - s.firstVirtual)
        if (orig < s.end && orig == cursor.index + 1) cursor.advance() else moveTo(v)
    }

    /** Ключевой сэмпл для перемотки к [timeUs] (Mp4Extractor.updateSampleIndex). */
    fun syncIndexForSeek(timeUs: Long): Int {
        val i = lookup().syncForSeek(timeUs)
        return if (i == C.INDEX_UNSET) timeline.sampleCount else i
    }

    /** Поиск по времени — со своими окнами чтения (getSeekPoints зовут из потока плеера). */
    fun lookup() = timeline.Lookup(tables.Reader())
}
