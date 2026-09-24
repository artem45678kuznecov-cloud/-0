package com.nox.offline.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkParserTest {
    @Test fun oneLinkPerLineWithDuplicatesAndGarbage() {
        val text = """
            https://vk.com/video-1_2
            просто текст
            https://m.vk.com/video-1_2/
            Смотри: https://vkvideo.ru/video-3_4, круто
            
            http://www.vk.com/video-5_6.
        """.trimIndent()
        val lines = LinkParser.lines(text)
        assertEquals(
            listOf(LinkParser.Kind.LINK, LinkParser.Kind.NOT_A_LINK, LinkParser.Kind.DUPLICATE, LinkParser.Kind.LINK, LinkParser.Kind.LINK),
            lines.map { it.kind },
        )
        assertEquals(
            listOf("https://vk.com/video-1_2", "https://vkvideo.ru/video-3_4", "http://www.vk.com/video-5_6"),
            LinkParser.links(text),
        )
    }

    @Test fun singleLinkStaysSingle() {
        assertEquals(listOf("https://vk.com/video1"), LinkParser.links("  https://vk.com/video1  "))
    }

    @Test fun normalizeIgnoresSchemeHostPrefixAndSlash() {
        assertEquals(LinkParser.normalize("https://www.vk.com/a/"), LinkParser.normalize("http://vk.com/a"))
    }
}
