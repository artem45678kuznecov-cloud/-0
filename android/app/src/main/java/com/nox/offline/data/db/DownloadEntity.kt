package com.nox.offline.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Состояния задания — те же, что в NOX, но уже как Kotlin enum. */
enum class DownloadStatus {
    QUEUED,        // ждёт слот (или ждёт, пока пользователь продолжит после сбоя)
    RESOLVING,     // yt-dlp ищет прямой адрес
    DOWNLOADING,   // байты идут в .part
    PAUSED,        // пользователь нажал паузу; .part лежит на диске
    PROCESSING,    // .part -> mp4 (или склейка дорожек), обложка, запись в медиатеку
    COMPLETED,
    ERROR;

    val isActive: Boolean get() = this == RESOLVING || this == DOWNLOADING || this == PROCESSING
    val isPending: Boolean get() = this == QUEUED || isActive
}

/** Как качается файл: одним прогрессивным потоком или двумя дорожками. */
object DownloadMode {
    const val PROGRESSIVE = "progressive"
    const val SPLIT = "split"
}

/**
 * Задание загрузки.
 *
 * Поля до `createdAt/updatedAt` — схема v1 (v0.1.0). Всё, что ниже
 * помечено «v2», добавлено миграцией 1→2 со значениями по умолчанию,
 * поэтому старые строки читаются без изменений.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageUrl: String,
    val quality: String,                 // 360 / 480 / 720 / MAX
    val title: String = "",
    val videoId: String = "",
    val formatId: String = "",
    val resolvedUrl: String = "",        // прямой адрес; пусто = нужен разбор
    val headersJson: String = "{}",      // белый список заголовков, JSON
    val fileName: String = "",           // имя итогового файла в Media/
    val ext: String = "mp4",
    val height: Int = 0,
    val totalBytes: Long = 0,            // 0 = пока неизвестно
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String = "",
    val speedBps: Long = 0,
    val etaSec: Long = -1,
    val retries: Int = 0,                // подряд идущие сетевые сбои
    val resolveRetries: Int = 0,         // подряд идущие протухшие адреса
    val thumbnailUrl: String = "",
    val durationSec: Long = 0,
    val lastStopReason: String = "",
    val createdAt: Long,
    val updatedAt: Long,

    // ---- v2 ----
    /** Название, заданное пользователем. Файл получает его при завершении. */
    @ColumnInfo(defaultValue = "''") val customTitle: String = "",
    @ColumnInfo(defaultValue = "'progressive'") val mode: String = DownloadMode.PROGRESSIVE,
    @ColumnInfo(defaultValue = "''") val audioUrl: String = "",
    @ColumnInfo(defaultValue = "'{}'") val audioHeadersJson: String = "{}",
    @ColumnInfo(defaultValue = "''") val audioFormatId: String = "",
    @ColumnInfo(defaultValue = "0") val videoTotalBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val audioTotalBytes: Long = 0,
    /** Дорожка уже целиком на диске — после паузы её не качаем заново. */
    @ColumnInfo(defaultValue = "0") val videoDone: Boolean = false,
    @ColumnInfo(defaultValue = "0") val audioDone: Boolean = false,
    @ColumnInfo(defaultValue = "''") val uploader: String = "",
    /** Разрешил ли пользователь раздельные дорожки для этого задания. */
    @ColumnInfo(defaultValue = "0") val allowSplit: Boolean = false,
) {
    val displayTitle: String get() = customTitle.ifBlank { title }

    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes.coerceIn(0, totalBytes) * 100) / totalBytes).toInt() else 0

    val isSplit: Boolean get() = mode == DownloadMode.SPLIT
}
