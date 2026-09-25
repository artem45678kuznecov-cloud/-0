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
    /** 0.2.x: 360 / 480 / 720 / MAX (лестница). С 0.3.0 — подпись выбранного варианта («1440p60»). */
    val quality: String,
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

    // ---- v3 (0.3.0): точный выбор из каталога ----
    /** 0 — задание 0.2.x (лестница качества); 1 — точный план из каталога. */
    @ColumnInfo(defaultValue = "0") val planVersion: Int = 0,
    @ColumnInfo(defaultValue = "''") val extractorKey: String = "",
    /** Ключ варианта: «v:308+a:251» или «f:url1440». */
    @ColumnInfo(defaultValue = "''") val variantKey: String = "",
    @ColumnInfo(defaultValue = "0") val width: Int = 0,
    @ColumnInfo(defaultValue = "0") val fps: Int = 0,
    @ColumnInfo(defaultValue = "''") val vcodec: String = "",
    @ColumnInfo(defaultValue = "''") val acodec: String = "",
    /** Контейнер итогового файла: mp4 / webm. */
    @ColumnInfo(defaultValue = "''") val container: String = "",
    @ColumnInfo(defaultValue = "''") val dynamicRange: String = "",
    @ColumnInfo(defaultValue = "''") val audioLang: String = "",
    /** Размеры дорожек — точные (от источника), а не оценка: по ним сверяется докачка. */
    @ColumnInfo(defaultValue = "0") val videoExact: Boolean = false,
    @ColumnInfo(defaultValue = "0") val audioExact: Boolean = false,
    /** Размер одного Range-запроса (YouTube режет длинные ответы). 0 — одним запросом. */
    @ColumnInfo(defaultValue = "0") val videoChunk: Long = 0,
    @ColumnInfo(defaultValue = "0") val audioChunk: Long = 0,
    /** Машинная причина ошибки: format-gone, forbidden, no-space ... */
    @ColumnInfo(defaultValue = "''") val errorKind: String = "",
    /** Этап для диагностики: plan, video, audio, merge, verify. */
    @ColumnInfo(defaultValue = "''") val stage: String = "",
) {
    val displayTitle: String get() = customTitle.ifBlank { title }

    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes.coerceIn(0, totalBytes) * 100) / totalBytes).toInt() else 0

    val isSplit: Boolean get() = mode == DownloadMode.SPLIT

    /** Задание с точным выбором формата (0.3.0+). */
    val isPlanned: Boolean get() = planVersion >= 1

    /** Подпись качества для очереди и медиатеки: настоящая, а не «MAX». */
    val qualityLabel: String
        get() = when {
            isPlanned -> quality
            height > 0 -> "${height}p"
            quality == "MAX" -> "наилучшее"
            quality.all { it.isDigit() } -> "до ${quality}p"
            else -> quality
        }
}
