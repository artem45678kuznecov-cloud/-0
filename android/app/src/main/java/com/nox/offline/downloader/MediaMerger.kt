package com.nox.offline.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/**
 * Склейка отдельных видео- и аудиодорожки в один MP4 средствами Android
 * (MediaExtractor + MediaMuxer). Без перекодирования: сэмплы копируются
 * как есть, поэтому это быстро и не теряет качество.
 *
 * Ограничение честное и заранее учтённое в resolver.py: MediaMuxer пишет в
 * MP4 только H.264/H.265 и AAC. Другие кодеки сюда не попадают.
 *
 * Работа идёт кусками по одному сэмплу; между сэмплами проверяется отмена
 * корутины, поэтому пауза перед обновлением или отмена задания прерывают
 * склейку быстро, а исходные дорожки остаются нетронутыми.
 */
object MediaMerger {
    data class Probe(val hasVideo: Boolean, val hasAudio: Boolean, val durationUs: Long, val videoMime: String, val audioMime: String)

    class MergeException(message: String) : Exception(message)

    suspend fun merge(video: File, audio: File, output: File, onProgress: (Float) -> Unit = {}) {
        output.delete()
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(video.absolutePath)
            aEx.setDataSource(audio.absolutePath)
            val vTrack = findTrack(vEx, "video/") ?: throw MergeException("в видеодорожке нет видео")
            val aTrack = findTrack(aEx, "audio/") ?: throw MergeException("в аудиодорожке нет звука")
            vEx.selectTrack(vTrack)
            aEx.selectTrack(aTrack)
            val vFormat = vEx.getTrackFormat(vTrack)
            val aFormat = aEx.getTrackFormat(aTrack)
            val m = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val outV = try { m.addTrack(vFormat) } catch (e: Exception) {
                throw MergeException("видео-кодек ${vFormat.getString(MediaFormat.KEY_MIME)} не подходит для MP4")
            }
            val outA = try { m.addTrack(aFormat) } catch (e: Exception) {
                throw MergeException("аудио-кодек ${aFormat.getString(MediaFormat.KEY_MIME)} не подходит для MP4")
            }
            // Поворот кадра (вертикальные ролики) переносится как есть.
            if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                runCatching { m.setOrientationHint(vFormat.getInteger(MediaFormat.KEY_ROTATION)) }
            }
            m.start()
            started = true
            val duration = maxOf(durationOf(vFormat), durationOf(aFormat)).coerceAtLeast(1)
            val bufSize = maxOf(maxInput(vFormat), maxInput(aFormat), 2 * 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(bufSize)
            val info = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            var lastReport = 0L
            // Чередуем по времени, чтобы в файле дорожки шли вперемешку.
            while (!vDone || !aDone) {
                currentCoroutineContext().ensureActive()
                val takeVideo = when {
                    vDone -> false
                    aDone -> true
                    else -> vEx.sampleTime <= aEx.sampleTime
                }
                val ex = if (takeVideo) vEx else aEx
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) {
                    if (takeVideo) vDone = true else aDone = true
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = ex.sampleTime
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(if (takeVideo) outV else outA, buffer, info)
                ex.advance()
                if (info.presentationTimeUs - lastReport > 2_000_000) {
                    lastReport = info.presentationTimeUs
                    onProgress((info.presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f))
                }
            }
            // stop() дописывает индекс MP4: его ошибка — это ошибка склейки.
            started = false
            m.stop()
            onProgress(1f)
        } catch (t: Throwable) {
            output.delete()
            throw t
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            vEx.release()
            aEx.release()
        }
    }

    /** Проверка результата: есть ли видео, звук и длительность. */
    fun probe(file: File): Probe {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            var v = false; var a = false; var dur = 0L; var vm = ""; var am = ""
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { v = true; vm = mime }
                if (mime.startsWith("audio/")) { a = true; am = mime }
                dur = maxOf(dur, durationOf(f))
            }
            return Probe(v, a, dur, vm, am)
        } finally {
            ex.release()
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return null
    }

    private fun durationOf(f: MediaFormat): Long =
        if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L

    private fun maxInput(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
}
