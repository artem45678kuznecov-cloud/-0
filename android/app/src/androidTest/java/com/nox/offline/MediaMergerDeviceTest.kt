package com.nox.offline

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.downloader.MediaMerger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Склейка раздельных дорожек настоящим MediaMuxer на устройстве.
 * Исходники (assets/media): 4 с H.264 и 4 с AAC; 1 с VP9 2560×1440 с
 * ключевым кадром 2,8 МБ и 1,2 с Opus; 1 с AV1 640×360.
 */
@RunWith(AndroidJUnit4::class)
class MediaMergerDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(name: String): File {
        val out = File(ctx.cacheDir, "merge-test/$name").apply { parentFile!!.mkdirs() }
        testAssets.open("media/$name").use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    @Test fun mergesVideoAndAudioWithoutReencoding(): Unit = runBlocking {
        val video = asset("video_only.mp4")
        val audio = asset("audio_only.m4a")
        val pv = MediaMerger.probe(video)
        val pa = MediaMerger.probe(audio)
        assertTrue(pv.hasVideo); assertFalse(pv.hasAudio)
        assertTrue(pa.hasAudio); assertFalse(pa.hasVideo)

        val out = File(ctx.cacheDir, "merge-test/merged.mp4").apply { delete() }
        var last = 0f
        MediaMerger.merge(video, audio, out) { last = it }
        val p = MediaMerger.probe(out)
        assertTrue("video track", p.hasVideo)
        assertTrue("audio track", p.hasAudio)
        assertEquals("video/avc", p.videoMime)
        assertEquals("audio/mp4a-latm", p.audioMime)
        assertTrue("duration ${p.durationUs}", p.durationUs in 3_500_000L..4_600_000L)
        assertTrue("progress reached end: $last", last >= 0.99f)
        // Без перекодирования размер ≈ сумма исходников.
        val sum = video.length() + audio.length()
        assertTrue("size ${out.length()} vs $sum", out.length() in (sum * 8 / 10)..(sum * 12 / 10))
    }

    @Test fun refusesWhenAudioTrackIsMissing(): Unit = runBlocking {
        val video = asset("video_only.mp4")
        val out = File(ctx.cacheDir, "merge-test/bad.mp4").apply { delete() }
        try {
            MediaMerger.merge(video, video, out)
            fail("merge without audio must fail")
        } catch (_: Exception) {
        }
    }

    private fun trackFormat(file: File, prefix: String): MediaFormat {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return f
            }
        } finally {
            ex.release()
        }
        throw AssertionError("нет дорожки $prefix в ${file.name}")
    }

    private fun head(file: File, n: Int): ByteArray = file.inputStream().use { i -> ByteArray(n).also { i.read(it) } }

    /** 1440p: VP9 + Opus собираются в настоящий WebM без перекодирования, кадр 2560×1440 сохраняется. */
    @Test fun mergesVp9AndOpus1440pIntoWebm(): Unit = runBlocking {
        assumeTrue("Opus в WebM — Android 10+", Build.VERSION.SDK_INT >= 29)
        val video = asset("vp9_1440_video.webm")
        val audio = asset("opus_audio.webm")
        val out = File(ctx.cacheDir, "merge-test/merged.webm").apply { delete() }
        MediaMerger.merge(video, audio, out, MediaMerger.CONTAINER_WEBM)
        // Это WebM (EBML), а не MP4 с другим расширением.
        assertArrayEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), head(out, 4))
        val v = trackFormat(out, "video/")
        val a = trackFormat(out, "audio/")
        assertEquals("video/x-vnd.on2.vp9", v.getString(MediaFormat.KEY_MIME))
        assertEquals("audio/opus", a.getString(MediaFormat.KEY_MIME))
        assertEquals(2560, v.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(1440, v.getInteger(MediaFormat.KEY_HEIGHT))
        assertNull(MediaMerger.verify(out, video, audio))
        val p = MediaMerger.probe(out)
        assertTrue("duration ${p.durationUs}", p.durationUs in 900_000L..1_400_000L)
    }

    /** AV1 + AAC → MP4 доступно с Android 14. */
    @Test fun mergesAv1AndAacIntoMp4(): Unit = runBlocking {
        assumeTrue("AV1 в MP4 — Android 14+", Build.VERSION.SDK_INT >= 34)
        val video = asset("av1_video.mp4")
        val audio = asset("audio_only.m4a")
        val out = File(ctx.cacheDir, "merge-test/av1.mp4").apply { delete() }
        MediaMerger.merge(video, audio, out, MediaMerger.CONTAINER_MP4)
        assertEquals("ftyp", String(head(out, 8), 4, 4, Charsets.US_ASCII))
        assertEquals("video/av01", trackFormat(out, "video/").getString(MediaFormat.KEY_MIME))
        assertEquals("audio/mp4a-latm", trackFormat(out, "audio/").getString(MediaFormat.KEY_MIME))
    }

    /** Проверка перед «Готово» ловит оборванный файл. */
    @Test fun verifyRejectsTruncatedResult(): Unit = runBlocking {
        val video = asset("video_only.mp4")
        val audio = asset("audio_only.m4a")
        val out = File(ctx.cacheDir, "merge-test/ok.mp4").apply { delete() }
        MediaMerger.merge(video, audio, out)
        assertNull(MediaMerger.verify(out, video, audio))
        val cut = File(ctx.cacheDir, "merge-test/cut.mp4")
        cut.writeBytes(out.readBytes().copyOf((out.length() / 2).toInt()))
        assertNotNull(MediaMerger.verify(cut, video, audio))
    }

    /** WebM из NOX открывается плеером Media3, перематывается и знает длительность — без сети. */
    @Test fun mergedWebmPlaysAndSeeksOffline() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val video = asset("vp9_360_video.webm")
        val audio = asset("opus_3s.webm")
        val out = File(ctx.cacheDir, "merge-test/play.webm").apply { delete() }
        runBlocking { MediaMerger.merge(video, audio, out, MediaMerger.CONTAINER_WEBM) }
        val instr = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val seeked = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        var player: ExoPlayer? = null
        var seekRequested = false
        instr.runOnMainSync {
            val pl = ExoPlayer.Builder(ctx).build()
            player = pl
            pl.volume = 0f
            pl.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        if (!seekRequested) {
                            ready.countDown()
                        } else seeked.countDown()
                    }
                }
                override fun onPlayerError(e: PlaybackException) { error.set(e); ready.countDown(); seeked.countDown() }
            })
            pl.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(out)))
            pl.prepare()
        }
        assertTrue("плеер не готов", ready.await(20, TimeUnit.SECONDS))
        error.get()?.let { throw AssertionError("ошибка плеера", it) }
        var duration = 0L
        instr.runOnMainSync {
            duration = player!!.duration
            seekRequested = true
            player!!.seekTo(2_000)
        }
        assertTrue("длительность $duration", duration in 2_500L..3_500L)
        assertTrue("перемотка не завершилась", seeked.await(20, TimeUnit.SECONDS))
        error.get()?.let { throw AssertionError("ошибка плеера", it) }
        var pos = 0L
        instr.runOnMainSync { pos = player!!.currentPosition; player!!.release() }
        assertTrue("позиция $pos", pos in 1_500L..2_600L)
    }
}
