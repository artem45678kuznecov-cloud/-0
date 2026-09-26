package com.nox.offline.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    /** Нажатие на вкладку. Повторное нажатие на выбранную возвращает к её началу. */
    fun select(t: Tab) {
        if (t == tab) stacks[t] = stacks.getValue(t).take(1) else tab = t
        version++
    }

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
