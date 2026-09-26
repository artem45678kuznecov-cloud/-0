package com.nox.offline.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Миграции 1→2 и 2→3 на настоящем SQLite.
 *
 * База собирается ровно по схеме 1.json (снятой с кода v0.1.0),
 * заполняется данными, похожими на реальные, затем к ней применяются те
 * же команды, что выполнит Room на телефоне. Проверяется, что:
 *  - ни одна строка и ни одно старое значение не потеряны;
 *  - новые колонки получили безопасные значения по умолчанию;
 *  - итоговые таблицы совпадают со схемой 2.json колонка в колонку.
 */
class MigrationTest {
    private val schemaDir = File("schemas/com.nox.offline.data.db.NoxDatabase")

    private fun schema(version: Int): JSONObject =
        JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")

    private fun createV1(conn: Connection) = create(conn, 1)

    private fun create(conn: Connection, version: Int) {
        val db = schema(version)
        val entities = db.getJSONArray("entities")
        conn.createStatement().use { st ->
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                st.execute(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = db.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) st.execute(setup.getString(i))
        }
    }

    private fun fillV1(conn: Connection) {
        conn.createStatement().use { st ->
            // Готовое видео, пауза с .part, задание в очереди и сбой.
            st.execute("""INSERT INTO downloads (id,pageUrl,quality,title,videoId,formatId,resolvedUrl,headersJson,
                fileName,ext,height,totalBytes,downloadedBytes,status,error,speedBps,etaSec,retries,resolveRetries,
                thumbnailUrl,durationSec,lastStopReason,createdAt,updatedAt) VALUES
                (1,'https://m.vkvideo.ru/video-1_1','480','Повар-боец Сома','v1','url480','https://cdn/1?sig=x','{"User-Agent":"vk"}',
                 'Повар-боец Сома [v1].mp4','mp4',480,8000,8000,'COMPLETED','',0,-1,0,0,'https://img/1.jpg',1500,'',100,200),
                (2,'https://m.vkvideo.ru/video-1_2','720','Длинное видео','v2','url720','https://cdn/2?sig=y','{}',
                 'Длинное видео [v2].mp4','mp4',720,9000000,4500000,'PAUSED','',0,-1,0,0,'',3600,'',300,400),
                (3,'https://m.vkvideo.ru/video-1_3','MAX','','','','','{}','','mp4',0,0,0,'QUEUED','',0,-1,0,0,'',0,'',500,500),
                (4,'https://bad.example/x','360','','','','','{}','','mp4',0,0,0,'ERROR','нет формата',0,-1,0,3,'',0,'',600,700)""")
            st.execute("""INSERT INTO media (id,title,filePath,sizeBytes,quality,height,durationSec,coverPath,pageUrl,videoId,createdAt)
                VALUES (10,'Повар-боец Сома','/storage/emulated/0/Android/data/com.nox.offline/files/NOX/Media/Повар-боец Сома [v1].mp4',
                        8000,'480',480,1500,'/x/Covers/a.jpg','https://m.vkvideo.ru/video-1_1','v1',210),
                       (11,'Второе','/x/Media/b.mp4',123,'360',360,60,'','','',220)""")
            st.execute("INSERT INTO playback (mediaId,positionMs,durationMs,updatedAt) VALUES (10,600000,1500000,230),(11,59000,60000,240)")
        }
    }

