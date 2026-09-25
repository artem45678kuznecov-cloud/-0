package com.nox.offline.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.downloader.catalog.CatalogBuilder
import com.nox.offline.downloader.catalog.CatalogJson
import com.nox.offline.downloader.catalog.FixedCaps
import com.nox.offline.downloader.catalog.FormatCatalog
import com.nox.offline.ui.downloads.LanguageChips
import com.nox.offline.ui.downloads.VariantList
import com.nox.offline.ui.downloads.VideoDetailsCard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Список вариантов качества на настоящем Compose: строки из каталога
 * (записанный ответ YouTube с 1440p), выбор, «Подробнее», недоступные
 * варианты с причиной, никаких фиксированных кнопок 360/480/720/MAX.
 */
@RunWith(AndroidJUnit4::class)
class VariantListUiTest {
    @get:Rule val rule = createComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()

    private fun catalog(name: String, sdk: Int): FormatCatalog {
        val raw = instr.context.assets.open("catalog/$name.json").use { it.readBytes().toString(Charsets.UTF_8) }
        return CatalogBuilder.build(CatalogJson.parseAnalysis("https://youtu.be/x", JSONObject(raw)), FixedCaps(sdk))
    }

    private var selected by mutableStateOf<String?>(null)
    private var expanded by mutableStateOf<String?>(null)

    private fun show(c: FormatCatalog) {
        selected = c.preselect(1080)?.key
        rule.setContent {
            Column(Modifier.width(400.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                VideoDetailsCard(c.details)
                LanguageChips(c, {})
                VariantList(c, selected, expanded, { selected = it }, { expanded = if (expanded == it) null else it })
            }
        }
    }

    private fun shot(name: String) {
        runCatching {
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val fds = instr.uiAutomation.executeShellCommandRw("sh -c 'mkdir -p /data/local/tmp/nox-shots && cat > /data/local/tmp/nox-shots/$name.png'")
            ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(bytes) }
            ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes() }
        }
    }

    @Test fun realVariantsAreListedAndSelectable() {
        val c = catalog("yt_bbb", 34)
        show(c)
        for (v in c.main) assertTrue(v.title, rule.onAllNodesWithText(v.title).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("MAX").fetchSemanticsNodes().isEmpty())
        // Предварительный выбор виден и его можно сменить на 1440p60.
        assertEquals("v:299+a:140", selected)
        rule.onNodeWithText("1440p60").performClick()
        rule.waitForIdle()
        assertEquals("v:308+a:251", selected)
        shot("variant-list-1440")
        // «Подробнее» у 1440p60 раскрывает AV1-вариант той же ступени.
        val group = c.main.first { it.tierHeight == 1440 }.groupKey
        rule.onAllNodesWithText("Ещё", substring = true)[1].performClick()
        rule.waitForIdle()
        assertEquals(group, expanded)
        assertTrue(rule.onAllNodes(hasText("AV1", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun unsupportedVariantShowsReasonAndCannotBeChosen() {
        val c = catalog("yt_bbb", 28)
        show(c)
        val before = selected
        rule.onNodeWithText("1440p60").performClick()
        rule.waitForIdle()
        assertEquals(before, selected)
        assertTrue(rule.onAllNodes(hasText("Android 10", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun severalAudioLanguagesAreOffered() {
        val c = catalog("yt_multilang", 34)
        show(c)
        assertTrue(rule.onAllNodes(hasText("оригинал", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText("1080p60")).fetchSemanticsNodes().isNotEmpty())
    }
}
