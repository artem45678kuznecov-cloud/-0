package com.nox.offline

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.data.db.ChapterEntity
import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.CollectionType
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.settings.GlassMode
import com.nox.offline.settings.WallpaperKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Обновление поверх опубликованной версии с данными пользователя.
 *
 * Запускается скриптом выпуска (tools/ci-upgrade-check.sh) на эмуляторе в два
 * приёма: `nox.upgrade=seed` — внутри установленной опубликованной версии
 * (0.4.3) создаются медиатека, позиции, главы и серии, коллекция, субтитры,
 * закладка, недокачанная загрузка с файлом .part, оформление и фон, и
 * записывается снимок; затем поверх ставится новый подписанный APK
 * (`adb install -r`), и `nox.upgrade=verify` — уже внутри новой версии —
 * сверяет всё со снимком. Без аргумента не запускается.
 *
 * Код засева пользуется только тем, что есть и в 0.4.3: база, хранилище,
 * библиотека, разметка, субтитры, настройки.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeDataDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = instr.targetContext
    private val app get() = NoxApp.get(ctx)
    private fun arg(k: String): String? = InstrumentationRegistry.getArguments().getString(k)
    private val dir get() = File(ctx.getExternalFilesDir(null), "upgrade-check").apply { mkdirs() }

    private fun say(s: String) = s.lines().forEach { Log.i("NOX-TEST", "upgrade: $it") }

    @Test
    fun seed(): Unit = runBlocking {
        assumeTrue("только из скрипта выпуска", arg("nox.upgrade") == "seed")
        val db = app.db
        val now = System.currentTimeMillis()
        val clip = instr.context.assets.open("media/marathon-clip.mp4").use { it.readBytes() }

        // Скачанный (готовый) файл и его позиция.
        val video = File(app.storage.media, "Проверка обновления — клип.mp4").apply { writeBytes(clip) }
        val mediaId = db.media().insert(MediaEntity(title = "Проверка обновления — клип", filePath = video.absolutePath,
            sizeBytes = video.length(), quality = "720p", height = 720, width = 1280, durationSec = 8, createdAt = now,
            container = "mp4", codecs = "H.264 + AAC", kind = "video", imported = true, uploader = "Проверка обновления"))
        db.playback().upsert(PlaybackEntity(mediaId, 5_432, 8_000, now, false))

        // Главы, серия, её прогресс, закладка, субтитры.
        val chapters = db.chapters()
        chapters.insert(ChapterEntity(mediaId = mediaId, title = "Глава: начало", startMs = 0, kind = ChapterKind.CHAPTER, createdAt = now))
        val ep = chapters.insert(ChapterEntity(mediaId = mediaId, title = "Серия 2", startMs = 4_000, endMs = 8_000,
            kind = ChapterKind.EPISODE, createdAt = now + 1))
        app.markup.saveProgress(ep, 1_500, false)
        app.markup.addBookmark(mediaId, 3_000, "Закладка проверки", "до обновления")
        val sub = app.subtitles.save(mediaId, "1\n00:00:01,000 --> 00:00:03,000\nСубтитр до обновления\n", "Русские", "ru", "user")
        db.media().setSubtitle(mediaId, sub.id)

        // Коллекция.
        app.library.ensureDefaults()
        val col = app.library.create("Проверка обновления", CollectionType.ALBUM, "Коллекция до обновления", "проверка")
        app.library.addMedia(col, listOf(mediaId))

        // Недокачанная загрузка на паузе: запись и файл .part.
        val dl = db.downloads().insert(DownloadEntity(pageUrl = "https://example.invalid/watch?v=upgrade", quality = "720p",
            title = "Недокачанное видео", videoId = "upgrade", fileName = "Недокачанное видео.mp4", totalBytes = 1_000_000,
            downloadedBytes = 300_000, status = DownloadStatus.PAUSED, createdAt = now, updatedAt = now))
        val part = app.coordinator.partFileOf(db.downloads().get(dl)!!)
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(300_000) { (it % 251).toByte() })

        // Оформление и фон.
        app.settings.updateAppearance { it.copy(glassMode = GlassMode.ECONOMY, wallpaper = WallpaperKind.AURORA, customHue = 30f, dim = 0.6f) }
        Thread.sleep(500)

        val snap = snapshot()
        File(dir, "before.txt").writeText(snap)
        say("засеяно: видео $mediaId, серия $ep, коллекция $col, загрузка $dl (.part ${part.length()} байт)\n$snap")
    }

    @Test
    fun verify(): Unit = runBlocking {
        assumeTrue("только из скрипта выпуска", arg("nox.upgrade") == "verify")
        val before = File(dir, "before.txt").readText()
        val after = snapshot()
        File(dir, "after.txt").writeText(after)
        val b = sections(before)
        val a = sections(after)
        val problems = ArrayList<String>()

        val pb = kv(b.getValue("package"))
        val pa = kv(a.getValue("package"))
        say("версия до: ${pb["versionName"]} (${pb["versionCode"]}), после: ${pa["versionName"]} (${pa["versionCode"]})")
        if (pa.getValue("versionCode").toLong() <= pb.getValue("versionCode").toLong()) problems += "versionCode не вырос"
        if (pa["firstInstallTime"] != pb["firstInstallTime"]) problems += "firstInstallTime изменился — это переустановка, а не обновление"
        if (pa["cert"] != pb["cert"]) problems += "сертификат подписи изменился"
        if (pa["applicationId"] != pb["applicationId"]) problems += "applicationId изменился"

        for (name in b.keys - "package") {
            val x = b.getValue(name)
            val y = a[name]
            when {
                y == null -> problems += "раздел $name пропал"
                name == "prefs nox_settings" -> {
                    val kb = kv(x)
                    val ka = kv(y)
                    for ((k, v) in kb) if (ka[k] != v) problems += "настройка $k: было «$v», стало «${ka[k]}»"
                }
                else -> {
                    // Всё, что было, должно остаться как было; новое (например, автокопия) — только в отчёт.
                    val lb = x.lines().filter { it.isNotEmpty() }
                    val la = y.lines().filter { it.isNotEmpty() }.toSet()
                    lb.filter { it !in la }.forEach { problems += "$name: пропало или изменилось: ${it.take(600)}" }
                    val added = la - lb.toSet()
                    if (added.isNotEmpty()) say("$name: добавилось после обновления: ${added.joinToString("; ") { it.take(200) }}")
                }
            }
        }
        say("сверка после обновления: ${if (problems.isEmpty()) "всё сохранилось" else problems.joinToString("\n")}")
        say(after)
        assertTrue("после обновления: $problems", problems.isEmpty())
    }

    // ------------------------------------------------------------------
    //  Снимок данных
    // ------------------------------------------------------------------

    private fun snapshot(): String = buildString {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(ctx.packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val cert = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { sha(it) } else null
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        append("=== package\n")
        append("applicationId=${ctx.packageName}\nversionName=${info.versionName}\nversionCode=$code\n")
        append("firstInstallTime=${info.firstInstallTime}\ncert=$cert\n")

        append("=== prefs nox_settings\n")
        ctx.getSharedPreferences("nox_settings", Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) -> append("$k=$v\n") }

        val sql = app.db.openHelper.readableDatabase
        val tables = ArrayList<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c -> while (c.moveToNext()) tables += c.getString(0) }
        for (t in tables) {
            if (t in setOf("android_metadata", "room_master_table", "sqlite_sequence")) continue
            append("=== table $t\n")
            sql.query("SELECT * FROM `$t` ORDER BY rowid").use { c ->
                while (c.moveToNext()) append((0 until c.columnCount).joinToString(" | ") { "${c.getColumnName(it)}=${value(c, it)}" }).append('\n')
            }
        }

        append("=== files\n")
        val root = app.storage.root
        root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            append("${f.relativeTo(root)} ${f.length()} ${sha(f.readBytes())}\n")
        }

        append("=== saf\n")
        ctx.contentResolver.persistedUriPermissions.sortedBy { it.uri.toString() }.forEach {
            append("${it.uri} read=${it.isReadPermission} write=${it.isWritePermission}\n")
        }
    }

    private fun value(c: Cursor, i: Int): String = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> "null"
        Cursor.FIELD_TYPE_BLOB -> sha(c.getBlob(i))
        else -> c.getString(i)
    }

    private fun sections(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var name = ""
        val body = StringBuilder()
        for (line in s.lines()) {
            if (line.startsWith("=== ")) {
                if (name.isNotEmpty()) out[name] = body.toString()
                name = line.removePrefix("=== ")
                body.clear()
            } else if (line.isNotEmpty()) body.append(line).append('\n')
        }
        if (name.isNotEmpty()) out[name] = body.toString()
        return out
    }

    private fun kv(s: String) = s.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
