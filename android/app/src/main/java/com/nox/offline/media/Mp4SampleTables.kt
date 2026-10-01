package com.nox.offline.media

import java.io.IOException

/** Данные бокса таблицы в файле: смещение после заголовка и длина. */
data class TableBox(val dataPos: Long, val dataSize: Long)

/** Таблицы этой дорожки не подходят для разбора из файла — нужен обычный путь Media3. */
class UnsupportedTables(reason: String) : IOException(reason)

/**
 * Таблицы сэмплов одной дорожки MP4 (stsz, stco/co64, stsc, stts, ctts, stss),
 * которые остаются в файле.
 *
 * Mp4Extractor из Media3 разворачивает их в массивы по 32 байта на сэмпл и
 * вдобавок держит весь moov в памяти: у 15-часового фильма это ~150 МиБ кучи
 * при лимите 256 МиБ. Здесь в памяти только разреженные указатели (каждая
 * [STEP]-я запись), а нужные записи читаются окнами через [CachedReader].
 *
 * Значения повторяют разбор BoxParser.parseStbl из Media3 1.5.1 для корректных
 * таблиц. Всё, что Media3 разбирает с оговорками (несогласованные таблицы,
 * пустые записи, отрицательные шаги времени), отклоняется [UnsupportedTables] —
 * такой файл открывается обычным путём.
 */
