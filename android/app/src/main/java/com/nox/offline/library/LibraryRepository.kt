package com.nox.offline.library

import android.content.ContentResolver
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.CollectionEntity
import com.nox.offline.data.db.CollectionItemEntity
import com.nox.offline.data.db.CollectionSummary
import com.nox.offline.data.db.CollectionType
import com.nox.offline.data.db.NoxDatabase
import com.nox.offline.data.db.SeasonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Коллекции, сериалы и категории. Только связи в базе: файлы не
 * копируются, не переносятся и не удаляются. Удаление коллекции убирает
 * связи, видео остаются в медиатеке.
 */
class LibraryRepository(
    private val db: NoxDatabase,
    private val coversDir: File,
    private val prefs: SharedPreferences,
) {
    private val dao get() = db.collections()

    val summaries: Flow<List<CollectionSummary>> get() = dao.observeSummaries()

    /**
     * Встроенные «Избранное» и «Посмотреть позже» и три пустые метки из
     * макета («Аниме», «Фильмы», «Сериалы»). Создаются один раз; метки
     * пользователь может переименовать или удалить, и они не вернутся.
     */
    suspend fun ensureDefaults() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (dao.bySystemKey(CollectionType.FAVORITES) == null) {
            dao.insert(CollectionEntity(title = "Избранное", type = CollectionType.FAVORITES, systemKey = CollectionType.FAVORITES,
                sortOrder = 1_000_000, createdAt = now, updatedAt = now))
        }
        if (dao.bySystemKey(CollectionType.WATCH_LATER) == null) {
            dao.insert(CollectionEntity(title = "Посмотреть позже", type = CollectionType.WATCH_LATER,
                systemKey = CollectionType.WATCH_LATER, sortOrder = 1_000_001, createdAt = now, updatedAt = now))
        }
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            for ((i, pair) in LibraryRules.defaultCategories.withIndex()) {
                if (dao.bySystemKey(pair.first) == null) {
                    dao.insert(CollectionEntity(title = pair.second, type = CollectionType.CATEGORY, systemKey = pair.first,
                        sortOrder = i.toLong(), createdAt = now, updatedAt = now))
                }
            }
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
    }

    suspend fun get(id: Long) = dao.get(id)

    suspend fun create(title: String, type: String = CollectionType.ALBUM, description: String = "", tags: String = "",
                       sourceUrl: String = ""): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(CollectionEntity(title = title.trim().ifBlank { "Новая коллекция" }, description = description.trim(),
            type = type, tags = tags.trim(), sourceUrl = sourceUrl, sortOrder = dao.maxSortOrder() + 1,
            createdAt = now, updatedAt = now))
        NoxLog.event("collection-created", "id" to id, "type" to type)
        return id
    }

    suspend fun update(c: CollectionEntity) = dao.update(c.copy(title = c.title.trim().ifBlank { "Без названия" },
        updatedAt = System.currentTimeMillis()))

    /** Удаляет коллекцию и её связи. Видео не трогаются. Встроенные списки не удаляются. */
    suspend fun delete(id: Long): Boolean {
        val c = dao.get(id) ?: return false
        if (c.isSystem) return false
        db.withTransaction {
            dao.deleteItemsOf(id)
            dao.deleteSeasonsOf(id)
            dao.delete(id)
        }
        if (c.coverPath.isNotBlank()) runCatching { File(c.coverPath).delete() }
        NoxLog.event("collection-deleted", "id" to id)
        return true
    }

    suspend fun setPinned(id: Long, pinned: Boolean) {
        val c = dao.get(id) ?: return
        dao.update(c.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))
    }

    /** Сдвиг коллекции в ручном порядке на одну позицию. */
    suspend fun move(id: Long, delta: Int) = db.withTransaction {
        val all = dao.getAll().toMutableList()
        val i = all.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in all.indices) return@withTransaction
        val a = all[i]; all[i] = all[j]; all[j] = a
        all.forEachIndexed { k, c -> if (c.sortOrder != k.toLong()) dao.update(c.copy(sortOrder = k.toLong())) }
    }

    suspend fun setCoverMedia(id: Long, mediaId: Long) {
        val c = dao.get(id) ?: return
        if (c.coverPath.isNotBlank()) runCatching { File(c.coverPath).delete() }
        dao.update(c.copy(coverMediaId = mediaId, coverPath = "", updatedAt = System.currentTimeMillis()))
    }

    /** Своё изображение: уменьшенная копия внутри NOX, исходник можно удалить. */
    suspend fun setCoverImage(id: Long, uri: Uri, resolver: ContentResolver): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = dao.get(id) ?: error("Коллекция не найдена")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: error("Нет доступа к изображению")
            if (bounds.outWidth <= 0) error("Это не изображение")
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
            val bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Не удалось прочитать изображение")
            coversDir.mkdirs()
            val out = File(coversDir, "collection-$id-${System.currentTimeMillis()}.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            if (c.coverPath.isNotBlank()) runCatching { File(c.coverPath).delete() }
            dao.update(c.copy(coverPath = out.absolutePath, coverMediaId = 0, updatedAt = System.currentTimeMillis()))
        }
    }

    // ---------------- элементы ----------------

    /** Добавить видео в коллекцию. Повтор той же связи не создаёт дубль. Возвращает число добавленных. */
    suspend fun addMedia(collectionId: Long, mediaIds: List<Long>, season: Int = 0): Int = db.withTransaction {
        var pos = dao.maxPosition(collectionId)
        var added = 0
        val now = System.currentTimeMillis()
        val c = dao.get(collectionId) ?: return@withTransaction 0
        for (m in mediaIds.distinct()) {
            if (dao.contains(collectionId, m) > 0) continue
            val media = db.media().get(m) ?: continue
            val guess = if (c.isSeries) SeriesNumbering.guess(media.title) else null
            val id = dao.insertItem(CollectionItemEntity(collectionId = collectionId, mediaId = m, position = ++pos,
                season = guess?.season?.takeIf { it > 0 } ?: season, episode = guess?.episode ?: 0,
                sourceUrl = media.pageUrl, addedAt = now))
            if (id > 0) added++
        }
        if (added > 0) dao.update(c.copy(updatedAt = now))
        added
    }

    /** Виртуальная серия / глава файла как элемент коллекции. */
    suspend fun addChapter(collectionId: Long, mediaId: Long, chapterId: Long, season: Int = 0, episode: Int = 0): Long {
        val pos = dao.maxPosition(collectionId) + 1
        return dao.insertItem(CollectionItemEntity(collectionId = collectionId, mediaId = mediaId, chapterId = chapterId,
            position = pos, season = season, episode = episode, addedAt = System.currentTimeMillis()))
    }

    suspend fun removeItem(itemId: Long) = dao.deleteItem(itemId)

    /** Ручной порядок внутри коллекции (в пределах того же сезона). */
    suspend fun moveItem(itemId: Long, delta: Int) = db.withTransaction {
        val item = dao.item(itemId) ?: return@withTransaction
        val list = dao.items(item.collectionId).filter { it.season == item.season }.toMutableList()
        val i = list.indexOfFirst { it.id == itemId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@withTransaction
        val a = list[i]; list[i] = list[j]; list[j] = a
        list.forEachIndexed { k, it -> if (it.position != k.toLong()) dao.updateItem(it.copy(position = k.toLong())) }
    }

    suspend fun setItemNumbers(itemId: Long, season: Int, episode: Int, title: String) {
        val item = dao.item(itemId) ?: return
        dao.updateItem(item.copy(season = season.coerceIn(0, 999), episode = episode.coerceIn(0, 99_999), title = title.trim()))
    }

    suspend fun setSeasonTitle(collectionId: Long, number: Int, title: String) {
        dao.upsertSeason(SeasonEntity(collectionId = collectionId, number = number, title = title.trim()))
    }

    // ---------------- встроенные списки ----------------

    suspend fun isIn(systemKey: String, mediaId: Long): Boolean {
        val c = dao.bySystemKey(systemKey) ?: return false
        return dao.contains(c.id, mediaId) > 0
    }

    /** «В избранное» / «Посмотреть позже» — переключатель. Возвращает новое состояние. */
    suspend fun toggleSystem(systemKey: String, mediaId: Long): Boolean {
        ensureDefaults()
        val c = dao.bySystemKey(systemKey) ?: return false
        val existing = dao.items(c.id).firstOrNull { it.mediaId == mediaId && it.chapterId == 0L }
        return if (existing != null) {
            dao.deleteItem(existing.id); false
        } else {
            addMedia(c.id, listOf(mediaId)); true
        }
    }

    // ---------------- ожидающие скачивания (плейлисты) ----------------

    /** Ролик источника, которого ещё нет на устройстве. Повтор не создаёт дубль. */
    suspend fun addPending(collectionId: Long, sourceUrl: String, sourceKey: String, title: String, position: Long,
                           unavailableReason: String = ""): Long {
        if (sourceKey.isNotBlank() && dao.items(collectionId).any { it.sourceKey == sourceKey }) return -1
        return dao.insertItem(CollectionItemEntity(collectionId = collectionId, sourceUrl = sourceUrl, sourceKey = sourceKey,
            title = title, position = position, unavailableReason = unavailableReason, addedAt = System.currentTimeMillis()))
    }

    /**
     * Видео скачано: ожидающие элементы с тем же источником получают файл;
     * если задание было поставлено «в коллекцию», связь создаётся на его месте
     * в порядке источника (не в порядке завершения).
     */
    suspend fun onDownloaded(mediaId: Long, sourceKey: String, collectionId: Long, position: Long, pageUrl: String = "") = db.withTransaction {
        val pending = LinkedHashMap<Long, CollectionItemEntity>()
        if (sourceKey.isNotBlank()) dao.itemsForSource(sourceKey).forEach { pending[it.id] = it }
        // Серия, файл которой удаляли раньше, узнаётся и по известной ссылке.
        if (pageUrl.isNotBlank()) dao.allItems().filter { it.mediaId == 0L && it.sourceUrl == pageUrl }.forEach { pending[it.id] = it }
        run {
            for (it in pending.values) {
                if (it.mediaId == 0L) {
                    val clash = dao.items(it.collectionId).any { o -> o.mediaId == mediaId && o.chapterId == 0L }
                    if (clash) dao.deleteItem(it.id) else dao.updateItem(it.copy(mediaId = mediaId, unavailableReason = ""))
                }
            }
        }
        if (collectionId > 0 && dao.get(collectionId) != null && dao.contains(collectionId, mediaId) == 0) {
            dao.insertItem(CollectionItemEntity(collectionId = collectionId, mediaId = mediaId, position = position,
                sourceKey = sourceKey, addedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Видео удалено с устройства: если источник известен, серия остаётся
     * в коллекции как «ожидает скачивания»; иначе связь убирается.
     */
    suspend fun onMediaDeleted(mediaId: Long) = db.withTransaction {
        for (it in dao.itemsForMedia(mediaId)) {
            if (it.chapterId == 0L && it.sourceUrl.isNotBlank()) {
                val key = it.sourceKey.ifBlank { "url:${it.sourceUrl}" }
                val dup = dao.items(it.collectionId).any { o -> o.id != it.id && o.mediaId == 0L && o.sourceKey == key }
                if (dup) dao.deleteItem(it.id) else dao.updateItem(it.copy(mediaId = 0, sourceKey = key))
            } else dao.deleteItem(it.id)
        }
    }

    companion object {
        private const val KEY_SEEDED = "collectionsSeeded"
    }
}
