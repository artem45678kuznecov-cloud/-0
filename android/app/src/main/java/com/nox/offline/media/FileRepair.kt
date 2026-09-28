package com.nox.offline.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.storage.FileOps
import com.nox.offline.storage.MediaLocator
import com.nox.offline.storage.PlaybackRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * «Восстановить»: пересборка индекса MP4 в НОВЫЙ файл. Только если проверка
 * нашла именно то, что этот способ чинит ([FileCheck.Repair.MP4_REBUILD_INDEX]).
 *
 * Порядок: оригинал открывается только на чтение → результат пишется во
 * временный файл рядом (или во временный документ той же папки SAF) →
 * проверка результата: структура, разборщик плеера в начале/середине/конце и
 * выборочное побайтное сравнение кадров с оригиналом → переименование →
 * запись медиатеки (тот же mediaId: коллекции, позиции, главы, субтитры
 * остаются) указывает на новый файл. Оригинал НЕ удаляется: его путь
 * запоминается, удалить его можно отдельно и только по решению человека.
 * Отмена или сбой на любом шаге удаляют лишь временный результат.
 */
@UnstableApi
class FileRepair(private val app: NoxApp) {
    sealed class Outcome {
        data class Done(val newName: String, val original: String, val details: List<String>) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    suspend fun rebuildMp4(m: MediaEntity, ctl: CheckControl): Outcome = withContext(Dispatchers.IO) {
        if (PlaybackRegistry.playingMediaId == m.id) return@withContext Outcome.Failed("видео открыто в плеере — закройте его и повторите")
        val src = try { FileChecks.open(app, m) } catch (t: Throwable) {
            return@withContext Outcome.Failed("файл не открывается на чтение: ${t.message}")
        }
        src.use { s ->
            val insp = Mp4Inspector(CachedReader(s), ctl)
            val check = insp.inspect(FileChecks.exactSize(app, m))
            val index = insp.lastIndex
            if (check.repair != FileCheck.Repair.MP4_REBUILD_INDEX || index == null) {
                return@withContext Outcome.Failed("этот способ здесь не поможет: ${check.summary}")
            }
            val rb = Mp4Rebuild(s, index)
            val need = rb.outputSize()
            val base = MediaLocator.fileName(m).substringBeforeLast('.')
            val newName = "$base (восстановлено).mp4"
            val details = ArrayList<String>(rb.notes)
            val phase = object : CheckControl {
                override fun check() = ctl.check()
                override fun progress(fraction: Float) = ctl.progress(fraction * 0.85f)
            }
            val verify: suspend (Uri) -> String? = { uri -> verifyResult(uri, s, index, details, ctl) }
            val tree = app.settings.downloads.value.destinationTree
            val result: Pair<String, Uri> = try {
                if (m.isExternal && tree.isNotBlank() && app.saf.isWritable(tree)) {
                    val uri = app.saf.writeNew(tree, newName, "video/mp4", need, write = { out -> rb.write(out, phase) }, verify = verify)
                    newName to uri
                } else {
                    // Рядом с оригиналом внутри NOX; для SAF без доступа — в хранилище NOX.
                    val dir = if (m.isExternal) app.storage.media else File(m.filePath).parentFile ?: app.storage.media
                    FileOps.requireSpace(need, dir.usableSpace)
                    val tmp = File(dir, ".$base.nox-repair")
                    try {
                        FileOutputStream(tmp).use { fos ->
                            fos.buffered(1 shl 20).let { bos -> rb.write(bos, phase); bos.flush() }
                            fos.fd.sync()
                        }
                        if (tmp.length() != need) throw java.io.IOException("размер записанного ${tmp.length()} из $need")
                        verify(Uri.fromFile(tmp))?.let { throw java.io.IOException("проверка результата не пройдена: $it") }
                        val final = unique(dir, newName)
                        if (!tmp.renameTo(final)) throw java.io.IOException("не удалось переименовать результат")
                        final.name to Uri.fromFile(final)
                    } finally {
                        if (tmp.exists()) tmp.delete()
                    }
                }
            } catch (c: CheckCancelled) {
                NoxLog.event("repair-cancelled", "media" to m.id)
                return@withContext Outcome.Failed("восстановление отменено — оригинал не менялся")
            } catch (e: FileOps.NotEnoughSpace) {
                return@withContext Outcome.Failed(e.message ?: "недостаточно места")
            } catch (t: Throwable) {
                NoxLog.event("repair-failed", "media" to m.id, "error" to "${t.javaClass.simpleName}: ${t.message?.take(120)}")
                return@withContext Outcome.Failed(t.message ?: t.javaClass.simpleName)
            }
            val (name, uri) = result
            val original = if (m.isExternal) m.contentUri else m.filePath
            val updated = if (uri.scheme == "file") m.copy(filePath = uri.path.orEmpty(), contentUri = "", sizeBytes = need, container = "mp4")
            else m.copy(filePath = "", contentUri = uri.toString(), sizeBytes = need, container = "mp4")
            app.db.media().update(updated)
            runCatching {
                File(File(app.filesDir, "file-checks").apply { mkdirs() }, "${m.id}.original.txt")
                    .writeText("${if (m.isExternal) "saf" else "file"}\n$original\n${MediaLocator.fileName(m)}\n")
            }
            NoxLog.event("repair-done", "media" to m.id, "size" to need, "external" to (uri.scheme != "file"))
            ctl.progress(1f)
            Outcome.Done(name, MediaLocator.fileName(m), details)
        }
    }

