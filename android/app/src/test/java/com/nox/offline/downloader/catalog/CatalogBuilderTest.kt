package com.nox.offline.downloader.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог вариантов на ответах resolver.analyze (test/resources/catalog —
 * их выдал Python-резолвер на записанных ответах yt-dlp; yt_bbb — настоящий
 * ответ YouTube для общедоступного ролика с 1440p и 2160p).
 */
class CatalogBuilderTest {
    private fun analysis(name: String): Analysis {
        val raw = javaClass.getResource("/catalog/$name.json")!!.readText()
        return CatalogJson.parseAnalysis("https://example/$name", JSONObject(raw))
    }

    private fun build(name: String, sdk: Int = 34, lang: String? = null,
                      play: (String, Int, Int, Double) -> Playback = { _, _, _, _ -> Playback.LIKELY }) =
        CatalogBuilder.build(analysis(name), FixedCaps(sdk, play), lang)

    // ---------------- ступени качества ----------------

    @Test fun `tier comes from the real frame, not from ids`() {
        assertEquals(1080, CatalogBuilder.tier(1920, 800))
        assertEquals(1080, CatalogBuilder.tier(1080, 1920))   // вертикальное — не «1920p»
        assertEquals(1440, CatalogBuilder.tier(2560, 1440))
        assertEquals(1440, CatalogBuilder.tier(1440, 1440))
        assertEquals(2160, CatalogBuilder.tier(3840, 1600))
        assertEquals(720, CatalogBuilder.tier(1280, 720))
        assertEquals(144, CatalogBuilder.tier(256, 144))
        assertEquals(720, CatalogBuilder.tier(0, 720))
        assertEquals(0, CatalogBuilder.tier(0, 0))
    }

    // ---------------- YouTube: настоящий набор с 1440p ----------------

    @Test fun `real youtube catalog offers selectable 1440p60 with audio`() {
        val c = build("yt_bbb", sdk = 34)
        val row = c.main.first { it.tierHeight == 1440 }
        assertEquals("1440p60", row.title)
        assertTrue(row.support.ok)
        assertEquals("v:308+a:251", row.key)                   // VP9 + Opus
        assertEquals("webm", row.outputContainer)
        assertEquals("webm", row.outputExt)
        assertEquals("2560×1440", row.resolutionLabel)
        assertEquals(SizeKind.EXACT, row.sizeKind)
        assertEquals(473363704L + 10202210L, row.sizeBytes)
        assertTrue(row.needsMerge)
        assertEquals("VP9 + Opus", row.codecLabel)
        // AV1-вариант той же ступени — в «Подробнее», не отдельной строкой.
        assertTrue(c.alternatives(row).any { it.key == "v:400+a:140" })
    }

    @Test fun `main list is built from the actual formats only`() {
        val c = build("yt_bbb", sdk = 34)
        val tiers = c.main.map { it.title }
        assertEquals(listOf("2160p60", "1440p60", "1080p60", "720p60", "480p", "360p", "240p", "144p"), tiers)
        // Ни одной выдуманной ступени и ни одного «MAX».
        assertFalse(tiers.any { it.contains("MAX") })
        assertEquals(c.main.size, c.main.map { it.groupKey }.distinct().size)
        assertEquals(c.variants.size, c.variants.map { it.key }.distinct().size)
    }

    @Test fun `up to 1080p the compatible h264 is preferred`() {
        val c = build("yt_bbb", sdk = 34)
        val p1080 = c.main.first { it.tierHeight == 1080 }
        assertEquals("v:299+a:140", p1080.key)
        assertEquals("mp4", p1080.outputContainer)
    }

    @Test fun `old android shows 1440p honestly disabled with a reason`() {
        val c = build("yt_bbb", sdk = 28)
        val row = c.main.first { it.tierHeight == 1440 }
        assertFalse(row.support.ok)
        assertTrue((row.support as Support.No).reason.contains("Android 10"))
        assertTrue(c.main.first { it.tierHeight == 1080 }.support.ok)
        assertEquals(1080, c.preselect(1440)?.tierHeight)
    }