class Mp4SampleTables(
    private val src: ByteSource,
    stsz: TableBox,
    chunkOffsets: TableBox,
    private val longOffsets: Boolean,
    stsc: TableBox,
    stts: TableBox,
    ctts: TableBox?,
    stss: TableBox?,
) {
    val sampleCount: Int
    /** Размер всех сэмплов (stsz с общим размером) или 0 — у каждого свой. */
    private val fixedSize: Int
    private val stszEntries = stsz.dataPos + 12
    val chunkCount: Int
    private val chunkEntries = chunkOffsets.dataPos + 8
    private val stscCount: Int
    private val stscEntries = stsc.dataPos + 8
    private val sttsCount: Int
    private val sttsEntries = stts.dataPos + 8
    private val cttsCount: Int
    private val cttsEntries = (ctts?.dataPos ?: 0) + 8
    val hasCtts = ctts != null
    /** stss без записей Media3 считает отсутствующим: тогда все сэмплы ключевые. */
    val syncCount: Int
    private val stssEntries = (stss?.dataPos ?: 0) + 8
    val allSync: Boolean

    val maxSize: Int
    val totalBytes: Long
    /** Время декодирования после последнего сэмпла (сумма stts), в единицах дорожки. */
    val totalDecode: Long
    /** Смещение ctts последнего сэмпла: Media3 прибавляет его к длительности. */
    val lastCts: Int
    /** Наименьшее смещение ctts: ниже него время показа не опускается. */
    val minCts: Int

    // Разреженные указатели: запись k*STEP и с какого сэмпла она начинается.
    private val sttsSample: IntArray
    private val sttsTime: LongArray
    private val cttsSample: IntArray
    private val stscSample: LongArray

    init {
        val r = CachedReader(src, 64 * 1024)
        if (stsz.dataSize < 12) throw UnsupportedTables("короткий stsz")
        fixedSize = r.u32(stsz.dataPos + 4).toIntChecked("размер сэмпла")
        sampleCount = r.u32(stsz.dataPos + 8).toIntChecked("число сэмплов")
        if (fixedSize == 0 && stsz.dataSize < 12 + 4L * sampleCount) throw UnsupportedTables("stsz короче числа сэмплов")
        if (sampleCount == 0) throw UnsupportedTables("дорожка без сэмплов")

        // stsz: наибольший сэмпл и общий объём — один проход по таблице блоками.
        var max = fixedSize
        var total = fixedSize.toLong() * sampleCount
        if (fixedSize == 0) {
            total = 0
            scan(src, stszEntries, sampleCount, 4) { b, o, _ ->
                val v = be32(b, o)
                if (v < 0) throw UnsupportedTables("размер сэмпла больше 2^31")
                if (v > max) max = v
                total += v
            }
        }
        maxSize = max
        totalBytes = total

        chunkCount = r.u32(chunkOffsets.dataPos + 4).toIntChecked("число чанков")
        if (chunkOffsets.dataSize < 8 + (if (longOffsets) 8L else 4L) * chunkCount) throw UnsupportedTables("таблица чанков короче заявленного")

        // stsc: первая запись — чанк 1, далее строго по возрастанию; сэмплов в чанках не меньше, чем в stsz.
        stscCount = r.u32(stsc.dataPos + 4).toIntChecked("число записей stsc")
        if (stscCount == 0 || stsc.dataSize < 8 + 12L * stscCount) throw UnsupportedTables("stsc пуст или короче заявленного")
        if (r.u32(stscEntries) != 1L) throw UnsupportedTables("первая запись stsc не с чанка 1")
        val stscIdx = (stscCount + STEP - 1) / STEP
        stscSample = LongArray(stscIdx)
        var samplesBefore = 0L
        var prevFirst = 0L
        var prevSpc = 0L
        // Сэмплы записи известны, когда прочитано начало следующей: считаем со сдвигом на одну.
        scan(src, stscEntries, stscCount, 12) { b, o, e ->
            val first = be32(b, o).toLong() and 0xFFFFFFFFL
            val spc = be32(b, o + 4).toLong() and 0xFFFFFFFFL
            if (first <= prevFirst) throw UnsupportedTables("stsc не по возрастанию")
            if (spc > Int.MAX_VALUE) throw UnsupportedTables("слишком много сэмплов в чанке")
            if (e > 0) samplesBefore += runChunks(prevFirst - 1, first - 1) * prevSpc
            if (e % STEP == 0) stscSample[e / STEP] = samplesBefore
            prevFirst = first
            prevSpc = spc
        }
        samplesBefore += runChunks(prevFirst - 1, chunkCount.toLong()) * prevSpc
        if (samplesBefore < sampleCount) throw UnsupportedTables("в чанках меньше сэмплов, чем в stsz")

        // stts: шаги неотрицательные, записей с нулём сэмплов нет, сумма — ровно число сэмплов.
        sttsCount = r.u32(stts.dataPos + 4).toIntChecked("число записей stts")
        if (sttsCount == 0 || stts.dataSize < 8 + 8L * sttsCount) throw UnsupportedTables("stts пуст или короче заявленного")
        val sttsIdx = (sttsCount + STEP - 1) / STEP
        sttsSample = IntArray(sttsIdx)
        sttsTime = LongArray(sttsIdx)
        var n = 0L
        var time = 0L
        scan(src, sttsEntries, sttsCount, 8) { b, o, e ->
            val count = be32(b, o).toLong() and 0xFFFFFFFFL
            val delta = be32(b, o + 4)
            if (count == 0L || delta < 0) throw UnsupportedTables("stts с пустой записью или отрицательным шагом")
            if (e % STEP == 0) { sttsSample[e / STEP] = n.toInt(); sttsTime[e / STEP] = time }
            n += count
            time += count * delta
            if (n > sampleCount) throw UnsupportedTables("в stts больше сэмплов, чем в stsz")
        }
        if (n != sampleCount.toLong()) throw UnsupportedTables("в stts другое число сэмплов")
        totalDecode = time

        // ctts: записи с нулём сэмплов Media3 пропускает; сумма — ровно число сэмплов.
        if (ctts != null) {
            cttsCount = r.u32(ctts.dataPos + 4).toIntChecked("число записей ctts")
            if (ctts.dataSize < 8 + 8L * cttsCount) throw UnsupportedTables("ctts короче заявленного")
            val cttsIdx = (cttsCount + STEP - 1) / STEP
            cttsSample = IntArray(cttsIdx)
            var c = 0L
            var minOffset = Int.MAX_VALUE
            var last = 0
            scan(src, cttsEntries, cttsCount, 8) { b, o, e ->
                val count = be32(b, o).toLong() and 0xFFFFFFFFL
                val offset = be32(b, o + 4)
                if (e % STEP == 0) cttsSample[e / STEP] = c.toInt()
                if (count > 0) { minOffset = minOf(minOffset, offset); last = offset }
                c += count
                if (c > sampleCount) throw UnsupportedTables("в ctts больше сэмплов, чем в stsz")
            }
            if (c != sampleCount.toLong()) throw UnsupportedTables("в ctts другое число сэмплов")
            minCts = minOffset
            lastCts = last
        } else {
            cttsCount = 0
            cttsSample = IntArray(0)
            minCts = 0
            lastCts = 0
        }

        // stss: номера ключевых сэмплов строго по возрастанию.
        val sc = if (stss != null) r.u32(stss.dataPos + 4).toIntChecked("число ключевых") else 0
        if (stss != null && stss.dataSize < 8 + 4L * sc) throw UnsupportedTables("stss короче заявленного")
        allSync = stss == null || sc == 0
        syncCount = if (allSync) 0 else sc
        var prev = 0L
        scan(src, stssEntries, syncCount, 4) { b, o, _ ->
            val v = be32(b, o).toLong() and 0xFFFFFFFFL
            if (v <= prev) throw UnsupportedTables("stss не по возрастанию")
            prev = v
        }
    }

    /** Наибольший сэмпл среди [from, to) — для дорожки, у которой правки оставили не все сэмплы. */
    fun maxSize(from: Int, to: Int): Int {
        if (fixedSize != 0) return fixedSize
        var max = 0
        scan(src, stszEntries + 4L * from, to - from, 4) { b, o, _ -> max = maxOf(max, be32(b, o)) }
        return max
    }

    /** Чанков в записи stsc, начинающейся с чанка [from] (с 0), если следующая начинается с [to]. */
    private fun runChunks(from: Long, to: Long): Long =
        (to.coerceAtMost(chunkCount.toLong()) - from.coerceAtMost(chunkCount.toLong())).coerceAtLeast(0)

    private fun Long.toIntChecked(what: String): Int {
        if (this > Int.MAX_VALUE) throw UnsupportedTables("$what больше 2^31")
        return toInt()
    }

    // ------------------------------------------------------------------
    //  Отдельные значения по номеру сэмпла (для поиска при перемотке)
    // ------------------------------------------------------------------

    /** Свои окна чтения: курсоры и поиск при перемотке работают из разных потоков. */
    inner class Reader {
        private val r = CachedReader(src, 4096)
        private val sizes = CachedReader(src, 4096)
        private val offsets = CachedReader(src, 4096)
        private val sync = CachedReader(src, 4096)

        fun size(i: Int): Int = if (fixedSize != 0) fixedSize else sizes.u32(stszEntries + 4L * i).toInt()

        fun chunkOffset(c: Int): Long =
            if (longOffsets) offsets.u64(chunkEntries + 8L * c) else offsets.u32(chunkEntries + 4L * c)

        /** Время декодирования сэмпла [i] (единицы дорожки). */
        fun decodeTime(i: Int): Long {
            var k = floorIndex(sttsSample, i)
            var e = k * STEP
            var first = sttsSample[k].toLong()
            var time = sttsTime[k]
            while (true) {
                val count = r.u32(sttsEntries + 8L * e)
                val delta = r.u32(sttsEntries + 8L * e + 4).toInt()
                if (i < first + count) return time + (i - first) * delta
                first += count
                time += count * delta
                e++
            }
        }

        /** Смещение ctts сэмпла [i] (0, если ctts нет). */
        fun cts(i: Int): Int {
            if (!hasCtts) return 0
            val k = floorIndex(cttsSample, i)
            var e = k * STEP
            var first = cttsSample[k].toLong()
            while (true) {
                val count = r.u32(cttsEntries + 8L * e)
                if (i < first + count) return r.u32(cttsEntries + 8L * e + 4).toInt()
                first += count
                e++
            }
        }

        /** Время показа сэмпла [i] до правок: декодирование + ctts. */
        fun pts(i: Int): Long = decodeTime(i) + cts(i)

        fun isSync(i: Int): Boolean = allSync || syncIndexAtOrAfter(i).let { it < syncCount && syncSample(it) == i }

        /** Номер (с 0) сэмпла [j]-й записи stss. */
        fun syncSample(j: Int): Int = (sync.u32(stssEntries + 4L * j) - 1).toInt()

        /** Первая запись stss с сэмплом не раньше [i]. */
        fun syncIndexAtOrAfter(i: Int): Int {
            var lo = 0
            var hi = syncCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (syncSample(mid) < i) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Последний ключевой сэмпл не позже [i] или -1. */
        fun syncAtOrBefore(i: Int): Int {
            if (i < 0) return -1
            if (allSync) return i
            val j = syncIndexAtOrAfter(i + 1) - 1
            return if (j >= 0) syncSample(j) else -1
        }

        /** Первый ключевой сэмпл не раньше [i] или -1. */
        fun syncAtOrAfter(i: Int): Int {
            if (i >= sampleCount) return -1
            if (allSync) return i
            val j = syncIndexAtOrAfter(i)
            return if (j < syncCount) syncSample(j).takeIf { it < sampleCount } ?: -1 else -1
        }

        /** Смещение сэмпла [i] в файле. */
        fun offset(i: Int): Long {
            val (chunk, firstOfChunk) = chunkOf(i)
            var off = chunkOffset(chunk)
            if (fixedSize != 0) return off + fixedSize.toLong() * (i - firstOfChunk)
            for (s in firstOfChunk until i) off += size(s)
            return off
        }

        /** Чанк сэмпла [i] и номер первого сэмпла этого чанка. */
        fun chunkOf(i: Int): Pair<Int, Int> {
            val k = floorIndex(stscSample, i.toLong())
            var e = k * STEP
            var first = stscSample[k]
            while (true) {
                val chunk0 = r.u32(stscEntries + 12L * e) - 1
                val spc = r.u32(stscEntries + 12L * e + 4)
                val next = if (e + 1 < stscCount) r.u32(stscEntries + 12L * (e + 1)) - 1 else chunkCount.toLong()
                val chunks = (next.coerceAtMost(chunkCount.toLong()) - chunk0.coerceAtMost(chunkCount.toLong())).coerceAtLeast(0)
                val inRun = chunks * spc
                if (i < first + inRun) {
                    val c = (i - first) / spc
                    return (chunk0 + c).toInt() to (first + c * spc).toInt()
                }
                first += inRun
                e++
            }
        }
    }

    // ------------------------------------------------------------------
    //  Курсор: следующий сэмпл подряд — без поиска, окна читаются по ходу
    // ------------------------------------------------------------------

    inner class Cursor {
        private val rd = Reader()
        private val r = CachedReader(src, 4096)

        var index = -1
            private set
        var offset = 0L
            private set
        var size = 0
            private set
        var decodeTime = 0L
            private set
        var cts = 0
            private set
        var sync = false
            private set

        // Чанк
        private var chunk = 0
        private var leftInChunk = 0
        // stsc
        private var stscEntry = 0
        private var stscSpc = 0L
        private var stscNextChunk = 0L
        // stts
        private var sttsEntry = 0
        private var sttsLeft = 0L
        private var sttsDelta = 0
        // ctts
        private var cttsEntry = 0
        private var cttsLeft = 0L
        // stss
        private var nextSyncIdx = 0
        private var nextSync = -1

        val pts: Long get() = decodeTime + cts

        /** Встать на сэмпл [i]: все подкурсоры вычисляются из разреженных указателей. */
        fun positionAt(i: Int) {
            index = i
            // stts
            var k = floorIndex(sttsSample, i)
            var e = k * STEP
            var first = sttsSample[k].toLong()
            var time = sttsTime[k]
            while (true) {
                val count = r.u32(sttsEntries + 8L * e)
                val delta = r.u32(sttsEntries + 8L * e + 4).toInt()
                if (i < first + count) {
                    sttsEntry = e; sttsDelta = delta; sttsLeft = first + count - i
                    decodeTime = time + (i - first) * delta
                    break
                }
                first += count; time += count * delta; e++
            }
            // ctts
            if (hasCtts) {
                k = floorIndex(cttsSample, i)
                e = k * STEP
                first = cttsSample[k].toLong()
                while (true) {
                    val count = r.u32(cttsEntries + 8L * e)
                    if (i < first + count) {
                        cttsEntry = e; cttsLeft = first + count - i
                        cts = r.u32(cttsEntries + 8L * e + 4).toInt()
                        break
                    }
                    first += count; e++
                }
            } else cts = 0
            // stsc + чанк
            k = floorIndex(stscSample, i.toLong())
            e = k * STEP
            var firstSample = stscSample[k]
            while (true) {
                val chunk0 = r.u32(stscEntries + 12L * e) - 1
                val spc = r.u32(stscEntries + 12L * e + 4)
                val next = if (e + 1 < stscCount) r.u32(stscEntries + 12L * (e + 1)) - 1 else chunkCount.toLong()
                val chunks = (next.coerceAtMost(chunkCount.toLong()) - chunk0.coerceAtMost(chunkCount.toLong())).coerceAtLeast(0)
                val inRun = chunks * spc
                if (i < firstSample + inRun) {
                    val c = (i - firstSample) / spc
                    stscEntry = e; stscSpc = spc; stscNextChunk = next
                    chunk = (chunk0 + c).toInt()
                    val firstOfChunk = (firstSample + c * spc).toInt()
                    leftInChunk = (spc - (i - firstOfChunk)).toInt()
                    var off = rd.chunkOffset(chunk)
                    if (fixedSize != 0) off += fixedSize.toLong() * (i - firstOfChunk)
                    else for (s in firstOfChunk until i) off += rd.size(s)
                    offset = off
                    break
                }
                firstSample += inRun; e++
            }
            size = rd.size(i)
            // stss
            if (allSync) sync = true
            else {
                nextSyncIdx = rd.syncIndexAtOrAfter(i)
                nextSync = if (nextSyncIdx < syncCount) rd.syncSample(nextSyncIdx) else -1
                sync = nextSync == i
            }
        }

        /** Следующий сэмпл по порядку файла дорожки. */
        fun advance() {
            val prevSize = size
            index++
            if (index >= sampleCount) return
            // stts
            decodeTime += sttsDelta
            if (--sttsLeft == 0L && sttsEntry + 1 < sttsCount) {
                sttsEntry++
                sttsLeft = r.u32(sttsEntries + 8L * sttsEntry)
                sttsDelta = r.u32(sttsEntries + 8L * sttsEntry + 4).toInt()
            }
            // ctts (записи с нулём сэмплов пропускаются, как в Media3)
            if (hasCtts && --cttsLeft == 0L) {
                while (cttsEntry + 1 < cttsCount) {
                    cttsEntry++
                    cttsLeft = r.u32(cttsEntries + 8L * cttsEntry)
                    if (cttsLeft > 0) { cts = r.u32(cttsEntries + 8L * cttsEntry + 4).toInt(); break }
                }
            }
            // чанк
            if (--leftInChunk > 0) {
                offset += prevSize
            } else {
                while (true) {
                    chunk++
                    while (chunk >= stscNextChunk && stscEntry + 1 < stscCount) {
                        stscEntry++
                        stscSpc = r.u32(stscEntries + 12L * stscEntry + 4)
                        stscNextChunk = if (stscEntry + 1 < stscCount) r.u32(stscEntries + 12L * (stscEntry + 1)) - 1 else chunkCount.toLong()
                    }
                    if (stscSpc > 0) break
                }
                leftInChunk = stscSpc.toInt()
                offset = rd.chunkOffset(chunk)
            }
            size = rd.size(index)
            // stss
            if (!allSync) {
                if (nextSync in 0 until index) {
                    nextSyncIdx++
                    nextSync = if (nextSyncIdx < syncCount) rd.syncSample(nextSyncIdx) else -1
                }
                sync = nextSync == index
            }
        }
    }

    companion object {
        /** Шаг разреженных указателей: столько записей таблицы на один указатель. */
        const val STEP = 256

        private fun be32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)

        /** Пройти [count] записей по [entry] байт с [pos] блоками по 64 КБ: f(блок, смещение записи, номер записи). */
        private inline fun scan(src: ByteSource, pos: Long, count: Int, entry: Int, f: (ByteArray, Int, Int) -> Unit) {
            val per = (64 * 1024) / entry
            val buf = ByteArray(per * entry)
            var done = 0
            while (done < count) {
                val n = minOf(per, count - done)
                val got = src.read(pos + done.toLong() * entry, buf, 0, n * entry)
                if (got < n * entry) throw UnsupportedTables("таблица обрывается раньше конца")
                for (k in 0 until n) f(buf, k * entry, done + k)
                done += n
            }
        }

        private fun floorIndex(a: IntArray, v: Int): Int {
            var lo = 0
            var hi = a.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (a[mid] <= v) lo = mid else hi = mid - 1
            }
            return lo
        }

        private fun floorIndex(a: LongArray, v: Long): Int {
            var lo = 0
            var hi = a.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (a[mid] <= v) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}
