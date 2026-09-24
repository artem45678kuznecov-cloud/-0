package com.nox.offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.downloader.MediaMerger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Склейка раздельных дорожек настоящим MediaMuxer на устройстве.
 * Исходники — 4 с H.264 без звука и 4 с AAC без видео (assets/media).
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

    @Test fun mergesVideoAndAudioWithoutReencoding() = runBlocking {
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

    @Test fun refusesWhenAudioTrackIsMissing() = runBlocking {
        val video = asset("video_only.mp4")
        val out = File(ctx.cacheDir, "merge-test/bad.mp4").apply { delete() }
        try {
            MediaMerger.merge(video, video, out)
            fail("merge without audio must fail")
        } catch (_: Exception) {
        }
    }
}
