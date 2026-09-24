package com.nox.offline.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Состояния задания — те же, что в NOX, но уже как Kotlin enum. */
enum class DownloadStatus {
    QUEUED,        // ждёт слот (или ждёт, пока пользователь продолжит после сбоя)
    RESOLVING,     // yt-dlp ищет прямой адрес
    DOWNLOADING,   // байты идут в .part
    PAUSED,        // пользователь нажал паузу; .part лежит на диске
    PROCESSING,    // .part -> mp4, обложка, запись в медиатеку
    COMPLETED,
    ERROR;

    val isActive: Boolean get() = this == RESOLVING || this == DOWNLOADING || this == PROCESSING
    val isPending: Boolean get() = this == QUEUED || isActive
}

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
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes.coerceIn(0, totalBytes) * 100) / totalBytes).toInt() else 0
}