    @Test fun `av1 needs android 14 for muxing`() {
        val c33 = build("yt_bbb", sdk = 33)
        val av1 = c33.variants.first { it.key == "v:400+a:140" }
        assertTrue((av1.support as Support.No).reason.contains("Android 14"))
        assertTrue(build("yt_bbb", sdk = 34).variants.first { it.key == "v:400+a:140" }.support.ok)
    }

    @Test fun `segmented duplicates are not offered as downloadable`() {
        val c = build("yt_bbb", sdk = 34)
        val hls = c.variants.filter { it.video.transport == Transport.HLS }
        assertTrue(hls.isNotEmpty())
        assertTrue(hls.none { it.support.ok })
        assertTrue(c.main.none { it.video.transport == Transport.HLS })
    }

    @Test fun `preselect respects preference and playback`() {
        val c = build("yt_bbb", sdk = 34)
        assertEquals(1080, c.preselect(1080)?.tierHeight)
        assertEquals(2160, c.preselect(0)?.tierHeight)
        assertEquals(720, c.preselect(900)?.tierHeight)
        // Телефон не тянет 2160p — «наилучшее» предлагает 1440p.
        val weak = build("yt_bbb", sdk = 34) { _, w, h, _ -> if (maxOf(w, h) > 2560) Playback.UNLIKELY else Playback.LIKELY }
        assertEquals(1440, weak.preselect(0)?.tierHeight)
        val row = weak.main.first { it.tierHeight == 2160 }
        assertTrue(row.support.ok)                                // скачать всё равно можно
        assertTrue(row.playbackNote.isNotBlank())
    }

    // ---------------- 30/60 кадров, вертикальные, размеры ----------------

    @Test fun `30 and 60 fps stay separate rows`() {
        val c = build("yt_multilang")
        val titles = c.main.map { it.title }
        assertTrue(titles.contains("720p60"))
        assertTrue(titles.contains("720p"))
    }

    @Test fun `vertical shorts are labelled by the short side`() {
        val c = build("yt_shorts")
        val top = c.main.first()
        assertEquals("1080p", top.title)
        assertEquals(1080, top.width)
        assertEquals(1920, top.height)
        assertEquals("1080×1920", top.resolutionLabel)
    }

    @Test fun `unknown and approximate sizes are not shown as exact`() {
        val c = build("yt_multilang")
        val p720 = c.variants.first { it.video.id == "302" }
        assertEquals(SizeKind.UNKNOWN, p720.sizeKind)
        val p720s60 = c.variants.first { it.video.id == "298" }
        assertEquals(SizeKind.APPROX, p720s60.sizeKind)
        val p1080 = c.variants.first { it.video.id == "299" }
        assertEquals(SizeKind.EXACT, p1080.sizeKind)
    }

    // ---------------- языки звука ----------------

    @Test fun `original language is chosen, not the loudest dub`() {
        val c = build("yt_multilang")
        assertEquals(2, c.languages.size)
        assertEquals("en", c.language)
        assertTrue(c.languages.first { it.code == "en" }.original)
        val v = c.variants.first { it.video.id == "299" }
        assertEquals("140-0", v.audio?.id)                     // английский оригинал, хотя у русского битрейт выше
        val webm = c.variants.first { it.video.id == "303" }
        assertEquals("251-0", webm.audio?.id)                  // не DRC
    }

    @Test fun `user can switch audio language`() {
        val c = build("yt_multilang", lang = "ru")
        assertEquals("ru", c.language)
        assertEquals("140-1", c.variants.first { it.video.id == "299" }.audio?.id)
        assertEquals("251-1", c.variants.first { it.video.id == "303" }.audio?.id)
    }

    @Test fun `language names are human readable`() {
        assertEquals("Английский", CatalogBuilder.languageName("en"))
        assertEquals("Русский", CatalogBuilder.languageName("ru"))
    }

