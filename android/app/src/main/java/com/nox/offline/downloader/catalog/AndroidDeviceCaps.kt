package com.nox.offline.downloader.catalog

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Декодеры телефона по MediaCodecList. Проверяется именно декодирование
 * (кодек, размер кадра, частота), а не размер экрана: 1440p на экране
 * 1080p воспроизводится с уменьшением и не считается «невоспроизводимым».
 */
class AndroidDeviceCaps : DeviceCaps {
    override val sdk: Int = Build.VERSION.SDK_INT

    private val decoders: List<MediaCodecInfo> by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
            .getOrDefault(emptyList())
    }
    private val cache = ConcurrentHashMap<String, Playback>()

    override fun decode(codec: String, width: Int, height: Int, fps: Double): Playback {
        val mime = mimeOf(codec) ?: return Playback.UNKNOWN
        val w = if (width > 0) width else height * 16 / 9
        val h = if (height > 0) height else width * 9 / 16
        if (w <= 0 || h <= 0) return Playback.UNKNOWN
        val rate = if (fps > 0) fps else 30.0
        return cache.getOrPut("$mime|$w|$h|${rate.toInt()}") { query(mime, w, h, rate) }
    }

    private fun query(mime: String, w: Int, h: Int, fps: Double): Playback {
        var software = false
        for (info in decoders) {
            if (info.supportedTypes.none { it.equals(mime, true) }) continue
            val vc = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
            val fits = runCatching { vc.areSizeAndRateSupported(w, h, fps) || vc.areSizeAndRateSupported(h, w, fps) }
                .getOrDefault(false)
            if (!fits) continue
            if (isHardware(info)) return Playback.LIKELY
            software = true
        }
        return if (software) Playback.SOFTWARE else Playback.UNLIKELY
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
    }

    companion object {
        fun mimeOf(codec: String): String? = when (codec) {
            "h264" -> "video/avc"
            "h265" -> "video/hevc"
            "vp9" -> "video/x-vnd.on2.vp9"
            "vp8" -> "video/x-vnd.on2.vp8"
            "av1" -> "video/av01"
            "dv" -> "video/dolby-vision"
            else -> null
        }
    }
}
