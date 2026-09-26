package com.nox.offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.CollectionType
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.downloader.Quality
import com.nox.offline.downloader.TransferScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ДЕМО-данные для снимков экрана — только в отладочной сборке
 * (com.nox.offline.debug) и только по явному запуску этого класса.
 * Видео синтетические (цветные сцены с тоном), обложки процедурные;
 * описание каждой коллекции прямо говорит, что это демо. В релизной
 * сборке ничего этого нет.
 *
 * Файлы кладёт стенд: <external files>/demo-seed/ (видео, обложки, srt).
 * Очередь: адреса локального стенда http://10.0.2.2:8766/queue/… .
 */
@RunWith(AndroidJUnit4::class)
class DemoLibrarySeed {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = NoxApp.get(ctx)

    @Test fun seed(): Unit = runBlocking {
        assumeTrue("только отладочная сборка", BuildConfig.DEBUG)
        val src = File(ctx.getExternalFilesDir(null), "demo-seed")
        assumeTrue("нет файлов стенда в ${src.absolutePath}", src.isDirectory)
        val db = app.db
        if (db.media().getAll().any { it.uploader == DEMO }) return@runBlocking   // уже посеяно

        fun cover(name: String): String {
            val f = File(src, "cover_$name.jpg")
            val out = File(app.storage.covers, "demo-$name.jpg")
            f.copyTo(out, overwrite = true)
            return out.absolutePath
        }
        suspend fun media(file: String, title: String, coverName: String, durationSec: Long, kind: String = "video"): Long {
            val f = File(src, file)
            val out = File(app.storage.media, "Демо — $file")
            f.copyTo(out, overwrite = true)
            return db.media().insert(MediaEntity(title = title, filePath = out.absolutePath, sizeBytes = out.length(),
                quality = if (kind == "audio") "" else "360p", height = if (kind == "audio") 0 else 360, width = if (kind == "audio") 0 else 640,
                durationSec = durationSec, coverPath = cover(coverName), createdAt = System.currentTimeMillis(), uploader = DEMO,
                container = out.extension, codecs = if (kind == "audio") "AAC" else "H.264 + AAC", kind = kind, fps = 24, imported = true))
        }

        val ep5 = media("ep5.mp4", "Клинки рассвета S02E05 — Тренировка в деревне", "fire_red", 95)
        val ep6 = media("ep6.mp4", "Клинки рассвета S02E06 — Демоны в деревне", "sunset_city", 92)
        val ep7 = media("ep7.mp4", "Клинки рассвета S02E07 — Секрет кузнецов", "blue_night", 90)
        val ep8 = media("ep8.mp4", "Клинки рассвета S02E08 — Неожиданный гость", "reading_room", 88)
        val roofs = media("film_roofs.mp4", "Бегущий по крышам", "sunset_city", 70)
        val skeleton = media("skeleton.mp4", "Рыцарь-скелет, 1 серия", "dark_helmet", 60)
        val sky = media("sky_heart.mp4", "Твоё небо", "heart_lake", 75)
        val storm = media("storm.mp4", "Магия грозы", "violet_storm", 50)
        val autumn = media("autumn.mp4", "Осеннее настроение", "autumn_gate", 55)
        val space = media("space.mp4", "Космос", "space", 45)
        val marathon = media("marathon.mp4", "Ночной марафон — 3 серии одним файлом", "moon_torii", 360)
        val sound = media("soundtrack.m4a", "Саундтрек (только звук)", "white_hair", 120, kind = "audio")

        val lib = app.library
        lib.ensureDefaults()
        val c = db.collections()
        suspend fun col(title: String, type: String, desc: String, tags: String, coverName: String, pinned: Boolean, ids: List<Long>): Long {
            val id = lib.create(title, type, desc, tags)
            c.update(c.get(id)!!.copy(coverPath = cover(coverName), pinned = pinned))
            lib.addMedia(id, ids, season = if (type == CollectionType.SERIES) 2 else 0)
            return id
        }
        val demoNote = "Демонстрационные данные отладочной сборки."
        col("Аниме — Мой мир", CollectionType.ALBUM, "Любимые истории, которые всегда со мной. $demoNote", "Приключения, Фэнтези, Драма",
            "fire_red", false, listOf(ep5, ep6, ep7, ep8, skeleton, marathon))
        val series = col("Клинки рассвета", CollectionType.SERIES, demoNote, "Приключения", "fire_red", false, listOf(ep5, ep6, ep7, ep8))
        lib.setSeasonTitle(series, 2, "Тренировка в деревне кузнецов")
        col("Магия грозы", CollectionType.ALBUM, demoNote, "", "white_hair", true, listOf(storm))
        col("Осеннее настроение", CollectionType.ALBUM, demoNote, "", "autumn_gate", true, listOf(autumn))
        col("Космос", CollectionType.ALBUM, demoNote, "", "space", true, listOf(space))
        col("Аниме — Атмосфера", CollectionType.ALBUM, demoNote, "", "violet_storm", false, listOf(storm, sky, marathon))
        col("AMV / Клипы", CollectionType.ALBUM, demoNote, "", "reading_room", false, listOf(sound, storm))
        col("Красивые кадры", CollectionType.ALBUM, demoNote, "", "pink_mountains", false, listOf(autumn, space, sky))
        col("Киберпанк", CollectionType.ALBUM, demoNote, "", "cyber", false, listOf(roofs))
        col("Япония", CollectionType.ALBUM, demoNote, "", "sakura", false, listOf(autumn))

        // Метки из коллекции по умолчанию и встроенные списки.
        for ((key, ids) in listOf("anime" to listOf(ep5, ep6, ep7, ep8, skeleton, marathon), "movies" to listOf(roofs, sky),
            "series" to listOf(ep5, ep6, ep7, ep8))) {
            c.bySystemKey(key)?.let { lib.addMedia(it.id, ids) }
        }
        c.bySystemKey(CollectionType.FAVORITES)?.let { lib.addMedia(it.id, listOf(ep5, sky)) }
        c.bySystemKey(CollectionType.WATCH_LATER)?.let { lib.addMedia(it.id, listOf(roofs)) }
        for ((key, name) in listOf("anime" to "fire_red", "movies" to "dark_helmet", "series" to "blue_night",
            CollectionType.FAVORITES to "heart_lake", CollectionType.WATCH_LATER to "moon_torii")) {
            c.bySystemKey(key)?.let { c.update(it.copy(coverPath = cover(name))) }
        }

        // Марафон одного файла: три виртуальные серии, файл не режется.
        val chapters = db.chapters()
        val e1 = chapters.insert(ChapterEntity(mediaId = marathon, title = "Серия 1 — Сумерки", startMs = 0, kind = ChapterKind.EPISODE, createdAt = 1))
        val e2 = chapters.insert(ChapterEntity(mediaId = marathon, title = "Серия 2 — Полночь", startMs = 120_000, kind = ChapterKind.EPISODE, createdAt = 2))
        val e3 = chapters.insert(ChapterEntity(mediaId = marathon, title = "Серия 3 — Рассвет", startMs = 240_000, kind = ChapterKind.EPISODE, createdAt = 3))
        val night = lib.create("Ночной марафон", CollectionType.SERIES, demoNote)
        c.update(c.get(night)!!.copy(coverPath = cover("moon_torii")))
        lib.addChapter(night, marathon, e1, 1, 1)
        lib.addChapter(night, marathon, e2, 1, 2)
        lib.addChapter(night, marathon, e3, 1, 3)
        app.markup.saveProgress(e1, 0, true)

        // Позиции: остановились на 5-й серии; «Твоё небо» досмотрено.
        val now = System.currentTimeMillis()
        db.playback().upsert(PlaybackEntity(ep5, 41_000, 95_000, now, false))
        db.playback().upsert(PlaybackEntity(sky, 0, 75_000, now - 60_000, true))
        db.playback().upsert(PlaybackEntity(roofs, 22_000, 70_000, now - 120_000, false))

        // Субтитры и закладка.
        File(src, "ep5.srt").takeIf { it.exists() }?.let { srt ->
            val s = app.subtitles.save(ep5, srt.readText(), "Русские", "ru", "user")
            db.media().setSubtitle(ep5, s.id)
        }
        app.markup.addBookmark(ep5, 30_000, "Начало тренировки", "Демо-закладка")

        // Очередь: настоящие передачи со стенда (медленный локальный сервер).
        val co = app.coordinator
        val a = co.enqueue("http://10.0.2.2:8766/queue/ep9.mp4", Quality.fromKey("720"), customTitle = "Клинки рассвета — 2 сезон, 9 серия")
        val b = co.enqueue("http://10.0.2.2:8766/queue/film.mp4", Quality.fromKey("720"), customTitle = "Бегущий по крышам 2")
        val p = co.enqueue("http://10.0.2.2:8766/queue/knight.mp4", Quality.fromKey("720"), customTitle = "Рыцарь-скелет, 2 серия")
        val done = co.enqueue("http://10.0.2.2:8766/queue/clip.mp4", Quality.fromKey("720"), customTitle = "Вечерний клип")
        for ((id, name) in listOf(a to "fire_red", b to "cyber", p to "dark_helmet", done to "heart_lake")) {
            id.getOrNull()?.let { jid -> db.downloads().get(jid)?.let { d -> db.downloads().update(d.copy(thumbnailUrl = cover(name))) } }
        }
        p.getOrNull()?.let { co.pause(it) }
        runCatching { TransferScheduler.ensureRunning(ctx) }
    }

    companion object {
        const val DEMO = "Демо"
    }
}
