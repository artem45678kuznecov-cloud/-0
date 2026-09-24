package com.nox.offline.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Миграция 1→2 на настоящем SQLite.
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

    private fun createV1(conn: Connection) {
        val db = schema(1)
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
            val entities = schema(2).getJSONArray("entities")
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
        assertEquals(2, NoxDatabase.VERSION)
        assertEquals(2, schema(2).getInt("version"))
    }
}
