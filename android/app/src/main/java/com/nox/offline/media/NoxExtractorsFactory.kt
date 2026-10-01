package com.nox.offline.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import com.nox.offline.core.NoxLog
import java.io.File
import java.io.IOException

/**
 * Экстракторы плеера NOX — те же, что DefaultExtractorsFactory, но MP4 без
 * фрагментов с большим индексом (moov) читает [LargeMp4Extractor]: его
 * таблицы сэмплов остаются в файле, а не разворачиваются в Java-кучу.
 * Обычные файлы идут прежним путём Media3 без изменений.
 */
@UnstableApi
class NoxExtractorsFactory(context: Context) : ExtractorsFactory {
    private val app = context.applicationContext
    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = defaults.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val base = defaults.createExtractors(uri, responseHeaders)
        val large = largeMp4(app, uri) ?: return base
        return arrayOf<Extractor>(large) + base
    }

    companion object {
        /**
         * С какого размера moov таблицы остаются в файле. Mp4Extractor берёт
         * в куче примерно 4 размера moov (сам moov и массивы по 32 байта на
         * сэмпл): при лимите 256 МиБ порог — 6,4 МиБ, то есть до ~27 МиБ кучи.
         */
        fun thresholdBytes(maxHeap: Long = Runtime.getRuntime().maxMemory()): Long = maxOf(4L shl 20, maxHeap / 40)

        /** file:// или content:// только на чтение, с чтением по смещению. */
        fun openReadOnly(context: Context, uri: Uri): ByteSource = when (uri.scheme) {
            null, "file" -> ChannelByteSource.of(File(uri.path ?: throw IOException("пустой путь")))
            "content" -> {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("провайдер не открыл документ")
                val src = try { ChannelByteSource.of(pfd.fileDescriptor) } catch (t: Throwable) { pfd.close(); throw t }
                object : ByteSource by src {
                    override fun close() { src.close(); pfd.close() }
                }
            }
            else -> throw IOException("не локальный адрес: ${uri.scheme}")
        }

        /** Экстрактор для MP4 с большим индексом или null: не MP4, фрагменты, индекс небольшой, файл не открыть. */
        fun largeMp4(context: Context, uri: Uri): LargeMp4Extractor? {
            if (uri.scheme != null && uri.scheme != "file" && uri.scheme != "content") return null
            val moov = try {
                openReadOnly(context, uri).use { moovSize(it) }
            } catch (e: Exception) {
                -1L
            }
            if (moov < thresholdBytes()) return null
            NoxLog.event("mp4-large-path", "moovMiB" to moov / 1_048_576)
            return LargeMp4Extractor { openReadOnly(context, uri) }
        }

        /**
         * Размер moov у MP4 без фрагментов или -1. Читаются только заголовки
         * боксов верхнего уровня (и детей moov — нет ли mvex): несколько
         * коротких чтений, где бы ни лежал moov.
         */
        fun moovSize(src: ByteSource): Long {
            val r = CachedReader(src, 32)
            val size = src.size
            var pos = 0L
            repeat(MAX_TOP_BOXES) {
                if (pos + 8 > size) return -1
                var boxSize = r.u32(pos)
                val type = r.fourcc(pos + 4)
                var header = 8
                if (boxSize == 1L) { boxSize = r.u64(pos + 8); header = 16 } else if (boxSize == 0L) boxSize = size - pos
                if (boxSize < header) return -1
                when (type) {
                    "moof", "mvex" -> return -1
                    "moov" -> {
                        var child = pos + header
                        val end = minOf(pos + boxSize, size)
                        while (child + 8 <= end) {
                            var cs = r.u32(child)
                            if (r.fourcc(child + 4) == "mvex") return -1
                            if (cs == 1L) cs = r.u64(child + 8)
                            if (cs < 8) break
                            child += cs
                        }
                        return boxSize
                    }
                }
                pos += boxSize
            }
            return -1
        }

        private const val MAX_TOP_BOXES = 64
    }
}
