package com.nox.offline.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Готовое видео в медиатеке. Файл лежит в Media/, обложка — в Covers/. */
@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,                // абсолютный путь к mp4
    val sizeBytes: Long,
    val quality: String,
    val height: Int = 0,
    val durationSec: Long = 0,
    val coverPath: String = "",
    val pageUrl: String = "",
    val videoId: String = "",
    val createdAt: Long,
)

/** Позиция просмотра: одна строка на видео. */
@Entity(tableName = "playback")
data class PlaybackEntity(
    @PrimaryKey val mediaId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media ORDER BY createdAt DESC")
    suspend fun getAll(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: Long): MediaEntity?

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

    @Query("SELECT * FROM playback WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): PlaybackEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackEntity)

    @Query("DELETE FROM playback WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)
}
