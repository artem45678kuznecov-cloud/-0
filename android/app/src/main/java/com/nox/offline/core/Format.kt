package com.nox.offline.core

import java.util.Locale

/** Форматтеры величин — те же правила, что в nox_core.py. */
object Format {
    private const val KIB = 1024.0
    private const val MIB = KIB * 1024
    private const val GIB = MIB * 1024

    fun bytes(value: Long): String {
        if (value < 0) return "—"
        return when {
            value >= GIB -> String.format(Locale.US, "%.1f ГБ", value / GIB)
            value >= MIB -> String.format(Locale.US, "%.1f МБ", value / MIB)
            value >= KIB -> String.format(Locale.US, "%.0f КБ", value / KIB)
            else -> "$value Б"
        }
    }

    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "—"
        return bytes(bytesPerSecond) + "/с"
    }

    fun eta(seconds: Long): String {
        if (seconds < 0) return "—"
        if (seconds < 60) return "меньше минуты"
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes мин"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours ч" else "$hours ч $rest мин"
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** «12 мин», «1 ч 5 мин», «45 с» — для строк метаданных. */
    fun shortDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        if (seconds < 60) return "$seconds с"
        val minutes = (seconds + 30) / 60
        if (minutes < 60) return "$minutes мин"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h ч" else "$h ч $m мин"
    }

    /** «12:03» / «1:02:03» — бейдж на обложке. */
    fun clock(seconds: Long): String = duration(seconds)

    /** Оставшееся время загрузки часами: «09:36», «1:12:05». */
    fun etaClock(seconds: Long): String {
        if (seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun percent(downloaded: Long, total: Long): Int {
        if (total <= 0) return 0
        return ((downloaded.coerceIn(0, total) * 100) / total).toInt()
    }
}
