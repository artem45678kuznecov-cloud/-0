package com.nox.offline.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Личная медиатека 0.4.0: коллекции, сериалы, главы, закладки, субтитры.
 *
 * Всё здесь — связи и разметка поверх [MediaEntity]. Файлы не копируются,
 * не режутся и не переносятся: одно видео может быть в нескольких
 * коллекциях, глава — это интервал внутри файла. Удаление коллекции или
 * главы никогда не удаляет видео.
 *
 * Время хранится в миллисекундах в Long: марафоны длиной в сутки и больше
 * не переполняются и не «оборачиваются» после 24 часов.
 */

/** Виды коллекций. */
object CollectionType {
    /** Пользовательская подборка («Альбомы»). */
    const val ALBUM = "album"
    /** Сериал: сезоны и серии, ручной порядок, «Продолжить». */
    const val SERIES = "series"
    /** Пользовательская метка-категория («Аниме», «Фильмы»…). */
    const val CATEGORY = "category"
    /** Системные списки: одно действие «в избранное» / «посмотреть позже». */
    const val FAVORITES = "favorites"
    const val WATCH_LATER = "watch_later"

    val system = setOf(FAVORITES, WATCH_LATER)
}

@Entity(tableName = "collections", indices = [Index("type"), Index("pinned")])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(defaultValue = "''") val description: String = "",
    @ColumnInfo(defaultValue = "'album'") val type: String = CollectionType.ALBUM,
    /** Обложка из медиа коллекции (0 — нет) или своё изображение (путь внутри NOX). */
    @ColumnInfo(defaultValue = "0") val coverMediaId: Long = 0,
    @ColumnInfo(defaultValue = "''") val coverPath: String = "",
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Ручной порядок коллекций (меньше — выше). */
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    /** Метки через запятую: «Приключения, Фэнтези». */
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    /** Ссылка на плейлист источника, если коллекция создана из него. */
    @ColumnInfo(defaultValue = "''") val sourceUrl: String = "",
    /** Ключ встроенной записи (favorites, watch_later, anime…), '' — своя. */
    @ColumnInfo(defaultValue = "''") val systemKey: String = "",
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isSystem: Boolean get() = type in CollectionType.system
    val isSeries: Boolean get() = type == CollectionType.SERIES
    val tagList: List<String> get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Элемент коллекции. Три случая:
 *  - целый файл: [mediaId] > 0, [chapterId] = 0;
 *  - виртуальная серия внутри файла: [mediaId] > 0, [chapterId] > 0;
 *  - ещё не скачан: [mediaId] = 0 и известен источник ([sourceKey]).
 */
@Entity(
    tableName = "collection_items",
    indices = [
        Index("collectionId"), Index("mediaId"),
        Index(value = ["collectionId", "mediaId", "chapterId", "sourceKey"], unique = true),
    ],
)
data class CollectionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    @ColumnInfo(defaultValue = "0") val mediaId: Long = 0,
    @ColumnInfo(defaultValue = "0") val chapterId: Long = 0,
    /** Ручной порядок внутри коллекции / сезона. */
    @ColumnInfo(defaultValue = "0") val position: Long = 0,
    @ColumnInfo(defaultValue = "0") val season: Int = 0,
    @ColumnInfo(defaultValue = "0") val episode: Int = 0,
    /** Своё название серии ('' — название видео или главы). */
    @ColumnInfo(defaultValue = "''") val title: String = "",
    /** Известная ссылка на ролик ('' — неизвестна; ничего не угадываем). */
    @ColumnInfo(defaultValue = "''") val sourceUrl: String = "",
    /** «extractor:videoId» для сопоставления с загрузками; '' для локальных. */
    @ColumnInfo(defaultValue = "''") val sourceKey: String = "",
    /** Для ожидающих: недоступно у источника (удалено/закрыто) и почему. */
    @ColumnInfo(defaultValue = "''") val unavailableReason: String = "",
    val addedAt: Long,
) {
    val isLocal: Boolean get() = mediaId > 0
    val isVirtual: Boolean get() = mediaId > 0 && chapterId > 0
}

/** Названия сезонов сериала. */
@Entity(tableName = "seasons", indices = [Index(value = ["collectionId", "number"], unique = true)])
data class SeasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val number: Int,
    @ColumnInfo(defaultValue = "''") val title: String = "",
)

/** Вид разметки внутри файла. */
object ChapterKind {
    /** Глава оглавления: переход, подпись. */
    const val CHAPTER = "chapter"
    /** Виртуальная серия: свой статус просмотра и своя позиция. */
    const val EPISODE = "episode"
}

/**
 * Глава или виртуальная серия: интервал [startMs, endMs) внутри файла.
 * endMs = -1 — до начала следующей главы или до конца файла.
 */
@Entity(tableName = "chapters", indices = [Index("mediaId")])
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val title: String,
    val startMs: Long,
    @ColumnInfo(defaultValue = "-1") val endMs: Long = -1,
    @ColumnInfo(defaultValue = "'chapter'") val kind: String = ChapterKind.CHAPTER,
    /** user — создана пользователем; source — пришла от источника. */
    @ColumnInfo(defaultValue = "'user'") val origin: String = "user",
    val createdAt: Long,
)

/** Позиция и статус виртуальной серии — отдельно от позиции всего файла. */
@Entity(tableName = "segment_progress")
data class SegmentProgressEntity(
    @PrimaryKey val chapterId: Long,
    /** Позиция внутри серии (от её начала), мс. */
    val positionMs: Long,
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
    val updatedAt: Long,
)

/** Закладка: абсолютное время в файле, название и заметка. */
@Entity(tableName = "bookmarks", indices = [Index("mediaId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val positionMs: Long,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "''") val note: String = "",
    val createdAt: Long,
)

/** Дорожка субтитров, привязанная к видео. Файл хранится внутри NOX. */
@Entity(tableName = "subtitles", indices = [Index("mediaId")])
data class SubtitleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    /** Код языка ('' — неизвестен). */
    @ColumnInfo(defaultValue = "''") val language: String = "",
    val label: String,
    /** source — авторские, auto — автоматические источника, user — свой файл. */
    @ColumnInfo(defaultValue = "'user'") val origin: String = "user",
    /** vtt / srt. */
    val format: String,
    val filePath: String,
    @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
    val createdAt: Long,
)
