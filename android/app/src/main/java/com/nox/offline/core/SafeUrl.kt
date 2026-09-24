package com.nox.offline.core

import java.net.URI

/**
 * Адреса в журнал попадают только без строки запроса: у VK-CDN в ней
 * подпись и срок жизни ссылки, и это не для диагностики.
 */
object SafeUrl {
    fun redact(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        return try {
            val u = URI(url)
            val host = u.host ?: return "invalid"
            val path = u.rawPath ?: ""
            val scheme = u.scheme ?: "https"
            val shortPath = if (path.length > 48) path.take(45) + "..." else path
            "$scheme://$host$shortPath" + if (u.rawQuery.isNullOrEmpty()) "" else "?…"
        } catch (e: Exception) {
            "invalid"
        }
    }

    fun host(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        return try {
            URI(url).host ?: "-"
        } catch (e: Exception) {
            "-"
        }
    }

    /** Похоже ли на ссылку, которую есть смысл отдавать резолверу. */
    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("http://") || t.startsWith("https://")) && t.length > 12 && !t.contains(' ')
    }

    /** Из произвольного текста («Поделиться») вытащить первую ссылку. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = Regex("https?://[^\\s<>\"']+").find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ']')
    }
}
