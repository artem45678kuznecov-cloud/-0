package com.nox.offline.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nox.offline.ui.theme.LocalGlassConfig

enum class Tab { HOME, DOWNLOADS, PLAYER, SETTINGS }

/**
 * Страницы внутри вкладок. Вложенные страницы (коллекция, загрузчик,
 * разделы настроек) открываются поверх своей вкладки — пятой вкладки нет,
 * «Назад» возвращает на шаг вверх внутри той же вкладки.
 */
sealed interface Page {
    // Главная
    data object Home : Page
    data class Collection(val id: Long) : Page
    /** Список коллекций: all / category / pinned / album. */
    data class Collections(val kind: String) : Page
    data object AllVideos : Page

    // Загрузки
    data object Queue : Page
    data object Downloader : Page
    data class Playlist(val url: String) : Page
    data object Cleanup : Page

    // Плеер
    data object Player : Page

    // Настройки
    data object Settings : Page
    data object Appearance : Page
    data object DownloadSettings : Page
    data object Backup : Page
    data object About : Page
    data object Licenses : Page
    data object Privacy : Page
    data object Diagnostics : Page
}

/**
 * Стек страниц для каждой вкладки. Живёт в ViewModel: поворот экрана и
 * смена темы не сбрасывают, где находится пользователь.
 */
class Nav {
    var tab by mutableStateOf(Tab.HOME)
        private set

    private val stacks = mutableMapOf(
        Tab.HOME to listOf<Page>(Page.Home),
        Tab.DOWNLOADS to listOf<Page>(Page.Queue),
        Tab.PLAYER to listOf<Page>(Page.Player),
        Tab.SETTINGS to listOf<Page>(Page.Settings),
    )
    private var version by mutableStateOf(0)

    val current: Page get() { version; return stacks.getValue(tab).last() }
    val canGoBack: Boolean get() { version; return stacks.getValue(tab).size > 1 || tab != Tab.HOME }
    /** Глубина стека текущей вкладки (для направления анимации). */
    val depth: Int get() { version; return stacks.getValue(tab).size }

    /**
     * Переход на вкладку из кода (уведомление, «Смотреть», шапка экрана). Если
     * она уже выбрана — к её началу. Нижняя панель на одиночное нажатие уже
     * выбранной вкладки сюда не зовёт: там «в начало» — только двойное нажатие
     * ([reselect]).
     */
    fun select(t: Tab) {
        if (t == tab) stacks[t] = stacks.getValue(t).take(1) else tab = t
        version++
    }

    private val topRequests = mutableMapOf<Tab, Int>()

    /**
     * «В начало раздела» (двойное нажатие на вкладку): вложенные страницы
     * только этой вкладки закрываются, а если уже открыто её начало — оно
     * прокручивается вверх ([topRequest]). Стеки других вкладок, плеер,
     * загрузки и настройки при этом не трогаются.
     */
    fun reselect(t: Tab) {
        tab = t
        val s = stacks.getValue(t)
        if (s.size > 1) stacks[t] = s.take(1) else topRequests[t] = (topRequests[t] ?: 0) + 1
        version++
    }

    /** Счётчик просьб «прокрутить начало вкладки вверх»: начальный экран следит за его изменением. */
    fun topRequest(t: Tab): Int { version; return topRequests[t] ?: 0 }

    fun open(page: Page, inTab: Tab = tab) {
        tab = inTab
        val s = stacks.getValue(inTab)
        if (s.last() != page) stacks[inTab] = s + page
        version++
    }

    /** Заменить верх стека (например, после создания коллекции — сразу её страница). */
    fun replace(page: Page) {
        stacks[tab] = stacks.getValue(tab).dropLast(1) + page
        version++
    }

    fun back(): Boolean {
        val s = stacks.getValue(tab)
        return when {
            s.size > 1 -> { stacks[tab] = s.dropLast(1); version++; true }
            tab != Tab.HOME -> { tab = Tab.HOME; version++; true }
            else -> false
        }
    }

    /** Вернуться к корню вкладки и выбрать её. */
    fun root(t: Tab) {
        stacks[t] = stacks.getValue(t).take(1)
        tab = t
        version++
    }
}

/**
 * Начальный экран вкладки прокручивается вверх по двойному нажатию на неё.
 * Срабатывает только на новую просьбу: при возврате на экран не дёргает.
 */
@Composable
fun TopOnReselect(nav: Nav, tab: Tab, list: LazyListState) {
    val request = nav.topRequest(tab)
    val seen = remember { intArrayOf(request) }
    val reduce = LocalGlassConfig.current.reduceMotion
    LaunchedEffect(request) {
        if (request == seen[0]) return@LaunchedEffect
        seen[0] = request
        if (reduce) list.scrollToItem(0) else list.animateScrollToItem(0)
    }
}