    // ---------------- VK ----------------

    @Test fun `vk direct 1440 is a ready file without merge`() {
        val c = build("vk")
        val top = c.main.first { it.support.ok }
        assertEquals("1440p", top.title)
        assertEquals("f:url1440", top.key)
        assertFalse(top.needsMerge)
        assertEquals("mp4", top.outputExt)
    }

    @Test fun `hls only step is shown disabled with reason`() {
        val c = build("vk")
        val p1080 = c.main.first { it.tierHeight == 1080 }
        assertFalse(p1080.support.ok)
        assertTrue((p1080.support as Support.No).reason.contains("HLS"))
    }

    // ---------------- синтетика ----------------

    private fun track(id: String, kind: TrackKind, container: String, v: String = "", a: String = "",
                      w: Int = 0, h: Int = 0, fps: Double = 0.0, size: Long = 0, lang: String = "") =
        Track(id = id, kind = kind, ext = if (container == "mp4" && kind == TrackKind.AUDIO) "m4a" else container,
            container = container, vcodec = v, acodec = a, width = w, height = h, fps = fps,
            dynamicRange = if (kind == TrackKind.AUDIO) "" else "SDR", filesize = size, filesizeExact = size > 0, language = lang)

    private fun synthetic(vararg tracks: Track) = Analysis(
        VideoDetails("https://x", "Youtube", "id", "t", "", 7200, "", ""), tracks.toList(),
        0, false, emptyList(), 0, 0, 0, "", "", "")

    @Test fun `mp4 over 4 GB is refused before android 11`() {
        val a = synthetic(
            track("137", TrackKind.VIDEO, "mp4", v = "h264", w = 1920, h = 1080, fps = 30.0, size = 4_500_000_000L),
            track("140", TrackKind.AUDIO, "mp4", a = "aac", size = 100_000_000L),
        )
        val old = CatalogBuilder.build(a, FixedCaps(29))
        assertFalse(old.variants.first().support.ok)
        assertTrue(CatalogBuilder.build(a, FixedCaps(30)).variants.first().support.ok)
    }

    @Test fun `video without any audio in source is offered as silent`() {
        val a = synthetic(track("v1", TrackKind.VIDEO, "mp4", v = "h264", w = 1280, h = 720, fps = 25.0, size = 1000))
        val c = CatalogBuilder.build(a, FixedCaps(34))
        val v = c.main.single()
        assertTrue(v.silent)
        assertTrue(v.support.ok)
        assertFalse(v.needsMerge)
    }

    @Test fun `mismatched containers are not merged by force`() {
        val a = synthetic(
            track("137", TrackKind.VIDEO, "mp4", v = "h264", w = 1920, h = 1080, fps = 30.0, size = 10),
            track("251", TrackKind.AUDIO, "webm", a = "opus", size = 10),
        )
        val v = CatalogBuilder.build(a, FixedCaps(34)).variants.single()
        assertFalse(v.support.ok)
        assertNotNull((v.support as Support.No).reason)
    }

    @Test fun `hdr is its own row with a note`() {
        val hdr = track("337", TrackKind.VIDEO, "webm", v = "vp9", w = 3840, h = 2160, fps = 60.0, size = 10)
            .copy(dynamicRange = "HDR10")
        val a = synthetic(
            hdr,
            track("315", TrackKind.VIDEO, "webm", v = "vp9", w = 3840, h = 2160, fps = 60.0, size = 10),
            track("251", TrackKind.AUDIO, "webm", a = "opus", size = 10),
        )
        val c = CatalogBuilder.build(a, FixedCaps(34))
        assertEquals(2, c.main.size)
        val h = c.main.first { it.hdr }
        assertTrue(h.playbackNote.contains("HDR"))
        assertNull(c.main.firstOrNull { it.hdr && it.key == "v:315+a:251" })
    }
}
