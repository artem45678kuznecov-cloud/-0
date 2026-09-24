package com.nox.offline.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','RESOLVING','DOWNLOADING','PAUSED','PROCESSING') ORDER BY createdAt ASC")
    fun observeLive(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt ASC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt ASC")
    suspend fun byStatus(status: DownloadStatus): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED','RESOLVING','DOWNLOADING','PROCESSING')")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE pageUrl = :pageUrl AND status != 'COMPLETED' AND status != 'ERROR'")
    suspend fun countLiveFor(pageUrl: String): Int

    @Query("SELECT pageUrl FROM downloads WHERE status != 'COMPLETED' AND status != 'ERROR'")
    suspend fun livePageUrls(): List<String>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','RESOLVING','DOWNLOADING') ORDER BY createdAt ASC")
    suspend fun running(): List<DownloadEntity>

    @Query("SELECT COALESCE(SUM(totalBytes - downloadedBytes), 0) FROM downloads WHERE status IN ('QUEUED','RESOLVING','DOWNLOADING') AND totalBytes > 0")
    suspend fun remainingKnownBytes(): Long

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Delete
    suspend fun delete(entity: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, speedBps = :speed, etaSec = :eta, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long, speed: Long, eta: Long, now: Long)

    @Query("UPDATE downloads SET status = :status, error = :error, speedBps = 0, etaSec = -1, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String, now: Long)
}