    private fun columns(conn: Connection, table: String): Map<String, Triple<String, Boolean, String?>> {
        val out = linkedMapOf<String, Triple<String, Boolean, String?>>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    out[rs.getString("name")] = Triple(rs.getString("type"), rs.getInt("notnull") == 1, rs.getString("dflt_value"))
                }
            }
        }
        return out
    }

    @Test
    fun `migration keeps every v1 row and matches schema 2`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            createV1(conn)
            fillV1(conn)
            conn.createStatement().use { st -> for (sql in Migrations.SQL_1_2) st.execute(sql) }

            // 1) Старые данные на месте.
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM downloads").use { it.next(); assertEquals(4, it.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM media").use { it.next(); assertEquals(2, it.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM playback").use { it.next(); assertEquals(2, it.getInt(1)) }
                st.executeQuery("SELECT status, downloadedBytes, fileName, customTitle, mode, videoDone, allowSplit FROM downloads WHERE id=2").use {
                    it.next()
                    assertEquals("PAUSED", it.getString(1))
                    assertEquals(4_500_000L, it.getLong(2))
                    assertEquals("Длинное видео [v2].mp4", it.getString(3))
                    assertEquals("", it.getString(4))
                    assertEquals("progressive", it.getString(5))
                    assertEquals(0, it.getInt(6))
                    assertEquals(0, it.getInt(7))
                }
                st.executeQuery("SELECT title, filePath, contentUri, imported, moveState FROM media WHERE id=10").use {
                    it.next()
                    assertEquals("Повар-боец Сома", it.getString(1))
                    assertTrue(it.getString(2).endsWith("Повар-боец Сома [v1].mp4"))
                    assertEquals("", it.getString(3))
                    assertEquals(0, it.getInt(4))
                    assertEquals("", it.getString(5))
                }
                st.executeQuery("SELECT positionMs, completed FROM playback WHERE mediaId=10").use {
                    it.next()
                    assertEquals(600_000L, it.getLong(1))
                    assertEquals(0, it.getInt(2))
                }
                // Досмотренное в 0.1.0 (59 из 60 с) получает отметку «досмотрено».
                st.executeQuery("SELECT positionMs, completed FROM playback WHERE mediaId=11").use {
                    it.next()
                    assertEquals(59_000L, it.getLong(1))
                    assertEquals(1, it.getInt(2))
                }
            }

            // 2) Итоговые таблицы = схема 2.json.
            assertMatchesSchema(conn, 2)
        }
    }

    private fun assertMatchesSchema(conn: Connection, version: Int) {
        run {
            val entities = schema(version).getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                val actual = columns(conn, table)
                val fields = e.getJSONArray("fields")
                assertEquals("число колонок $table", fields.length(), actual.size)
                for (j in 0 until fields.length()) {
                    val f = fields.getJSONObject(j)
                    val name = f.getString("columnName")
                    val col = actual[name] ?: throw AssertionError("нет колонки $table.$name")
                    assertEquals("$table.$name type", f.getString("affinity"), col.first)
                    assertEquals("$table.$name notNull", f.getBoolean("notNull"), col.second)
                    if (f.has("defaultValue")) {
                        assertEquals("$table.$name default", f.getString("defaultValue"), col.third)
                    }
                }
            }
        }
    }

    @Test
    fun `schema 1 is the one shipped in v0_1_0`() {
        // Хэш схемы v0.1.0. Если он поменялся, значит 1.json переписали,
        // и миграция проверяется уже не против реальной базы пользователей.
        assertEquals("b7a7227454d528ff324bc39cb18e64b6", schema(1).getString("identityHash"))
        assertEquals(2, schema(2).getInt("version"))
    }

    @Test
    fun `schema 2 is the one shipped in 0_2_x`() {
        // База пользователей 0.2.0/0.2.1 — ровно эта схема.
        assertEquals("fa85ca565ff4b679bfbac6d7adb705a7", schema(2).getString("identityHash"))
        assertEquals(3, schema(3).getInt("version"))
    }

    @Test
    fun `schema 3 is the one shipped in 0_3_0 and the app is on 4`() {
        assertEquals("6258266b98ca2510807e95a749865e26", schema(3).getString("identityHash"))
        assertEquals(4, NoxDatabase.VERSION)
        assertEquals(4, schema(4).getInt("version"))
    }

    /** Заполненная база 0.2.1: готовое видео, пауза раздельных дорожек с частями, очередь MAX, сбой. */
    private fun fillV2(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("""INSERT INTO downloads (id,pageUrl,quality,title,videoId,formatId,resolvedUrl,headersJson,
                fileName,ext,height,totalBytes,downloadedBytes,status,error,speedBps,etaSec,retries,resolveRetries,
                thumbnailUrl,durationSec,lastStopReason,createdAt,updatedAt,customTitle,mode,audioUrl,audioHeadersJson,
                audioFormatId,videoTotalBytes,audioTotalBytes,videoDone,audioDone,uploader,allowSplit) VALUES
                (1,'https://vkvideo.ru/video-1_1','480','Готово','v1','url480','','{}','Готово [v1].mp4','mp4',480,8000,8000,
                 'COMPLETED','',0,-1,0,0,'',100,'',100,200,'','progressive','','{}','',0,0,0,0,'Канал',0),
                (2,'https://www.youtube.com/watch?v=abc','720','Раздельно','abc','136','https://rr/v?sig=1','{"User-Agent":"UA"}',
                 'Раздельно [abc].mp4','mp4',720,30000000,12000000,'PAUSED','',0,-1,0,0,'https://i/abc.jpg',600,'user',300,400,
                 'Моё название','split','https://rr/a?sig=2','{}','140',25000000,5000000,0,1,'Автор',1),
                (3,'https://vkvideo.ru/video-1_3','MAX','','','','','{}','','mp4',0,0,0,'QUEUED','',0,-1,0,0,'',0,'',500,500,
                 '','progressive','','{}','',0,0,0,0,'',0),
                (4,'https://bad.example/x','360','','','','','{}','','mp4',0,0,0,'ERROR','нет формата',0,-1,0,3,'',0,'',600,700,
                 '','progressive','','{}','',0,0,0,0,'',0)""")
            st.execute("""INSERT INTO media (id,title,filePath,sizeBytes,quality,height,durationSec,coverPath,pageUrl,videoId,createdAt,
                contentUri,uploader,imported,moveState) VALUES
                (10,'Готово','/x/Media/Готово [v1].mp4',8000,'480',480,100,'','https://vkvideo.ru/video-1_1','v1',210,'','Канал',0,''),
                (11,'Внешнее','',123,'MAX',1080,60,'','','',220,'content://tree/doc/1','',1,'')""")
            st.execute("INSERT INTO playback (mediaId,positionMs,durationMs,updatedAt,completed) VALUES (10,50000,100000,230,0),(11,60000,60000,240,1)")
        }
    }

    @Test
    fun `migration 2 to 3 keeps 0_2_1 jobs, parts state and positions`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, 2)
            fillV2(conn)
            conn.createStatement().use { st -> for (sql in Migrations.SQL_2_3) st.execute(sql) }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM downloads").use { it.next(); assertEquals(4, it.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM media").use { it.next(); assertEquals(2, it.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM playback").use { it.next(); assertEquals(2, it.getInt(1)) }
                // Пауза раздельных дорожек: format ID, готовность звука, адреса и имена частей — как были.
                st.executeQuery("""SELECT status, quality, formatId, audioFormatId, audioDone, videoDone, fileName, mode,
                    downloadedBytes, customTitle, planVersion, variantKey, container, errorKind, videoExact FROM downloads WHERE id=2""").use {
                    it.next()
                    assertEquals("PAUSED", it.getString(1))
                    assertEquals("720", it.getString(2))
                    assertEquals("136", it.getString(3))
                    assertEquals("140", it.getString(4))
                    assertEquals(1, it.getInt(5))
                    assertEquals(0, it.getInt(6))
                    assertEquals("Раздельно [abc].mp4", it.getString(7))
                    assertEquals("split", it.getString(8))
                    assertEquals(12_000_000L, it.getLong(9))
                    assertEquals("Моё название", it.getString(10))
                    assertEquals(0, it.getInt(11))          // задание 0.2.x
                    assertEquals("", it.getString(12))
                    assertEquals("", it.getString(13))
                    assertEquals("", it.getString(14))
                    assertEquals(0, it.getInt(15))
                }
                st.executeQuery("SELECT quality, planVersion FROM downloads WHERE id=3").use {
                    it.next(); assertEquals("MAX", it.getString(1)); assertEquals(0, it.getInt(2))
                }
                st.executeQuery("SELECT title, contentUri, imported, container, codecs FROM media WHERE id=11").use {
                    it.next()
                    assertEquals("Внешнее", it.getString(1))
                    assertEquals("content://tree/doc/1", it.getString(2))
                    assertEquals(1, it.getInt(3))
                    assertEquals("", it.getString(4))
                    assertEquals("", it.getString(5))
                }
                st.executeQuery("SELECT positionMs, completed FROM playback WHERE mediaId=10").use {
                    it.next(); assertEquals(50_000L, it.getLong(1)); assertEquals(0, it.getInt(2))
                }
            }
            assertMatchesSchema(conn, 3)
        }
    }

    /** Заполненная база 0.3.0: задание с точным планом на паузе, очередь, готовое видео, позиции. */
    private fun fillV3(conn: Connection) {
        fillV2(conn)
        conn.createStatement().use { st ->
            for (sql in Migrations.SQL_2_3) st.execute(sql)
            st.execute("""UPDATE downloads SET planVersion=1, extractorKey='Youtube', variantKey='v:308+a:251', container='webm',
                videoExact=1, audioExact=1, videoChunk=10485760 WHERE id=2""")
            st.execute("UPDATE media SET container='mp4', codecs='H.264 + AAC', variantKey='f:url480' WHERE id=10")
        }
    }

    @Test
    fun `migration 3 to 4 keeps 0_3_0 data and queue order`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, 2)
            fillV3(conn)
            conn.createStatement().use { st -> for (sql in Migrations.SQL_3_4) st.execute(sql) }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM downloads").use { it.next(); assertEquals(4, it.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM media").use { it.next(); assertEquals(2, it.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM playback").use { it.next(); assertEquals(2, it.getInt(1)) }
                // Задание 0.3.0 продолжается по сохранённым форматам, место в очереди — прежнее.
                st.executeQuery("""SELECT status, formatId, audioFormatId, variantKey, container, videoExact, audioOnly,
                    collectionId, queueOrder, createdAt, subtitleRequest FROM downloads WHERE id=2""").use {
                    it.next()
                    assertEquals("PAUSED", it.getString(1))
                    assertEquals("136", it.getString(2))
                    assertEquals("140", it.getString(3))
                    assertEquals("v:308+a:251", it.getString(4))
                    assertEquals("webm", it.getString(5))
                    assertEquals(1, it.getInt(6))
                    assertEquals(0, it.getInt(7))
                    assertEquals(0, it.getInt(8))
                    assertEquals(it.getLong(10), it.getLong(9))
                    assertEquals("[]", it.getString(11))
                }
                st.executeQuery("SELECT id, kind, protectedFromCleanup, subtitleId, codecs FROM media ORDER BY id").use {
                    it.next(); assertEquals(10L, it.getLong(1)); assertEquals("video", it.getString(2))
                    assertEquals(0, it.getInt(3)); assertEquals(0, it.getInt(4)); assertEquals("H.264 + AAC", it.getString(5))
                    it.next(); assertEquals(11L, it.getLong(1))
                }
                st.executeQuery("SELECT positionMs FROM playback WHERE mediaId=10").use { it.next(); assertEquals(50_000L, it.getLong(1)) }
                // Новые таблицы пусты: никаких придуманных коллекций в базе пользователя.
                for (t in listOf("collections", "collection_items", "seasons", "chapters", "segment_progress", "bookmarks", "subtitles")) {
                    st.executeQuery("SELECT COUNT(*) FROM $t").use { it.next(); assertEquals(t, 0, it.getInt(1)) }
                }
                // Длинные отметки времени (больше суток) хранятся без переполнения.
                st.execute("INSERT INTO chapters (mediaId,title,startMs,endMs,kind,origin,createdAt) VALUES (10,'День 2',90000000,180000000,'episode','user',1)")
                st.executeQuery("SELECT endMs - startMs FROM chapters").use { it.next(); assertEquals(90_000_000L, it.getLong(1)) }
                // Один файл в двух коллекциях, повтор связи не создаёт дубль.
                st.execute("INSERT INTO collections (title,createdAt,updatedAt) VALUES ('А',1,1),('Б',1,1)")
                st.execute("INSERT INTO collection_items (collectionId,mediaId,addedAt) VALUES (1,10,1),(2,10,1)")
                st.execute("INSERT OR IGNORE INTO collection_items (collectionId,mediaId,addedAt) VALUES (1,10,2)")
                st.executeQuery("SELECT COUNT(*) FROM collection_items WHERE mediaId=10").use { it.next(); assertEquals(2, it.getInt(1)) }
            }
            assertMatchesSchema(conn, 4)
        }
    }

    @Test
    fun `chain 1 to 4 matches schema 4`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            createV1(conn)
            fillV1(conn)
            conn.createStatement().use { st ->
                for (sql in Migrations.SQL_1_2 + Migrations.SQL_2_3 + Migrations.SQL_3_4) st.execute(sql)
                st.executeQuery("SELECT COUNT(*) FROM downloads").use { it.next(); assertEquals(4, it.getInt(1)) }
            }
            assertMatchesSchema(conn, 4)
        }
    }

    @Test
    fun `chain 1 to 2 to 3 matches schema 3`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            createV1(conn)
            fillV1(conn)
            conn.createStatement().use { st ->
                for (sql in Migrations.SQL_1_2) st.execute(sql)
                for (sql in Migrations.SQL_2_3) st.execute(sql)
                st.executeQuery("SELECT COUNT(*) FROM downloads").use { it.next(); assertEquals(4, it.getInt(1)) }
            }
            assertMatchesSchema(conn, 3)
        }
    }
}
