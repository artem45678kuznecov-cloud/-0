package com.nox.offline.ui.library

import com.nox.offline.ui.components.LibraryItem

enum class SortKey(val label: String) {
    DATE("По дате"),
    TITLE("По названию"),
    SIZE("По размеру"),
    DURATION("По длительности"),
    QUALITY("По качеству"),
}

enum class WatchFilter(val label: String) {
    ALL("Все"),
    UNWATCHED("Не начатые"),
    IN_PROGRESS("Незавершённые"),
    WATCHED("Просмотренные"),
}

enum class QualityFilter(val label: String) {
    ANY("Любое"),
    SD("До 480p"),
    HD("720p"),
    FULL_HD("1080p и выше"),
}

data class LibraryFilter(
    val sort: SortKey = SortKey.DATE,
    val descending: Boolean = true,
    val watch: WatchFilter = WatchFilter.ALL,
    val quality: QualityFilter = QualityFilter.ANY,
) {
    val isDefault: Boolean get() = this == LibraryFilter()
}

/** Поиск, фильтры и сортировка медиатеки. Чистая функция — есть JVM-тест. */
object LibraryQuery {
    fun apply(items: List<LibraryItem>, query: String, f: LibraryFilter): List<LibraryItem> {
        val q = query.trim().lowercase()
        val filtered = items.filter { item ->
            val m = item.media
            val matchesQuery = q.isEmpty() || m.title.lowercase().contains(q) || m.uploader.lowercase().contains(q)
            val p = item.playback
            val watch = when (f.watch) {
                WatchFilter.ALL -> true
                WatchFilter.UNWATCHED -> p == null || (!p.completed && p.positionMs < 5_000)
                WatchFilter.IN_PROGRESS -> p?.inProgress == true
                WatchFilter.WATCHED -> p?.completed == true
            }
            // Вертикальное 1080×1920 — это 1080p: считаем по короткой стороне, если она известна.
            val h = if (m.width > 0 && m.height > 0) minOf(m.width, m.height) else m.height
            val quality = when (f.quality) {
                QualityFilter.ANY -> true
                QualityFilter.SD -> h in 1..480
                QualityFilter.HD -> h in 481..720
                QualityFilter.FULL_HD -> h > 720
            }
            matchesQuery && watch && quality
        }
        val comparator: Comparator<LibraryItem> = when (f.sort) {
            SortKey.DATE -> compareBy { it.media.createdAt }
            SortKey.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.media.title }
            SortKey.SIZE -> compareBy { it.media.sizeBytes }
            SortKey.DURATION -> compareBy { it.media.durationSec }
            SortKey.QUALITY -> compareBy<LibraryItem> { it.media.height }.thenBy { it.media.sizeBytes }
        }
        return filtered.sortedWith(if (f.descending) comparator.reversed() else comparator)
    }

    /** «Продолжить просмотр»: последнее реально открытое и недосмотренное видео. */
    fun continueWatching(items: List<LibraryItem>): LibraryItem? =
        items.filter { it.playback?.inProgress == true }.maxByOrNull { it.playback!!.updatedAt }
}
