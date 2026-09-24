package com.nox.offline.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Единственный источник правды о заданиях и медиатеке. Именно отсюда
 * состояние восстанавливается после гибели процесса: ни Activity, ни
 * сервис ничего важного в памяти не держат.
 */
@Database(
    entities = [DownloadEntity::class, MediaEntity::class, PlaybackEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NoxDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun media(): MediaDao
    abstract fun playback(): PlaybackDao

    companion object {
        fun build(context: Context): NoxDatabase =
            Room.databaseBuilder(context.applicationContext, NoxDatabase::class.java, "nox.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
