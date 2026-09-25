package com.nox.offline.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Миграции базы. Добавление колонок со значениями по умолчанию: ни одна
 * строка v0.1.0 не удаляется. Единственное вычисляемое значение — отметка
 * «досмотрено» для видео, которые в 0.1.0 досмотрены до конца.
 *
 * SQL вынесен в списки, чтобы JVM-тест прогнал ровно те же команды на
 * настоящем SQLite с заполненной базой v1 и сверил результат со схемой 2.
 */
object Migrations {
    val SQL_1_2: List<String> = listOf(
        // downloads
        "ALTER TABLE `downloads` ADD COLUMN `customTitle` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `mode` TEXT NOT NULL DEFAULT 'progressive'",
        "ALTER TABLE `downloads` ADD COLUMN `audioUrl` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `audioHeadersJson` TEXT NOT NULL DEFAULT '{}'",
        "ALTER TABLE `downloads` ADD COLUMN `audioFormatId` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `videoTotalBytes` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `audioTotalBytes` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `videoDone` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `audioDone` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `uploader` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `allowSplit` INTEGER NOT NULL DEFAULT 0",
        // media
        "ALTER TABLE `media` ADD COLUMN `contentUri` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `media` ADD COLUMN `uploader` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `media` ADD COLUMN `imported` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `media` ADD COLUMN `moveState` TEXT NOT NULL DEFAULT ''",
        // playback
        "ALTER TABLE `playback` ADD COLUMN `completed` INTEGER NOT NULL DEFAULT 0",
        // В 0.1.0 отметки не было: досмотренным считается видео, остановленное
        // в последних 5 секундах (тот же порог, что у «Продолжить просмотр»).
        "UPDATE `playback` SET `completed` = 1 WHERE `durationMs` > 0 AND `positionMs` >= `durationMs` - 5000",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_1_2) db.execSQL(sql)
        }
    }

    /**
     * 2 → 3 (0.3.0): поля точного плана загрузки. Старые задания получают
     * planVersion = 0 и продолжают работать по сохранённым format ID и .part.
     */
    val SQL_2_3: List<String> = listOf(
        "ALTER TABLE `downloads` ADD COLUMN `planVersion` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `extractorKey` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `variantKey` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `width` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `fps` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `vcodec` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `acodec` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `container` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `dynamicRange` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `audioLang` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `videoExact` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `audioExact` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `videoChunk` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `audioChunk` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `errorKind` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `stage` TEXT NOT NULL DEFAULT ''",
        // media: настоящий контейнер и кодеки готового файла.
        "ALTER TABLE `media` ADD COLUMN `container` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `media` ADD COLUMN `width` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `media` ADD COLUMN `fps` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `media` ADD COLUMN `codecs` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `media` ADD COLUMN `variantKey` TEXT NOT NULL DEFAULT ''",
    )

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_2_3) db.execSQL(sql)
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
