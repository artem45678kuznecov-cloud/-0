@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.data.db.CollectionType
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.downloader.MediaMerger
import com.nox.offline.media.Bytes
import com.nox.offline.media.CachedReader
import com.nox.offline.media.ChannelByteSource
import com.nox.offline.media.CheckCancelled
import com.nox.offline.media.CheckControl
import com.nox.offline.media.ContainerCheck
import com.nox.offline.media.FileCheck
import com.nox.offline.media.FileChecks
import com.nox.offline.media.FileRepair
import com.nox.offline.media.Media3Probe
import com.nox.offline.media.Mp4Inspector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * «Проверить файл» → «Восстановить» на устройстве для записи медиатеки:
 * MP4 больше 4 ГБ с обернувшимися 32-битными смещениями (кадры настоящего
 * ролика из склейки MediaMuxer лежат поперёк границы 4 ГБ, остальное —
 * разреженная дыра). Проверяется: отмена не трогает ни оригинал, ни запись;
 * после восстановления тот же mediaId, позиция и коллекция на месте, новый
 * файл читается разборщиком плеера, оригинал побайтно тот же и не удалён.
 *
 * Тяжёлый (пишет ~4,3 ГБ): только с аргументом `nox.large=1`.
 */
@RunWith(AndroidJUnit4::class)
class FileRepairDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = NoxApp.get(ctx)
    private val four = 1L shl 32

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Склейка MediaMuxer (moov в конце, stco) → тот же ролик поперёк 4 ГБ со смещениями по модулю 2^32. */
    private fun bigWrapped(dir: File): File {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun asset(name: String) = File(dir, name).also { f -> assets.open("media/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }
        val small = File(dir, "small.mp4").apply { delete() }
        runBlocking { MediaMerger.merge(asset("video_only.mp4"), asset("audio_only.m4a"), small) }
        val src = ChannelByteSource.of(small)
        val insp = Mp4Inspector(CachedReader(src), CheckControl.NONE)
        insp.inspect(-1)
        val idx = insp.lastIndex!!
        val ftyp = idx.top.first { it.type == "ftyp" }
        val region = idx.dataRegions.single()
        val payload = ByteArray((region.last + 1 - region.first).toInt()).also { src.read(region.first, it) }
        val ftypBytes = ByteArray(ftyp.size.toInt()).also { src.read(ftyp.pos, it) }
        src.close()
        val x = four - payload.size / 2
        val moov = idx.moovBytes.copyOf()
        val m = Bytes(moov)
        for (t in idx.tracks) {
            assertFalse("склейка малого ролика должна дать stco", t.co64)
            val box = t.chunkBoxOffsetInMoov
            val count = m.u32(box + 12).toInt()
            for (i in 0 until count) {
                val v = (m.u32(box + 16 + 4 * i) - region.first + x) and 0xFFFFFFFFL
                for (k in 0 until 4) moov[box + 16 + 4 * i + k] = (v shr (24 - 8 * k)).toByte()
            }
        }
        val out = File(dir, "Большой фильм [t4g].mp4").apply { delete() }
        RandomAccessFile(out, "rw").use { f ->
            f.write(ftypBytes)
            f.writeInt(1); f.writeBytes("mdat"); f.writeLong(x + payload.size - ftypBytes.size)
            f.seek(x); f.write(payload)
            f.write(moov)
        }
        return out
    }

    /** Отмена, когда временный результат вырос до [cancelAtBytes], — то есть посреди записи. */
    private class Ctl(private val dir: File, private val cancelAtBytes: Long) : CheckControl {
        override fun check() {
            if (dir.listFiles()!!.any { it.name.endsWith(".nox-repair") && it.length() >= cancelAtBytes }) throw CheckCancelled()
        }
        override fun progress(fraction: Float) {}
    }

    @Test fun cancelKeepsEverythingThenRepairKeepsRecordAndOriginal(): Unit = runBlocking {
        assumeTrue("тяжёлый тест — только по запросу (nox.large=1)",
            InstrumentationRegistry.getArguments().getString("nox.large") == "1")
        val dir = File(ctx.filesDir, "repair-test").apply { deleteRecursively(); mkdirs() }
        val db = app.db
        var id = 0L
        try {
            val big = bigWrapped(dir)
            assumeTrue("нужно ~4,4 ГБ свободного места", dir.usableSpace > big.length() + 200_000_000)
            id = db.media().insert(MediaEntity(title = "Большой фильм", filePath = big.absolutePath, sizeBytes = big.length(),
                quality = "360p", height = 360, width = 640, durationSec = 4, createdAt = System.currentTimeMillis(),
                container = "mp4", codecs = "H.264 + AAC", kind = "video", imported = true))
            db.playback().upsert(PlaybackEntity(id, 2_000, 4_000, System.currentTimeMillis()))
            val col = app.library.create("Проверка восстановления", CollectionType.ALBUM)
            app.library.addMedia(col, listOf(id))
            val m = db.media().get(id)!!
            val shaBefore = sha(big)

            // 1. Проверка: нарушение структуры, предложена пересборка индекса.
            val check = FileChecks.check(app, m, CheckControl.NONE)
            assertEquals(check.report(), FileCheck.Verdict.STRUCTURE, check.verdict)
            assertEquals(FileCheck.Repair.MP4_REBUILD_INDEX, check.repair)

            // 2. Отмена посреди записи: оригинал, запись и папка как были.
            val cancelled = FileRepair(app).rebuildMp4(m, Ctl(dir, 1_000_000_000))
            assertTrue("$cancelled", cancelled is FileRepair.Outcome.Failed && cancelled.reason.contains("отменено"))
            assertEquals(m, db.media().get(id))
            assertEquals(listOf(big.name, "audio_only.m4a", "small.mp4", "video_only.mp4").sorted(), dir.list()!!.sorted())

            // 3. Восстановление целиком.
            val done = FileRepair(app).rebuildMp4(m, CheckControl.NONE)
            assertTrue("$done", done is FileRepair.Outcome.Done)
            val after = db.media().get(id)!!
            val rebuilt = File(after.filePath)
            assertEquals("Большой фильм [t4g] (восстановлено).mp4", rebuilt.name)
            assertEquals(rebuilt.length(), after.sizeBytes)
            assertTrue(rebuilt.length() > four)
            assertEquals(2_000L, db.playback().get(id)!!.positionMs)
            assertTrue("коллекция сохранена", db.collections().itemsForMedia(id).any { it.collectionId == col })
            val c2 = ChannelByteSource.of(rebuilt).use { ContainerCheck.check(it) }
            assertEquals(c2.report(), FileCheck.Verdict.READABLE, c2.verdict)
            val probe = Media3Probe.run(ctx, Uri.fromFile(rebuilt), ContainerCheck.Kind.MP4, 2_000, CheckControl.NONE)
            assertTrue("${probe.failure} ${probe.lines}", probe.ok)

            // 4. Оригинал на месте и побайтно тот же; «удалить исходный» его и укажет.
            assertTrue(big.exists())
            assertEquals(shaBefore, sha(big))
            assertEquals(false to big.absolutePath, FileRepair(app).originalOf(id))
            assertFalse("временных файлов нет", dir.list()!!.any { it.contains("nox-repair") })
        } finally {
            if (id > 0) {
                db.collections().deleteItemsForMedia(id)
                db.playback().delete(id)
                db.media().get(id)?.let { db.media().delete(it) }
                File(ctx.filesDir, "file-checks/$id.original.txt").delete()
                File(ctx.filesDir, "file-checks/$id.txt").delete()
            }
            app.db.collections().let { dao -> dao.getAll().filter { it.title == "Проверка восстановления" }.forEach { dao.delete(it.id) } }
            dir.deleteRecursively()
        }
    }
}
