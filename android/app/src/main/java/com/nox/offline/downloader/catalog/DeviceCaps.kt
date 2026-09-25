package com.nox.offline.downloader.catalog

/**
 * Что умеет этот телефон: версия Android (для склейки MediaMuxer) и
 * декодеры (для оценки воспроизведения). Интерфейс — чтобы каталог
 * проверялся JVM-тестами без Android.
 */
interface DeviceCaps {
    val sdk: Int

    /** Оценка декодирования видео данного кодека, размера и частоты кадров. */
    fun decode(codec: String, width: Int, height: Int, fps: Double): Playback
}

/**
 * Какие пары дорожек MediaMuxer собирает без перекодирования.
 * Таблица кодеков и контейнеров — из документации MediaMuxer:
 *  MP4: AAC, H.264, H.265 (API 24+), AV1 (API 34+);
 *  WebM: VP8, VP9 (API 24+), Vorbis, Opus (API 29+).
 * До Android 11 MP4-писатель системы работает с 32-битными смещениями,
 * поэтому склейка MP4 больше ~4 ГБ там не допускается заранее.
 */
object MuxRoutes {
    const val MP4_32BIT_LIMIT = 4_000_000_000L
    const val SDK_MP4_64BIT = 30

    data class Route(val container: String?, val reason: String)

    fun videoInMp4(vcodec: String, sdk: Int): String? = when (vcodec) {
        "h264" -> null
        "h265" -> if (sdk >= 24) null else "Сборка H.265 доступна с Android 7"
        "av1" -> if (sdk >= 34) null else "Сборка AV1 без перекодирования доступна с Android 14"
        else -> "${CodecNames.video(vcodec)} нельзя положить в MP4 без перекодирования"
    }

    fun videoInWebm(vcodec: String, sdk: Int): String? = when (vcodec) {
        "vp9" -> if (sdk >= 24) null else "Сборка VP9 доступна с Android 7"
        "vp8" -> null
        else -> "${CodecNames.video(vcodec)} нельзя положить в WebM без перекодирования"
    }

    fun audioInWebm(acodec: String, sdk: Int): String? = when (acodec) {
        "opus" -> if (sdk >= 29) null else "Сборка звука Opus доступна с Android 10"
        "vorbis" -> null
        else -> "${CodecNames.audio(acodec)} нельзя положить в WebM"
    }

    fun route(video: Track, audio: Track, sdk: Int): Route {
        val vc = video.container
        val ac = audio.container
        return when {
            vc == "mp4" && ac == "mp4" -> {
                val v = videoInMp4(video.vcodec, sdk)
                val a = if (audio.acodec == "aac") null else "${CodecNames.audio(audio.acodec)} нельзя положить в MP4 без перекодирования"
                if (v == null && a == null) Route("mp4", "") else Route(null, v ?: a!!)
            }
            vc == "webm" && ac == "webm" -> {
                val v = videoInWebm(video.vcodec, sdk)
                val a = audioInWebm(audio.acodec, sdk)
                if (v == null && a == null) Route("webm", "") else Route(null, v ?: a!!)
            }
            else -> Route(null, "Видео ${CodecNames.container(vc)} и звук ${CodecNames.container(ac)} не собираются в один файл без перекодирования")
        }
    }

    /** Подходит ли звуковая дорожка к видео по маршруту склейки (без учёта версии Android). */
    fun sameFamily(video: Track, audio: Track): Boolean = video.container == audio.container && video.container in setOf("mp4", "webm")
}

/** Для тестов и для случаев, когда декодеры узнать нельзя. */
class FixedCaps(override val sdk: Int, private val playback: (String, Int, Int, Double) -> Playback = { _, _, _, _ -> Playback.LIKELY }) : DeviceCaps {
    override fun decode(codec: String, width: Int, height: Int, fps: Double) = playback(codec, width, height, fps)
}
