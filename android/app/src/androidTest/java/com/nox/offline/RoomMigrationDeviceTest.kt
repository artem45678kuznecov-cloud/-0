package com.nox.offline

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.data.db.Migrations
import com.nox.offline.data.db.NoxDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграция 1→2 на настоящем SQLite Android с проверкой Room: итоговая
 * схема сверяется с app/schemas/…/2.json, данные 0.1.0 сохраняются.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NoxDatabase::class.java)

    @Test fun filledV1DatabaseMigratesAndValidates() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL("""INSERT INTO downloads (id,pageUrl,quality,title,videoId,formatId,resolvedUrl,headersJson,fileName,ext,height,
                totalBytes,downloadedBytes,status,error,speedBps,etaSec,retries,resolveRetries,thumbnailUrl,durationSec,lastStopReason,createdAt,updatedAt)
                VALUES (1,'https://vk.com/video-1_2','480','Горы','v1','url480','https://x/v.mp4','{}','Горы [v1]','mp4',480,
                1000,400,'PAUSED','',0,0,0,0,'',60,'user',1,2)""")
            db.execSQL("""INSERT INTO media (id,title,filePath,sizeBytes,quality,height,durationSec,coverPath,pageUrl,videoId,createdAt)
                VALUES (10,'Море','/m/sea.mp4',5000,'720',720,1500,'','https://vk.com/video-3_4','v3',100)""")
            db.execSQL("INSERT INTO playback (mediaId,positionMs,durationMs,updatedAt) VALUES (10,600000,1500000,230),(11,59000,60000,240)")
        }
        helper.runMigrationsAndValidate("migration-test", NoxDatabase.VERSION, true, *Migrations.ALL).use { db ->
            db.query("SELECT title, status, downloadedBytes, mode, customTitle, allowSplit FROM downloads WHERE id=1").use { c ->
                c.moveToFirst()
                assertEquals("Горы", c.getString(0))
                assertEquals("PAUSED", c.getString(1))
                assertEquals(400L, c.getLong(2))
                assertEquals("progressive", c.getString(3))
                assertEquals("", c.getString(4))
                assertEquals(0, c.getInt(5))
            }
            db.query("SELECT title, filePath, contentUri, moveState FROM media WHERE id=10").use { c ->
                c.moveToFirst()
                assertEquals("Море", c.getString(0)); assertEquals("/m/sea.mp4", c.getString(1))
                assertEquals("", c.getString(2)); assertEquals("", c.getString(3))
            }
            db.query("SELECT positionMs, completed FROM playback WHERE mediaId=10").use { c ->
                c.moveToFirst()
                assertEquals(600000L, c.getLong(0)); assertEquals(0, c.getInt(1))
            }
            db.query("SELECT completed FROM playback WHERE mediaId=11").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
        }
    }
}