    /** Результат должен читаться и совпадать с оригиналом кадр в кадр (выборочно). */
    private fun verifyResult(uri: Uri, original: ByteSource, index: Mp4Inspector.Index, details: MutableList<String>, ctl: CheckControl): String? {
        val out = if (uri.scheme == "file") ChannelByteSource.of(File(uri.path!!)) else {
            val pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return "результат не открывается"
            val s = ChannelByteSource.of(pfd.fileDescriptor)
            object : ByteSource by s { override fun close() { s.close(); pfd.close() } }
        }
        out.use { o ->
            val insp = Mp4Inspector(CachedReader(o), ctl)
            val c = insp.inspect(-1)
            if (c.verdict != FileCheck.Verdict.READABLE) return "структура результата: ${c.summary}"
            val ri = insp.lastIndex ?: return "у результата нет индекса"
            if (ri.tracks.size != index.tracks.size) return "в результате ${ri.tracks.size} дорожек вместо ${index.tracks.size}"
            var compared = 0
            for (t in index.tracks.indices) {
                val a = index.tracks[t]
                val b = ri.tracks[t]
                if (a.sampleCount != b.sampleCount || a.codec != b.codec) return "дорожка ${t + 1} не совпадает с оригиналом"
                val step = maxOf(1, a.sampleCount / 200)
                val wa = Mp4Inspector.SampleWalk(a, index.dataRegions)
                val wb = Mp4Inspector.SampleWalk(b, ri.dataRegions)
                var i = 0
                while (wa.next() && wb.next()) {
                    if (i % step == 0 || i == a.sampleCount - 1) {
                        ctl.check()
                        if (wa.size != wb.size) return "кадр ${i + 1} дорожки ${t + 1}: другой размер"
                        val x = ByteArray(minOf(wa.size, 4096)).also { original.read(wa.offset, it) }
                        val y = ByteArray(x.size).also { o.read(wb.offset, it) }
                        if (!x.contentEquals(y)) return "кадр ${i + 1} дорожки ${t + 1} не совпадает с оригиналом"
                        compared++
                    }
                    i++
                }
            }
            val probe = Media3Probe.run(app, uri, ContainerCheck.Kind.MP4, 0, ctl)
            details.addAll(probe.lines)
            if (!probe.ok) return "разборщик плеера: ${probe.failure}"
            details.add("сравнено с оригиналом кадров: $compared (выборочно по всей длине, первые до 4 КБ каждого)")
            return null
        }
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 2
        val stem = name.substringBeforeLast('.')
        while (f.exists()) f = File(dir, "$stem ($n).mp4").also { n++ }
        return f
    }

    /** Где лежит оригинал после восстановления (для «Удалить исходный файл»), или null. */
    fun originalOf(mediaId: Long): Pair<Boolean, String>? = runCatching {
        val l = File(app.filesDir, "file-checks/$mediaId.original.txt").readLines()
        (l[0] == "saf") to l[1]
    }.getOrNull()

    /** Удалить оригинал — только по явному решению человека, и никогда не текущий файл записи. */
    suspend fun deleteOriginal(m: MediaEntity): Boolean = withContext(Dispatchers.IO) {
        val (saf, where) = originalOf(m.id) ?: return@withContext false
        if (where == m.filePath || where == m.contentUri) return@withContext false
        val ok = if (saf) app.saf.delete(where) else File(where).let { !it.exists() || it.delete() }
        if (ok) File(app.filesDir, "file-checks/${m.id}.original.txt").delete()
        NoxLog.event("repair-original-deleted", "media" to m.id, "ok" to ok)
        ok
    }
}
