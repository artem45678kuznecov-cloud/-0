package com.nox.offline.downloader

/** Кнопки 360 / 480 / 720 / MAX — те же, что в NOX. Лестница живёт в resolver.py. */
enum class Quality(val key: String, val label: String) {
    Q360("360", "360"),
    Q480("480", "480"),
    Q720("720", "720"),
    MAX("MAX", "MAX");

    companion object {
        val DEFAULT = Q480
        fun fromKey(key: String?): Quality = entries.firstOrNull { it.key == key?.uppercase() } ?: DEFAULT
    }
}
