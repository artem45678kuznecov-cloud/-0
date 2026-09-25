package com.nox.offline.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Единственный источник правды о заданиях и медиатеке. Именно отсюда
 * состояние восстанавливается после гибели процесса и после обновления
 * APK: ни Activity, ни сервис ничего важного в памяти не держат.
 *
 * Разрушительного fallback здесь нет намеренно. Если миграция не
 * подходит, приложение должно упасть с понятной ошибкой, а не молча
 * стереть медиатеку, очередь и позиции просмотра пользователя.
 */
@Database(
    entities = [DownloadEntity::class, MediaEntity::class, PlaybackEntity::class],
    version = NoxDatabase.VERSION,
    exportSchema = true,
)
abstract class NoxDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun media(): MediaDao
    abstract fun playback(): PlaybackDao

    companion object {
        const val VERSION = 3
        const val NAME = "nox.db"

        fun build(context: Context): NoxDatabase =
            Room.databaseBuilder(context.applicationContext, NoxDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
