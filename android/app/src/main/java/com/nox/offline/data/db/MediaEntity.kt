package com.nox.offline.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Готовое видео в медиатеке.
 *
 * Личность видео — [id], а не название и не путь: переименование и
 * перенос в другую папку не теряют позицию просмотра.
 *
 * Файл лежит либо внутри NOX ([filePath], Media/), либо в папке,
 * выбранной пользователем через SAF ([contentUri]). Ровно одно из двух
 * непусто; content URI никогда не трактуется как путь файловой системы.
 */
@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,                // абсолютный путь к mp4 внутри NOX или ''
    val sizeBytes: Long,
    val quality: String,
    val height: Int = 0,
    val durationSec: Long = 0,
    val coverPath: String = "",
    val pageUrl: String = "",
    val videoId: String = "",
    val createdAt: Long,

    // ---- v2 ----
    @ColumnInfo(defaultValue = "''") val contentUri: String = "",
    @ColumnInfo(defaultValue = "''") val uploader: String = "",
    @ColumnInfo(defaultValue = "0") val imported: Boolean = false,
    /** '' — на месте; 'pending' — ждёт переноса в выбранную папку. */
    @ColumnInfo(defaultValue = "''") val moveState: String = "",
) {
    val isExternal: Boolean get() = contentUri.isNotBlank()
}

/** Позиция просмотра: одна строка на видео. */
@Entity(tableName = "playback")
data class PlaybackEntity(
    @PrimaryKey val mediaId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    // ---- v2 ----
    /** Досмотрено до конца: для фильтра «просмотренные». */
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Начато, но не досмотрено — кандидат на «Продолжить просмотр». */
    val inProgress: Boolean get() = !completed && positionMs > 5_000 && durationMs > 0 && positionMs < durationMs - 5_000
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media ORDER BY createdAt DESC")
    suspend fun getAll(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE moveState = 'pending'")
    suspend fun pendingMoves(): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media WHERE videoId != '' AND videoId = :videoId")
    suspend fun countByVideoId(videoId: String): Int

    @Query("SELECT COUNT(*) FROM media WHERE pageUrl = :pageUrl")
    suspend fun countByPageUrl(pageUrl: String): Int

    @Insert
    suspend fun insert(entity: MediaEntity): Long

    @Update
    suspend fun update(entity: MediaEntity)

    @Delete
    suspend fun delete(entity: MediaEntity)
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaybackEntity>>

    @Query("SELECT * FROM playback ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PlaybackEntity>

    @Query("SELECT * FROM playback WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): PlaybackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackEntity)

    @Query("DELETE FROM playback WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)
}
