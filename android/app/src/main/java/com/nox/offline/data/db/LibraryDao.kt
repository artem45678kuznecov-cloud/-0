package com.nox.offline.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Коллекция вместе с посчитанными по базе счётчиками. */
data class CollectionSummary(
    val id: Long,
    val title: String,
    val description: String,
    val type: String,
    val coverMediaId: Long,
    val coverPath: String,
    val pinned: Boolean,
    val sortOrder: Long,
    val tags: String,
    val sourceUrl: String,
    val systemKey: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Всего элементов (включая ожидающие скачивания). */
    val items: Int,
    /** Из них есть на устройстве. */
    val local: Int,
    /** Первое локальное видео — запасная обложка. */
    val firstMediaId: Long,
) {
    val entity: CollectionEntity
        get() = CollectionEntity(id, title, description, type, coverMediaId, coverPath, pinned, sortOrder, tags, sourceUrl,
            systemKey, createdAt, updatedAt)
}

@Dao
interface CollectionDao {
    @Query("""
        SELECT c.*,
          (SELECT COUNT(*) FROM collection_items i WHERE i.collectionId = c.id) AS items,
          (SELECT COUNT(*) FROM collection_items i WHERE i.collectionId = c.id AND i.mediaId > 0) AS local,
          COALESCE((SELECT i.mediaId FROM collection_items i WHERE i.collectionId = c.id AND i.mediaId > 0
                    ORDER BY i.season, i.position, i.id LIMIT 1), 0) AS firstMediaId
        FROM collections c ORDER BY c.sortOrder ASC, c.createdAt ASC
    """)
    fun observeSummaries(): Flow<List<CollectionSummary>>

    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun get(id: Long): CollectionEntity?

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observe(id: Long): Flow<CollectionEntity?>

    @Query("SELECT * FROM collections WHERE systemKey = :key LIMIT 1")
    suspend fun bySystemKey(key: String): CollectionEntity?

    @Query("SELECT * FROM collections WHERE sourceUrl != '' AND sourceUrl = :url LIMIT 1")
    suspend fun bySourceUrl(url: String): CollectionEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM collections")
    suspend fun maxSortOrder(): Long

    @Insert
    suspend fun insert(c: CollectionEntity): Long

    @Update
    suspend fun update(c: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    // ---------------- элементы ----------------

    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId ORDER BY season ASC, position ASC, id ASC")
    fun observeItems(collectionId: Long): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId ORDER BY season ASC, position ASC, id ASC")
    suspend fun items(collectionId: Long): List<CollectionItemEntity>

    @Query("SELECT * FROM collection_items ORDER BY collectionId, season, position, id")
    suspend fun allItems(): List<CollectionItemEntity>

    @Query("SELECT * FROM collection_items ORDER BY collectionId, season, position, id")
    fun observeAllItems(): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM collection_items WHERE id = :id")
    suspend fun item(id: Long): CollectionItemEntity?

    @Query("SELECT * FROM collection_items WHERE mediaId = :mediaId")
    suspend fun itemsForMedia(mediaId: Long): List<CollectionItemEntity>

    @Query("SELECT * FROM collection_items WHERE sourceKey != '' AND sourceKey = :sourceKey")
    suspend fun itemsForSource(sourceKey: String): List<CollectionItemEntity>

    @Query("SELECT COALESCE(MAX(position), 0) FROM collection_items WHERE collectionId = :collectionId")
    suspend fun maxPosition(collectionId: Long): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: CollectionItemEntity): Long

    @Update
    suspend fun updateItem(item: CollectionItemEntity)

    @Query("DELETE FROM collection_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId")
    suspend fun deleteItemsOf(collectionId: Long)

    @Query("DELETE FROM collection_items WHERE mediaId = :mediaId")
    suspend fun deleteItemsForMedia(mediaId: Long)

    @Query("DELETE FROM collection_items WHERE chapterId = :chapterId")
    suspend fun deleteItemsForChapter(chapterId: Long)

    @Query("SELECT COUNT(*) FROM collection_items WHERE collectionId = :collectionId AND mediaId = :mediaId AND chapterId = 0")
    suspend fun contains(collectionId: Long, mediaId: Long): Int

    // ---------------- сезоны ----------------

    @Query("SELECT * FROM seasons WHERE collectionId = :collectionId ORDER BY number")
    fun observeSeasons(collectionId: Long): Flow<List<SeasonEntity>>

    @Query("SELECT * FROM seasons ORDER BY collectionId, number")
    suspend fun allSeasons(): List<SeasonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeason(s: SeasonEntity): Long

    @Query("DELETE FROM seasons WHERE collectionId = :collectionId")
    suspend fun deleteSeasonsOf(collectionId: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE mediaId = :mediaId ORDER BY startMs ASC, id ASC")
    fun observeFor(mediaId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE mediaId = :mediaId ORDER BY startMs ASC, id ASC")
    suspend fun forMedia(mediaId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters ORDER BY mediaId, startMs")
    suspend fun getAll(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: Long): ChapterEntity?

    @Insert
    suspend fun insert(c: ChapterEntity): Long

    @Update
    suspend fun update(c: ChapterEntity)

    @Query("DELETE FROM chapters WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM chapters WHERE mediaId = :mediaId")
    suspend fun deleteForMedia(mediaId: Long)

    // ---------------- позиции виртуальных серий ----------------

    @Query("SELECT * FROM segment_progress WHERE chapterId = :chapterId")
    suspend fun progress(chapterId: Long): SegmentProgressEntity?

    @Query("SELECT * FROM segment_progress")
    fun observeProgress(): Flow<List<SegmentProgressEntity>>

    @Query("SELECT * FROM segment_progress")
    suspend fun allProgress(): List<SegmentProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(p: SegmentProgressEntity)

    @Query("DELETE FROM segment_progress WHERE chapterId = :chapterId")
    suspend fun deleteProgress(chapterId: Long)

    // ---------------- закладки ----------------

    @Query("SELECT * FROM bookmarks WHERE mediaId = :mediaId ORDER BY positionMs ASC")
    fun observeBookmarks(mediaId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY mediaId, positionMs")
    suspend fun allBookmarks(): List<BookmarkEntity>

    @Insert
    suspend fun insertBookmark(b: BookmarkEntity): Long

    @Update
    suspend fun updateBookmark(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmarks WHERE mediaId = :mediaId")
    suspend fun deleteBookmarksFor(mediaId: Long)
}

@Dao
interface SubtitleDao {
    @Query("SELECT * FROM subtitles WHERE mediaId = :mediaId ORDER BY origin, language, id")
    fun observeFor(mediaId: Long): Flow<List<SubtitleEntity>>

    @Query("SELECT * FROM subtitles WHERE mediaId = :mediaId ORDER BY origin, language, id")
    suspend fun forMedia(mediaId: Long): List<SubtitleEntity>

    @Query("SELECT * FROM subtitles ORDER BY mediaId, id")
    suspend fun getAll(): List<SubtitleEntity>

    @Query("SELECT * FROM subtitles WHERE id = :id")
    suspend fun get(id: Long): SubtitleEntity?

    @Insert
    suspend fun insert(s: SubtitleEntity): Long

    @Query("DELETE FROM subtitles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM subtitles WHERE mediaId = :mediaId")
    suspend fun deleteForMedia(mediaId: Long)
}
