package com.nox.offline.player

/**
 * Таймер сна. Живёт в [PlaybackHub] (один на процесс), поэтому смена
 * вкладки, поворот экрана, полноэкранный режим и PiP не создают второй
 * таймер. Затухание — только громкостью самого плеера, системная
 * громкость не трогается. После перезапуска процесса таймер не
 * восстанавливается и сам ничего не включает.
 */
object SleepTimer {
    sealed class Mode {
        abstract val label: String

        data class At(val endAtElapsed: Long, val minutes: Int) : Mode() {
            override val label: String get() = when {
                minutes % 60 == 0 -> "${minutes / 60} ч"
                minutes > 60 -> "${minutes / 60} ч ${minutes % 60} мин"
                else -> "$minutes мин"
            }
        }

        /** После текущей серии или главы. */
        object AfterSegment : Mode() { override val label = "После серии" }

        /** После текущего файла. */
        object AfterFile : Mode() { override val label = "После файла" }
    }

    const val FADE_MS = 20_000L
    val PRESETS = listOf(30, 60)

    /** Громкость плеера за [remainingMs] до конца: плавно к нулю в последние [FADE_MS]. */
    fun volume(remainingMs: Long): Float = when {
        remainingMs >= FADE_MS -> 1f
        remainingMs <= 0 -> 0f
        else -> (remainingMs.toFloat() / FADE_MS).let { it * it }   // мягче на слух, чем линейно
    }

    fun remaining(mode: Mode?, nowElapsed: Long): Long? = (mode as? Mode.At)?.let { (it.endAtElapsed - nowElapsed).coerceAtLeast(0) }
}
