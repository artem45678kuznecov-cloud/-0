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

    /**
     * 3 → 4 (0.4.0): личная медиатека — коллекции, сериалы, главы, закладки,
     * субтитры; тип медиа, защита от очистки; порядок очереди и параметры
     * заданий. Только новые таблицы и колонки: ни одна строка не удаляется,
     * mediaId и позиции просмотра не меняются.
     */
    val SQL_3_4: List<String> = listOf(
        "ALTER TABLE `downloads` ADD COLUMN `audioOnly` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `subtitleRequest` TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE `downloads` ADD COLUMN `subtitleError` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `downloads` ADD COLUMN `collectionId` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `collectionPosition` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `downloads` ADD COLUMN `queueOrder` INTEGER NOT NULL DEFAULT 0",
        // Порядок очереди прежних заданий — как был: по времени создания.
        "UPDATE `downloads` SET `queueOrder` = `createdAt`",
        "ALTER TABLE `media` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'video'",
        "ALTER TABLE `media` ADD COLUMN `protectedFromCleanup` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `media` ADD COLUMN `subtitleId` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `media` ADD COLUMN `subtitleOffsetMs` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL DEFAULT '', `type` TEXT NOT NULL DEFAULT 'album', `coverMediaId` INTEGER NOT NULL DEFAULT 0, `coverPath` TEXT NOT NULL DEFAULT '', `pinned` INTEGER NOT NULL DEFAULT 0, `sortOrder` INTEGER NOT NULL DEFAULT 0, `tags` TEXT NOT NULL DEFAULT '', `sourceUrl` TEXT NOT NULL DEFAULT '', `systemKey` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_collections_type` ON `collections` (`type`)",
        "CREATE INDEX IF NOT EXISTS `index_collections_pinned` ON `collections` (`pinned`)",
        "CREATE TABLE IF NOT EXISTS `collection_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `collectionId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL DEFAULT 0, `chapterId` INTEGER NOT NULL DEFAULT 0, `position` INTEGER NOT NULL DEFAULT 0, `season` INTEGER NOT NULL DEFAULT 0, `episode` INTEGER NOT NULL DEFAULT 0, `title` TEXT NOT NULL DEFAULT '', `sourceUrl` TEXT NOT NULL DEFAULT '', `sourceKey` TEXT NOT NULL DEFAULT '', `unavailableReason` TEXT NOT NULL DEFAULT '', `addedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_collection_items_collectionId` ON `collection_items` (`collectionId`)",
        "CREATE INDEX IF NOT EXISTS `index_collection_items_mediaId` ON `collection_items` (`mediaId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_collection_items_collectionId_mediaId_chapterId_sourceKey` ON `collection_items` (`collectionId`, `mediaId`, `chapterId`, `sourceKey`)",
        "CREATE TABLE IF NOT EXISTS `seasons` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `collectionId` INTEGER NOT NULL, `number` INTEGER NOT NULL, `title` TEXT NOT NULL DEFAULT '')",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_seasons_collectionId_number` ON `seasons` (`collectionId`, `number`)",
        "CREATE TABLE IF NOT EXISTS `chapters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaId` INTEGER NOT NULL, `title` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL DEFAULT -1, `kind` TEXT NOT NULL DEFAULT 'chapter', `origin` TEXT NOT NULL DEFAULT 'user', `createdAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_chapters_mediaId` ON `chapters` (`mediaId`)",
        "CREATE TABLE IF NOT EXISTS `segment_progress` (`chapterId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chapterId`))",
        "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `title` TEXT NOT NULL DEFAULT '', `note` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_bookmarks_mediaId` ON `bookmarks` (`mediaId`)",
        "CREATE TABLE IF NOT EXISTS `subtitles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaId` INTEGER NOT NULL, `language` TEXT NOT NULL DEFAULT '', `label` TEXT NOT NULL, `origin` TEXT NOT NULL DEFAULT 'user', `format` TEXT NOT NULL, `filePath` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_subtitles_mediaId` ON `subtitles` (`mediaId`)",
    )

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_3_4) db.execSQL(sql)
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
