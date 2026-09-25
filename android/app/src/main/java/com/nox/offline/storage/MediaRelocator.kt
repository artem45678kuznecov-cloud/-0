package com.nox.offline.storage

import com.nox.offline.core.MediaTypes
import com.nox.offline.core.AppEvents
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.NoxDatabase
import com.nox.offline.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Перенос готовых видео из NOX в папку пользователя (SAF).
 *
 * Порядок всегда один: копия под временным именем → проверка размера →
 * переименование → запись нового адреса в базу → и только потом удаление
 * исходника. Любой сбой (отозван доступ, извлечена карта, провайдер
 * ответил ошибкой) оставляет видео на старом месте целым.
 *
 * Работает в собственном однопоточном бюджете: переносы не соревнуются
 * друг с другом и не занимают сетевые слоты загрузчика.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRelocator(
    private val db: NoxDatabase,
    private val saf: SafStore,
    private val settings: AppSettings,
) {
    private val budget = Dispatchers.IO.limitedParallelism(1)

    sealed class Outcome {
        object Moved : Outcome()
        data class Skipped(val reason: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    suspend fun relocate(mediaId: Long, tree: String = settings.downloads.value.destinationTree,
                         onProgress: (Long, Long) -> Unit = { _, _ -> }): Outcome = withContext(budget) {
        val m = db.media().get(mediaId) ?: return@withContext Outcome.Skipped("нет в медиатеке")
        if (tree.isBlank()) return@withContext clear(m, Outcome.Skipped("папка не выбрана"))
        if (m.isExternal) return@withContext clear(m, Outcome.Skipped("уже в папке"))
        if (PlaybackRegistry.playingMediaId == mediaId) {
            // Файл сейчас читает плеер: переносим позже, отметка остаётся.
            return@withContext Outcome.Skipped("видео открыто в плеере")
        }
        if (!saf.isWritable(tree)) {
            NoxLog.event("move-no-access", "media" to mediaId)
            return@withContext clear(m, Outcome.Failed("нет доступа к папке — видео осталось в NOX"))
        }
        val file = File(m.filePath)
        if (!file.exists()) return@withContext clear(m, Outcome.Failed("файл не найден"))
        try {
            val uri = saf.copyInto(tree, file, file.name, MediaTypes.mimeForName(file.name), onProgress)
            db.media().update(m.copy(contentUri = uri.toString(), filePath = "", moveState = ""))
            if (!file.delete()) NoxLog.event("move-delete-source-failed", "media" to mediaId)
            NoxLog.event("move-done", "media" to mediaId, "size" to file.length())
            Outcome.Moved
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            NoxLog.event("move-failed", "media" to mediaId, "error" to "${t.javaClass.simpleName}: ${t.message?.take(80)}")
            clear(m, Outcome.Failed(t.message ?: "ошибка копирования"))
        }
    }

    private suspend fun clear(m: MediaEntity, o: Outcome): Outcome {
        if (m.moveState.isNotEmpty()) db.media().update(m.copy(moveState = ""))
        return o
    }

    /** Новое готовое видео: отметить и перенести, если папка выбрана. */
    suspend fun afterDownload(mediaId: Long) {
        val tree = settings.downloads.value.destinationTree
        if (tree.isBlank()) return
        db.media().get(mediaId)?.let { db.media().update(it.copy(moveState = "pending")) }
        when (val o = relocate(mediaId, tree)) {
            is Outcome.Failed -> AppEvents.notice("Видео осталось в NOX: ${o.reason}")
            else -> Unit
        }
    }

    /** После перезапуска: доделать прерванные переносы. */
    suspend fun resumePending() {
        for (m in db.media().pendingMoves()) relocate(m.id)
    }

    /** Отдельное подтверждённое перемещение уже готовой медиатеки. */
    suspend fun relocateAll(onItem: (done: Int, total: Int, current: String) -> Unit): Pair<Int, Int> {
        val tree = settings.downloads.value.destinationTree
        val items = db.media().getAll().filter { !it.isExternal }
        var moved = 0
        var failed = 0
        items.forEachIndexed { i, m ->
            onItem(i, items.size, m.title)
            when (relocate(m.id, tree)) {
                Outcome.Moved -> moved++
                is Outcome.Failed -> failed++
                is Outcome.Skipped -> Unit
            }
        }
        onItem(items.size, items.size, "")
        return moved to failed
    }
}
