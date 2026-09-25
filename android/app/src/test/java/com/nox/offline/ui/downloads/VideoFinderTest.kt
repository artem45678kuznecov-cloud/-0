package com.nox.offline.ui.downloads

import com.nox.offline.downloader.catalog.AnalyzeResult
import com.nox.offline.downloader.catalog.CatalogJson
import com.nox.offline.downloader.catalog.FixedCaps
import com.nox.offline.downloader.catalog.ResolveError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Поиск видео и выбор качества: один анализ на поиск, переключение
 * качества и языка без нового разбора, устаревшие ответы не побеждают.
 */
class VideoFinderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = AtomicInteger()
    private val callsByUrl = ConcurrentHashMap<String, AtomicInteger>()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()
    private val failures = ConcurrentHashMap<String, ResolveError>()

    private fun analysisJson(name: String) = javaClass.getResource("/catalog/$name.json")!!.readText()

    private val fixtures = mapOf(
        "https://youtu.be/bbb" to "yt_bbb",
        "https://youtu.be/dub" to "yt_multilang",
        "https://vk.com/video-1_1" to "vk",
    )

    private val analyze: (String) -> AnalyzeResult = { url ->
        calls.incrementAndGet()
        callsByUrl.getOrPut(url) { AtomicInteger() }.incrementAndGet()
        gates[url]?.await(10, TimeUnit.SECONDS)
        val fail = failures[url]
        if (fail != null) AnalyzeResult.Failed(fail)
        else AnalyzeResult.Ok(CatalogJson.parseAnalysis(url, JSONObject(analysisJson(fixtures.getValue(url)))))
    }

    private fun finder(height: Int = 1080) = VideoFinder(scope, analyze, FixedCaps(34), { PickPrefs(height, false) })

    private fun <T> await(timeoutMs: Long = 5000, what: () -> T?): T {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            what()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("не дождались состояния")
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `one analysis per search, preselected but not downloaded`() {
        val f = finder(1080)
        f.search("https://youtu.be/bbb")
        val ready = await { f.state.value as? FinderState.Ready }
        assertEquals(1, calls.get())
        assertEquals("1080p60", ready.selected?.title)
        // VideoFinder не умеет ставить загрузку: до «Скачать» ничего не передаётся.
    }

    @Test fun `switching quality and details uses the cached catalog`() {
        val f = finder()
        f.search("https://youtu.be/bbb")
        val ready = await { f.state.value as? FinderState.Ready }
        val p1440 = ready.catalog.main.first { it.tierHeight == 1440 }
        f.select(p1440.key)
        f.toggleDetails(p1440.groupKey)
        val av1 = ready.catalog.alternatives(p1440).first { it.video.vcodec == "av1" }
        f.select(av1.key)
        val s = f.state.value as FinderState.Ready
        assertEquals(av1.key, s.selectedKey)
        assertEquals(p1440.groupKey, s.expanded)
        assertEquals(1, calls.get())
    }

    @Test fun `language switch rebuilds without network and keeps the step`() {
        val f = finder()
        f.search("https://youtu.be/dub")
        val ready = await { f.state.value as? FinderState.Ready }
        val step = ready.selected!!.groupKey
        f.setLanguage("ru")
        val s = f.state.value as FinderState.Ready
        assertEquals("ru", s.catalog.language)
        assertEquals(step, s.selected!!.groupKey)
        assertEquals("ru", s.selected!!.audio!!.language)
        assertEquals(1, calls.get())
    }

    @Test fun `unsupported variant cannot be selected`() {
        val f = VideoFinder(scope, analyze, FixedCaps(28), { PickPrefs(1080, false) })
        f.search("https://youtu.be/bbb")
        val ready = await { f.state.value as? FinderState.Ready }
        val p1440 = ready.catalog.main.first { it.tierHeight == 1440 }
        f.select(p1440.key)
        assertEquals(ready.selectedKey, (f.state.value as FinderState.Ready).selectedKey)
    }

    @Test fun `late result of the previous search never overwrites the new card`() {
        val slow = CountDownLatch(1)
        gates["https://youtu.be/bbb"] = slow
        val f = finder()
        f.search("https://youtu.be/bbb")
        f.onUrlChanged("https://vk.com/video-1_1")
        assertTrue(f.state.value is FinderState.Idle)             // старые варианты сразу недействительны
        f.search("https://vk.com/video-1_1")
        val vk = await { (f.state.value as? FinderState.Ready)?.takeIf { it.url.contains("vk.com") } }
        slow.countDown()                                          // ответ YouTube приходит позже
        Thread.sleep(300)
        val s = f.state.value as FinderState.Ready
        assertEquals(vk.url, s.url)
        assertEquals("VK", s.catalog.details.extractor)
    }

    @Test fun `cancel ignores the running analysis`() {
        val slow = CountDownLatch(1)
        gates["https://youtu.be/bbb"] = slow
        val f = finder()
        f.search("https://youtu.be/bbb")
        await { callsByUrl["https://youtu.be/bbb"]?.get()?.takeIf { it > 0 } }
        f.cancel()
        slow.countDown()
        Thread.sleep(300)
        assertTrue(f.state.value is FinderState.Idle)
    }

    @Test fun `same url typed again does not invalidate the card`() {
        val f = finder()
        f.search("https://youtu.be/bbb")
        await { f.state.value as? FinderState.Ready }
        f.onUrlChanged(" https://youtu.be/bbb ")
        assertTrue(f.state.value is FinderState.Ready)
    }

    @Test fun `error is shown and retry analyses again`() {
        failures["https://youtu.be/bbb"] = ResolveError("network", "Нет сети")
        val f = finder()
        f.search("https://youtu.be/bbb")
        val failed = await { f.state.value as? FinderState.Failed }
        assertEquals("network", failed.error.kind)
        failures.clear()
        f.retry()
        await { f.state.value as? FinderState.Ready }
        assertEquals(2, calls.get())
    }

    @Test fun `batch keeps a separate catalog and choice per link`() {
        failures["https://youtu.be/dub"] = ResolveError("private", "Закрытое видео")
        val b = BatchFinder(scope, analyze, FixedCaps(34), { PickPrefs(1440, false) })
        b.start(listOf("https://youtu.be/bbb", "https://vk.com/video-1_1", "https://youtu.be/dub"))
        await { b.entries.value.takeIf { list -> list.none { it.state is FinderState.Searching } } }
        val list = b.entries.value
        val yt = list[0].state as FinderState.Ready
        val vk = list[1].state as FinderState.Ready
        assertEquals("1440p60", yt.selected?.title)
        assertEquals("1440p", vk.selected?.title)                 // у VK своё 1440p — готовый файл
        assertTrue(list[2].state is FinderState.Failed)            // ошибка одной ссылки не мешает остальным
        val yt720 = yt.catalog.main.first { it.tierHeight == 720 }
        b.select(0, yt720.key)
        assertEquals(yt720.key, (b.entries.value[0].state as FinderState.Ready).selectedKey)
        assertEquals(vk.selectedKey, (b.entries.value[1].state as FinderState.Ready).selectedKey)
        b.skip(1)
        assertNull((b.entries.value[1].state as FinderState.Ready).selected)
        assertEquals(3, calls.get())
    }
}
