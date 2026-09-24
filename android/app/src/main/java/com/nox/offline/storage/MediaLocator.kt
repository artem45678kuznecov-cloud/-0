package com.nox.offline.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.nox.offline.data.db.MediaEntity
import java.io.File

/**
 * Где физически лежит видео медиатеки: файл внутри NOX или документ в
 * папке пользователя. Все экраны и плеер спрашивают только отсюда.
 */
object MediaLocator {
    fun authority(context: Context) = "${context.packageName}.files"

    fun playUri(m: MediaEntity): Uri =
        if (m.isExternal) Uri.parse(m.contentUri) else Uri.fromFile(File(m.filePath))

    /** Адрес для «Поделиться»: только content://, никогда file://. */
    fun shareUri(context: Context, m: MediaEntity): Uri =
        if (m.isExternal) Uri.parse(m.contentUri)
        else FileProvider.getUriForFile(context, authority(context), File(m.filePath))

    fun exists(context: Context, saf: SafStore, m: MediaEntity): Boolean =
        if (m.isExternal) saf.exists(m.contentUri) else File(m.filePath).exists()

    fun delete(saf: SafStore, m: MediaEntity): Boolean =
        if (m.isExternal) saf.delete(m.contentUri) else File(m.filePath).let { !it.exists() || it.delete() }

    fun fileName(m: MediaEntity): String =
        if (m.isExternal) Uri.parse(m.contentUri).lastPathSegment?.substringAfterLast('/') ?: m.title
        else File(m.filePath).name
}

/** Какое видео сейчас открыто в плеере: его файл не переносим и не переименовываем. */
object PlaybackRegistry {
    @Volatile
    var playingMediaId: Long = -1
}
