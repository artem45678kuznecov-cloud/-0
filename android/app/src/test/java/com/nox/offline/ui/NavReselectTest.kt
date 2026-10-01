package com.nox.offline.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** «В начало раздела» по двойному нажатию: только выбранная вкладка, без побочных сбросов. */
class NavReselectTest {
    @Test fun `double tap pops only this tab to its root`() {
        val nav = Nav()
        nav.open(Page.Appearance, Tab.SETTINGS)
        nav.open(Page.Collection(5), Tab.HOME)
        nav.open(Page.AllVideos, Tab.HOME)
        assertEquals(3, nav.depth)

        nav.reselect(Tab.HOME)
        assertEquals(Tab.HOME, nav.tab)
        assertEquals(Page.Home, nav.current)
        assertEquals("начало уже было не открыто — прокручивать нечего", 0, nav.topRequest(Tab.HOME))

        // Настройки остались там, где были.
        nav.select(Tab.SETTINGS)
        assertEquals(Page.Appearance, nav.current)
    }

    @Test fun `double tap on a root page asks it to scroll to the top`() {
        val nav = Nav()
        nav.select(Tab.DOWNLOADS)
        nav.reselect(Tab.DOWNLOADS)
        assertEquals(Page.Queue, nav.current)
        assertEquals(1, nav.topRequest(Tab.DOWNLOADS))
        nav.reselect(Tab.DOWNLOADS)
        assertEquals(2, nav.topRequest(Tab.DOWNLOADS))
        assertEquals(0, nav.topRequest(Tab.HOME))
    }

    @Test fun `double tap on an inactive tab selects it and returns to its root`() {
        val nav = Nav()
        nav.open(Page.Downloader, Tab.DOWNLOADS)
        nav.select(Tab.PLAYER)
        // Первое нажатие — обычный выбор вкладки: её вложенная страница на месте.
        nav.select(Tab.DOWNLOADS)
        assertEquals(Page.Downloader, nav.current)
        // Второе — в начало раздела.
        nav.reselect(Tab.DOWNLOADS)
        assertEquals(Page.Queue, nav.current)
        assertEquals(Tab.DOWNLOADS, nav.tab)
    }

    @Test fun `back still works step by step after a reselect`() {
        val nav = Nav()
        nav.open(Page.Backup, Tab.SETTINGS)
        nav.reselect(Tab.SETTINGS)
        assertEquals(Page.Settings, nav.current)
        nav.open(Page.About)
        nav.back()
        assertEquals(Page.Settings, nav.current)
        nav.back()
        assertEquals(Tab.HOME, nav.tab)
    }
}
