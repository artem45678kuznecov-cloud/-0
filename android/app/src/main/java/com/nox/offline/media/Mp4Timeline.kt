package com.nox.offline.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.mp4.Track

/**
 * Номера и времена сэмплов дорожки после правок (edit lists) — как в конце
 * BoxParser.parseStbl из Media3 1.5.1, но без массивов на каждый сэмпл:
 * отредактированная дорожка — это несколько отрезков исходных сэмплов, а
 * время сэмпла считается той же формулой в момент чтения.
 */
@UnstableApi
class Mp4Timeline private constructor(
    val segments: List<Segment>,
    /** Сэмплов после правок (sampleCount таблицы Media3). */
    val sampleCount: Int,
    val durationUs: Long,
    /** Наибольший сэмпл среди оставшихся после правок (maximumSize таблицы Media3). */
    val maximumSize: Int,
    val hasPrerollSamples: Boolean,
    private val timescale: Long,
) {
    /** Исходные сэмплы [start, end) с номера [firstVirtual]; время = baseUs + scale(pts − shift). */
    class Segment(val start: Int, val end: Int, val firstVirtual: Int, val baseUs: Long, val shift: Long)

    fun segmentOf(v: Int): Segment {
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segments[mid].firstVirtual <= v) lo = mid else hi = mid - 1
        }
        return segments[lo]
    }

    fun timeUs(s: Segment, pts: Long): Long = s.baseUs + Util.scaleLargeTimestamp(pts - s.shift, C.MICROS_PER_SECOND, timescale)

    /**
     * Поиск по времени — как TrackSampleTable.getIndexOf…SynchronizationSample и
     * Mp4Extractor.getSynchronizationSampleIndex/maybeAdjustSeekOffset.
     */
    inner class Lookup(private val rd: Mp4SampleTables.Reader) {
        private fun orig(v: Int): Int = segmentOf(v).let { it.start + (v - it.firstVirtual) }

        fun timeUs(v: Int): Long = segmentOf(v).let { s -> timeUs(s, rd.pts(s.start + (v - s.firstVirtual))) }

        fun offset(v: Int): Long = rd.offset(orig(v))

        fun earlierOrEqualSync(timeUs: Long): Int {
            var v = binarySearchFloor(sampleCount, timeUs, inclusive = true, stayInBounds = false) { timeUs(it) }
            while (v >= 0) {
                val s = segmentOf(v)
                val sync = rd.syncAtOrBefore(s.start + (v - s.firstVirtual))
                if (sync >= s.start) return s.firstVirtual + (sync - s.start)
                v = s.firstVirtual - 1
            }
            return C.INDEX_UNSET
        }

        fun laterOrEqualSync(timeUs: Long): Int {
            var v = binarySearchCeil(sampleCount, timeUs, inclusive = true, stayInBounds = false) { timeUs(it) }
            while (v in 0 until sampleCount) {
                val s = segmentOf(v)
                val sync = rd.syncAtOrAfter(s.start + (v - s.firstVirtual))
                if (sync != -1 && sync < s.end) return s.firstVirtual + (sync - s.start)
                v = s.firstVirtual + (s.end - s.start)
            }
            return C.INDEX_UNSET
        }

        /** Ключевой не позже [timeUs], а если его нет — первый после. */
        fun syncForSeek(timeUs: Long): Int = earlierOrEqualSync(timeUs).takeIf { it != C.INDEX_UNSET } ?: laterOrEqualSync(timeUs)

        fun adjustOffset(seekTimeUs: Long, offset: Long): Long = syncForSeek(seekTimeUs).let { i ->
            if (i == C.INDEX_UNSET) offset else minOf(offset(i), offset)
        }
    }

    companion object {
        private const val MAX_GAPLESS_TRIM_SIZE_SAMPLES = 4

        fun build(track: Track, t: Mp4SampleTables, gapless: GaplessInfoHolder): Mp4Timeline {
            val n = t.sampleCount
            val rd = t.Reader()
            val timescale = track.timescale
            // Media3: duration = время после последнего сэмпла + ctts последнего.
            val durationUnits = t.totalDecode + t.lastCts
            fun plain(shift: Long, durationUs: Long) =
                Mp4Timeline(listOf(Segment(0, n, 0, 0, shift)), n, durationUs, t.maxSize, false, timescale)

            val durations = track.editListDurations
                ?: return plain(0, Util.scaleLargeTimestamp(durationUnits, C.MICROS_PER_SECOND, timescale))
            val mediaTimes = track.editListMediaTimes!!

            if (durations.size == 1 && track.type == C.TRACK_TYPE_AUDIO && n >= 2) {
                val editStart = mediaTimes[0]
                val editEnd = editStart + Util.scaleLargeTimestamp(durations[0], timescale, track.movieTimescale)
                val last = n - 1
                val latestDelay = Util.constrainValue(MAX_GAPLESS_TRIM_SIZE_SAMPLES, 0, last)
                val earliestPadding = Util.constrainValue(n - MAX_GAPLESS_TRIM_SIZE_SAMPLES, 0, last)
                if (rd.pts(0) <= editStart && editStart < rd.pts(latestDelay) &&
                    rd.pts(earliestPadding) < editEnd && editEnd <= durationUnits) {
                    val padding = durationUnits - editEnd
                    val delay = Util.scaleLargeTimestamp(editStart - rd.pts(0), track.format.sampleRate.toLong(), timescale)
                    val pad = Util.scaleLargeTimestamp(padding, track.format.sampleRate.toLong(), timescale)
                    if ((delay != 0L || pad != 0L) && delay <= Int.MAX_VALUE && pad <= Int.MAX_VALUE) {
                        gapless.encoderDelay = delay.toInt()
                        gapless.encoderPadding = pad.toInt()
                        return plain(0, Util.scaleLargeTimestamp(durations[0], C.MICROS_PER_SECOND, track.movieTimescale))
                    }
                }
            }

            if (durations.size == 1 && durations[0] == 0L) {
                val editStart = mediaTimes[0]
                return plain(editStart, Util.scaleLargeTimestamp(durationUnits - editStart, C.MICROS_PER_SECOND, timescale))
            }

            // Общий случай: каждая правка — отрезок исходных сэмплов от ключевого.
            val omitZeroDurationClippedSample = track.type == C.TRACK_TYPE_AUDIO
            val starts = IntArray(durations.size)
            val ends = IntArray(durations.size)
            var editedCount = 0
            var nextIndex = 0
            var copyMetadata = false
            for (i in durations.indices) {
                val mediaTime = mediaTimes[i]
                if (mediaTime == -1L) continue
                val editDuration = Util.scaleLargeTimestamp(durations[i], timescale, track.movieTimescale)
                val floor = binarySearchFloor(n, mediaTime, inclusive = true, stayInBounds = true) { rd.pts(it) }
                val start = rd.syncAtOrBefore(floor)
                if (start < 0) throw UnsupportedTables("правка начинается раньше первого ключевого кадра")
                var end = binarySearchCeil(n, mediaTime + editDuration, omitZeroDurationClippedSample, false) { rd.pts(it) }
                if (track.type == C.TRACK_TYPE_VIDEO) {
                    while (end < n - 1 && rd.pts(end + 1) <= mediaTime + editDuration) end++
                }
                starts[i] = start
                ends[i] = end
                editedCount += end - start
                copyMetadata = copyMetadata || nextIndex != start
                nextIndex = end
            }
            copyMetadata = copyMetadata || editedCount != n

            val segments = ArrayList<Segment>()
            var pts = 0L
            var virtual = 0
            var preroll = false
            var maxSize = 0
            for (i in durations.indices) {
                val mediaTime = mediaTimes[i]
                if (mediaTime != -1L && ends[i] > starts[i]) {
                    val s = Segment(starts[i], ends[i], virtual, Util.scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale), mediaTime)
                    segments += s
                    virtual += s.end - s.start
                    if (!preroll) preroll = hasNegativeTime(rd, t, s, timescale)
                    if (copyMetadata) maxSize = maxOf(maxSize, t.maxSize(s.start, s.end))
                }
                pts += durations[i]
            }
            if (segments.isEmpty()) throw UnsupportedTables("правки не оставили сэмплов")
            return Mp4Timeline(segments, virtual, Util.scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale),
                if (copyMetadata) maxSize else t.maxSize, preroll, timescale)
        }

        /** Есть ли в отрезке сэмпл с отрицательным временем внутри правки (Format.hasPrerollSamples). */
        private fun hasNegativeTime(rd: Mp4SampleTables.Reader, t: Mp4SampleTables, s: Segment, timescale: Long): Boolean {
            var j = s.start
            while (j < s.end) {
                val decode = rd.decodeTime(j)
                // Дальше время показа уже не меньше начала правки.
                if (decode + t.minCts >= s.shift) return false
                if (Util.scaleLargeTimestamp(decode + rd.cts(j) - s.shift, C.MICROS_PER_SECOND, timescale) < 0) return true
                j++
            }
            return false
        }

        /** java.util.Arrays.binarySearch по «массиву» [get] — тот же порядок проб. */
        private inline fun arraysBinarySearch(n: Int, key: Long, get: (Int) -> Long): Int {
            var low = 0
            var high = n - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val v = get(mid)
                if (v < key) low = mid + 1 else if (v > key) high = mid - 1 else return mid
            }
            return -(low + 1)
        }

        /** Util.binarySearchFloor(long[], …) из Media3. */
        internal inline fun binarySearchFloor(n: Int, value: Long, inclusive: Boolean, stayInBounds: Boolean, get: (Int) -> Long): Int {
            var index = arraysBinarySearch(n, value, get)
            if (index < 0) {
                index = -(index + 2)
            } else {
                while (--index >= 0 && get(index) == value) { }
                if (inclusive) index++
            }
            return if (stayInBounds) maxOf(0, index) else index
        }

        /** Util.binarySearchCeil(long[], …) из Media3. */
        internal inline fun binarySearchCeil(n: Int, value: Long, inclusive: Boolean, stayInBounds: Boolean, get: (Int) -> Long): Int {
            var index = arraysBinarySearch(n, value, get)
            if (index < 0) {
                index = index.inv()
            } else {
                while (++index < n && get(index) == value) { }
                if (inclusive) index--
            }
            return if (stayInBounds) minOf(n - 1, index) else index
        }
    }
}
