package com.nox.offline.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import com.nox.offline.core.NoxLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Работа с папкой, выбранной через Storage Access Framework (SAF).
 *
 * content:// здесь никогда не превращается в путь файловой системы: всё
 * через ContentResolver и DocumentsContract. Доступ к папке сохраняется
 * как persistable permission и переживает перезапуск; если пользователь
 * его отозвал или карта памяти извлечена, операции честно падают, а
 * исходный файл остаётся на месте.
 */
class SafStore(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Запомнить доступ к папке. Возвращает человекочитаемое имя. */
    fun persist(tree: Uri): String {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(tree, flags)
        return DocumentFile.fromTreeUri(context, tree)?.name ?: tree.lastPathSegment ?: "Папка"
    }

    fun release(tree: String) {
        if (tree.isBlank()) return
        try {
            resolver.releasePersistableUriPermission(Uri.parse(tree),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
    }

    fun hasPermission(tree: String): Boolean {
        if (tree.isBlank()) return false
        val uri = Uri.parse(tree)
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission && it.isReadPermission }
    }

    fun isWritable(tree: String): Boolean {
        if (!hasPermission(tree)) return false
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(tree))?.let { it.exists() && it.canWrite() } == true
        } catch (_: Exception) {
            false
        }
    }

    /** Свободное место на томе папки, если провайдер это позволяет; -1 — неизвестно. */
    fun freeBytes(tree: String): Long = try {
        val doc = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return -1
        resolver.openFileDescriptor(doc.uri, "r")?.use { pfd ->
            val st = Os.fstatvfs(pfd.fileDescriptor)
            st.f_bavail * st.f_frsize
        } ?: -1
    } catch (_: Exception) {
        -1
    }

    /**
     * Копия файла в папку: сначала под временным именем, затем проверка
     * размера и переименование. Оригинал не трогается — удалять его или
     * нет, решает вызывающий уже после успешной проверки.
     */
    suspend fun copyInto(tree: String, source: File, displayName: String, mime: String = "video/mp4",
                         onProgress: (Long, Long) -> Unit = { _, _ -> }): Uri =
        copyFrom(tree, source.length(), displayName, mime, onProgress) { FileInputStream(source) }

    /** То же для любого источника: файл NOX или документ в другой папке. */
    suspend fun copyFrom(tree: String, sourceSize: Long, displayName: String, mime: String,
                         onProgress: (Long, Long) -> Unit, open: () -> java.io.InputStream): Uri {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: throw IOException("Папка недоступна")
        if (!dir.canWrite()) throw IOException("Нет доступа на запись в папку")
        FileOps.requireSpace(sourceSize, freeBytes(tree))
        val finalName = uniqueName(dir, displayName)
        val tmpName = "$finalName.noxtmp"
        dir.findFile(tmpName)?.delete()
        val tmp = dir.createFile("application/octet-stream", tmpName) ?: throw IOException("Не удалось создать файл в папке")
        try {
            open().use { input ->
                (resolver.openOutputStream(tmp.uri, "w") ?: throw IOException("Нет потока записи")).use { out ->
                    FileOps.copy(input, out, sourceSize, onProgress)
                }
            }
            val written = DocumentFile.fromSingleUri(context, tmp.uri)?.length() ?: -1
            if (sourceSize > 0 && written >= 0 && written != sourceSize) {
                throw IOException("Размер копии не совпал: $written из $sourceSize")
            }
            val renamed = try {
                DocumentsContract.renameDocument(resolver, tmp.uri, finalName)
            } catch (_: Exception) {
                null
            }
            if (renamed != null) return renamed
            // Провайдер не умеет переименовывать — пишем сразу под итоговым именем.
            tmp.delete()
            val direct = dir.createFile(mime, finalName) ?: throw IOException("Не удалось создать файл")
            open().use { input ->
                (resolver.openOutputStream(direct.uri, "w") ?: throw IOException("Нет потока записи")).use { out ->
                    FileOps.copy(input, out, sourceSize, onProgress)
                }
            }
            return direct.uri
        } catch (t: Throwable) {
            try { tmp.delete() } catch (_: Exception) { }
            NoxLog.event("saf-copy-error", "error" to "${t.javaClass.simpleName}: ${t.message?.take(80)}")
            throw t
        }
    }

    fun exists(uri: String): Boolean = try {
        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.exists() == true
    } catch (_: Exception) {
        false
    }

    fun size(uri: String): Long = try {
        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.length() ?: -1
    } catch (_: Exception) {
        -1
    }

    fun delete(uri: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
    } catch (_: Exception) {
        false
    }

    fun rename(uri: String, newName: String): String? = try {
        DocumentsContract.renameDocument(resolver, Uri.parse(uri), newName)?.toString()
    } catch (_: Exception) {
        null
    }

    fun label(tree: String): String = try {
        DocumentFile.fromTreeUri(context, Uri.parse(tree))?.name ?: "Папка"
    } catch (_: Exception) {
        "Папка"
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 2..999) {
            val candidate = "$stem ($n)$ext"
            if (dir.findFile(candidate) == null) return candidate
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }
}
