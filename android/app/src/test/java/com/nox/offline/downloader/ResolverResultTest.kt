package com.nox.offline.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverResultTest {
    @Test fun progressiveAnswer() {
        val r = YtDlpResolver.Result.parse(
            """{"ok":true,"title":"Видео","video_id":"v1","format_id":"url480","direct_url":"https://x/v.mp4",
               "ext":"mp4","height":480,"filesize":100,"duration":61,"headers":{"User-Agent":"ua"},"uploader":"Канал"}""",
        )
        assertTrue(r.ok)
        assertFalse(r.isSplit)
        assertEquals("progressive", r.mode)
        assertEquals("ua", r.headers["User-Agent"])
        assertEquals("Канал", r.uploader)
        assertEquals(61L, r.durationSec)
    }

    @Test fun splitAnswer() {
        val r = YtDlpResolver.Result.parse(
            """{"ok":true,"mode":"split","format_id":"dash-1080","direct_url":"https://x/v","height":1080,
               "audio_url":"https://x/a","audio_format_id":"dash-audio","audio_headers":{"Referer":"r"},"audio_filesize":55}""",
        )
        assertTrue(r.isSplit)
        assertEquals("https://x/a", r.audioUrl)
        assertEquals("dash-audio", r.audioFormatId)
        assertEquals("r", r.audioHeaders["Referer"])
        assertEquals(55L, r.audioFilesize)
        assertEquals("mp4", r.ext)
    }

    @Test fun errorAnswer() {
        val r = YtDlpResolver.Result.parse("""{"ok":false,"error":"Видео закрыто","kind":"private"}""")
        assertFalse(r.ok)
        assertEquals("Видео закрыто", r.error)
        assertEquals("private", r.kind)
    }
}
