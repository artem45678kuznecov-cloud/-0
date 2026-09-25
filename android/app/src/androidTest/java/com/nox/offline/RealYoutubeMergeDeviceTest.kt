package com.nox.offline

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.downloader.MediaMerger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Настоящие дорожки YouTube 1440p (VP9 + Opus), скачанные в CI скриптом
 * tools/youtube-live-check.py тем же resolver.analyze/plan, собираются
 * MediaMerger в WebM на Android и проходят проверку перед «Готово».
 * Если YouTube отказал сети CI, файлов нет и тест пропускается (в отчёте —
 * «не подтверждено»), а не считается пройденным.
 */
@RunWith(AndroidJUnit4::class)
class RealYoutubeMergeDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fromShell(name: String): File? {
        val target = File(ctx.cacheDir, "live/$name").apply { parentFile!!.mkdirs(); delete() }
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat /data/local/tmp/nox-live/$name")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> target.outputStream().use { input.copyTo(it, 1 shl 20) } }
        return target.takeIf { it.length() > 0 }
    }

    private fun format(file: File, prefix: String): MediaFormat {
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
        throw AssertionError("нет дорожки $prefix")
    }

    @Test fun realYoutube1440pTracksMergeIntoPlayableWebm() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val metaFile = fromShell("meta.json")
        assumeTrue("живая проверка YouTube не дала файлов (YouTube мог отказать сети CI)", metaFile != null)
        val meta = JSONObject(metaFile!!.readText())
        val video = fromShell("video.webm")!!
        val audio = fromShell("audio.webm")!!
        assertEquals("точный размер видео", meta.getLong("video_bytes"), video.length())
        assertEquals("точный размер звука", meta.getLong("audio_bytes"), audio.length())
        val out = File(ctx.cacheDir, "live/merged.webm").apply { delete() }
        val started = System.currentTimeMillis()
        try {
            MediaMerger.merge(video, audio, out, MediaMerger.CONTAINER_WEBM)
            assertNull(MediaMerger.verify(out, video, audio))
            val v = format(out, "video/")
            val a = format(out, "audio/")
            assertEquals("video/x-vnd.on2.vp9", v.getString(MediaFormat.KEY_MIME))
            assertEquals("audio/opus", a.getString(MediaFormat.KEY_MIME))
            assertEquals(meta.getInt("width"), v.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(meta.getInt("height"), v.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(1440, minOf(v.getInteger(MediaFormat.KEY_WIDTH), v.getInteger(MediaFormat.KEY_HEIGHT)))
            val p = MediaMerger.probe(out)
            val expected = meta.getLong("duration") * 1_000_000L
            assertTrue("длительность ${p.durationUs} ≈ $expected", kotlin.math.abs(p.durationUs - expected) <= 2_000_000L)
            android.util.Log.i("NOX-TEST", "live-merge ok ${meta.getString("video_id")} ${meta.getString("video_format")}+" +
                "${meta.getString("audio_format")} ${v.getInteger(MediaFormat.KEY_WIDTH)}x${v.getInteger(MediaFormat.KEY_HEIGHT)} " +
                "size=${out.length()} dur=${p.durationUs / 1000}ms merge=${System.currentTimeMillis() - started}ms")
        } finally {
            out.delete(); video.delete(); audio.delete()
        }
    }
}
