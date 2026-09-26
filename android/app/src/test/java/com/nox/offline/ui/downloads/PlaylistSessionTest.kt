package com.nox.offline.ui.downloads

import com.nox.offline.downloader.catalog.AnalyzeResult
import com.nox.offline.downloader.catalog.CatalogJson
import com.nox.offline.downloader.catalog.FixedCaps
import com.nox.offline.downloader.catalog.PlaylistEntry
import com.nox.offline.downloader.catalog.PlaylistPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Плейлист: порядок источника, недоступные с причиной, без дублей, страницы, выбор, оценка объёма. */
class PlaylistSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyses = AtomicInteger()

    private fun entry(i: Int, reason: String = "") = PlaylistEntry(i, "v$i", "Youtube", if (reason.isBlank()) "https://youtu.be/bbb?i=$i" else "",
        "Серия $i", "Канал", 1440, "", reason)

    private fun page(start: Int, count: Int, total: Int) = PlaylistPage("pl", "Сериал", "Канал", "https://youtube.com/playlist?list=pl",
        "Youtube", total, start, (start until minOf(start + count, total + 1)).map { if (it == 3) entry(it, "Закрытое видео") else entry(it) },
        hasMore = start + count <= total)

    private val analyze: (String) -> AnalyzeResult = {
        analyses.incrementAndGet()
        AnalyzeResult.Ok(CatalogJson.parseAnalysis(it, JSONObject(javaClass.getResource("/catalog/yt_bbb.json")!!.readText())))
    }

    private fun session(have: Set<String> = setOf("v2")) = PlaylistSession(scope, { _, s, c -> Result.success(page(s, c, 120)) }, analyze,
        { e -> if (e.videoId in have) "уже в медиатеке" else null }, FixedCaps(34), { PickPrefs(1080, false) }, Semaphore(2))

    private fun <T> await(what: () -> T?): T {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) { what()?.let { return it }; Thread.sleep(10) }
        throw AssertionError("не дождались")
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `first page in source order, private with reason, present not preselected`() {
        val s = session()
        s.open("u")
        val st = await { s.state.value.takeIf { it.items.isNotEmpty() } }
        assertEquals((1..50).toList(), st.items.map { it.entry.index })
        assertTrue(st.hasMore)
        assertFalse(st.items.first { it.entry.index == 3 }.available)
        assertFalse(st.items.first { it.entry.index == 2 }.selected)
        assertEquals("уже в медиатеке", st.items.first { it.entry.index == 2 }.presence)
        assertEquals(48, st.selected.size)
        assertEquals(0, analyses.get())   // качества не смотрятся до явной просьбы
    }

    @Test fun `load more appends without duplicates`() {
        val s = session()
        s.open("u")
        await { s.state.value.takeIf { it.items.isNotEmpty() } }
        s.loadMore()
        val st = await { s.state.value.takeIf { it.items.size == 100 } }
        assertEquals(st.items.map { it.entry.index }.distinct().size, 100)
    }

    @Test fun `range and missing selection`() {
        val s = session()
        s.open("u")
        await { s.state.value.takeIf { it.items.isNotEmpty() } }
        s.selectRange(5, 1)
        assertEquals(listOf(1, 2, 4, 5), s.state.value.selected.map { it.entry.index })   // 3 закрыто
        s.selectMissing()
        assertTrue(s.state.value.selected.none { it.entry.index == 2 || it.entry.index == 3 })
        s.selectAll(false)
        assertTrue(s.state.value.selected.isEmpty())
    }

    @Test fun `analysis only for selected, estimate counts sizes`() {
        val s = session()
        s.open("u")
        await { s.state.value.takeIf { it.items.isNotEmpty() } }
        s.selectRange(1, 4)
        s.analyzeSelected()
        val st = await { s.state.value.takeIf { it.analyzedSelected.size == 3 } }
        assertEquals(3, analyses.get())
        val (sum, unknown) = st.estimate()
        assertTrue(sum > 0)
        assertEquals(0, unknown)
    }
}
