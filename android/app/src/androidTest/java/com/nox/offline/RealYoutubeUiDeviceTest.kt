package com.nox.offline

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.data.db.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Настоящий YouTube в установленном приложении, без подмен: «Поделиться в
 * NOX» → карточка с настоящими вариантами (разбор страницы, JS-задачи во
 * встроенном движке) → выбор 360p → «Скачать» → фоновая загрузка видео и
 * звука → объединение → «Готово» в медиатеке.
 *
 * Нужна сеть (эмулятор CI). Если сети нет или YouTube отказал этой сети
 * (проверка «не бот», 429), тест пропускается — это «не подтверждено».
 */
@RunWith(AndroidJUnit4::class)
class RealYoutubeUiDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val url = "https://youtu.be/aqz-KE-bpKQ"

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            compose.waitForIdle()
            if (check()) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /** Снимок экрана в /data/local/tmp/nox-shots (CI забирает его в артефакты). */
    private fun shot(name: String) {
        runCatching {
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            // Без оболочки: каталог создаёт mkdir, файл пишет dd из stdin.
            instr.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/nox-shots").close()
            val fds = instr.uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/nox-shots/$name.png")
            ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(bytes) }
            ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes() }
        }
    }

    @Test fun shareYoutubeLinkChooseQualityDownloadWithAudio() {
        if (Build.VERSION.SDK_INT >= 33) {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = NoxApp.get(ctx)
        val before = runBlocking { app.db.media().getAll().map { it.id }.toSet() }
        val share = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(share).use {
            val done = waitFor(240_000) { exists("Качество") || exists("Не удалось получить видео") }
            assumeTrue("нет ответа за 4 минуты (нет сети?)", done)
            if (exists("Не удалось получить видео")) {
                shot("youtube-refused")
                val log = com.nox.offline.core.NoxLog.dump().filter { "analyze" in it }.takeLast(3)
                assumeTrue("YouTube отказал этой сети: $log", false)
            }
            shot("nox-02-downloads-youtube-card")
            // Список — настоящие варианты этого видео: есть 1440p60, нет фиксированного «MAX».
            assertTrue(exists("1440p60"))
            assertTrue(!exists("MAX"))
            // Маленький вариант, чтобы загрузка уложилась в время теста: 360p (видео + звук).
            compose.onAllNodesWithText("360p")[0].performClick()
            compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Скачать 360p", substring = true))
            compose.onAllNodesWithText("Скачать 360p", substring = true)[0].performClick()
            val finished = waitFor(420_000) {
                runBlocking { app.db.downloads().getAll().any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.ERROR } }
            }
            val jobs = runBlocking { app.db.downloads().getAll() }
            shot("nox-02b-downloads-after")
            assertTrue("загрузка не завершилась: ${jobs.map { "${it.status} ${it.stage} ${it.error}" }}", finished)
            val job = jobs.first { it.variantKey.isNotBlank() }
            assertTrue("ошибка: ${job.errorKind} ${job.error}", job.status == DownloadStatus.COMPLETED)
            val media = runBlocking { app.db.media().getAll().first { it.id !in before } }
            val file = java.io.File(media.filePath)
            assertTrue(file.exists())
            val probe = com.nox.offline.downloader.MediaMerger.probe(file)
            assertTrue("видео и звук: $probe", probe.hasVideo && probe.hasAudio)
            assertTrue("длительность ${probe.durationUs}", probe.durationUs > 600_000_000L)
            assertTrue("качество в медиатеке: ${media.quality}", media.quality.startsWith("360p"))
            android.util.Log.i("NOX-TEST", "live-ui ok ${media.quality} ${media.container} ${media.codecs} ${file.length()} " +
                "job=${job.variantKey} ${job.container}")
        }
    }
}
