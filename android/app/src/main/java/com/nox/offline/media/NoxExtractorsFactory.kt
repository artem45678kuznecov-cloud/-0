package com.nox.offline.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import com.nox.offline.core.NoxLog
import java.io.File
import java.io.IOException

/**
 * Экстракторы плеера NOX — те же, что DefaultExtractorsFactory, но:
 *
 * - MP4 без фрагментов с большим индексом (moov) читает [LargeMp4Extractor]:
 *   его таблицы сэмплов остаются в файле, а распознавание читает только
 *   заголовки боксов. Обычные MP4-разборщики для такого файла в список не
 *   попадают — ни один кандидат не повторит просмотр всего moov;
 * - обычные Mp4Extractor и FragmentedMp4Extractor обёрнуты в
 *   [GuardedMp4Extractor]: их sniff не удержит во входе больше бюджета, даже
 *   если экономный путь недоступен (файл не открылся вторым дескриптором).
 *
 * Обычные файлы идут прежним путём Media3 без изменений.
 */
@UnstableApi
class NoxExtractorsFactory(context: Context, matroskaFlags: Int = 0) : ExtractorsFactory {
    private val app = context.applicationContext
    private val defaults = DefaultExtractorsFactory().setMatroskaExtractorFlags(matroskaFlags)

    override fun createExtractors(): Array<Extractor> = guarded(defaults.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        forSource(defaults.createExtractors(uri, responseHeaders), opener(app, uri))

    companion object {
        /**
         * С какого размера moov таблицы остаются в файле. Mp4Extractor берёт
         * в куче примерно 4 размера moov (сам moov и массивы по 32 байта на
         * сэмпл): при лимите 256 МиБ порог — 6,4 МиБ, то есть до ~27 МиБ кучи.
         */
        fun thresholdBytes(maxHeap: Long = Runtime.getRuntime().maxMemory()): Long = maxOf(4L shl 20, maxHeap / 40)

        /** Обычный MP4-разборщик Media3 (его sniff просматривает весь moov). */
        fun isStandardMp4(e: Extractor): Boolean =
            e.underlyingImplementation.let { it is Mp4Extractor || it is FragmentedMp4Extractor }

        /** Тот же список, но у обычных MP4-разборщиков sniff ограничен бюджетом. */
        fun guarded(base: Array<Extractor>, moovLimit: Long = thresholdBytes()): Array<Extractor> =
            Array(base.size) { i ->
                val e = base[i]
                if (e !is GuardedMp4Extractor && isStandardMp4(e)) GuardedMp4Extractor(e, GuardedMp4Extractor.sniffBudget(moovLimit)) else e
            }

        /**
         * Экстракторы для файла. [open] — независимое открытие того же файла
         * только на чтение (или null, если адрес не локальный).
         */
        fun forSource(base: Array<Extractor>, open: (() -> ByteSource)?, moovLimit: Long = thresholdBytes()): Array<Extractor> {
            val rest = guarded(base, moovLimit)
            val large = open?.let { largeMp4(it, moovLimit) } ?: return rest
            return arrayOf<Extractor>(large) + rest.filterNot { isStandardMp4(it) }
        }

        /** Открытие файла по адресу для разбора по смещениям, или null — адрес не локальный. */
        fun opener(context: Context, uri: Uri): (() -> ByteSource)? = when (uri.scheme) {
            null, "file", "content" -> { { openReadOnly(context, uri) } }
            else -> null
        }

        /**
         * file:// или content:// только на чтение, с чтением по смещению.
         * content:// открывается так же, как его открывает ContentDataSource
         * плеера (openTypedAssetFileDescriptor без перекодирования): учитываются
         * начало и длина документа в дескрипторе.
         */
        fun openReadOnly(context: Context, uri: Uri): ByteSource = when (uri.scheme) {
            null, "file" -> ChannelByteSource.of(File(uri.path ?: throw IOException("пустой путь")))
            "content" -> {
                val options = Bundle().apply { putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true) }
                val afd = context.contentResolver.openTypedAssetFileDescriptor(uri, "*/*", options)
                    ?: throw IOException("провайдер не открыл документ")
                val length = if (afd.length == AssetFileDescriptor.UNKNOWN_LENGTH) -1 else afd.length
                val src = try { ChannelByteSource.of(afd.fileDescriptor, afd.startOffset, length) } catch (t: Throwable) { afd.close(); throw t }
                object : ByteSource by src {
                    override fun close() { try { src.close() } finally { afd.close() } }
                }
            }
            else -> throw IOException("не локальный адрес: ${uri.scheme}")
        }

        /**
         * Экстрактор для MP4 с большим индексом или null: не MP4, фрагменты,
         * индекс небольшой, файл не открыть. Читаются только заголовки боксов.
         */
        fun largeMp4(open: () -> ByteSource, moovLimit: Long = thresholdBytes()): LargeMp4Extractor? {
            val scan = try {
                open().use { BoundedMp4Sniffer.scan(it) }
            } catch (e: Exception) {
                NoxLog.event("mp4-route-unreadable", "error" to "${e.javaClass.simpleName}: ${e.message?.take(80)}")
                return null
            }
            if (!scan.unfragmentedWithMoov || scan.moovSize <= moovLimit) return null
            NoxLog.event("mp4-large-path", "moovMiB" to scan.moovSize / 1_048_576, "moovAtEnd" to scan.moovAfterMdat)
            return LargeMp4Extractor(moovLimit, open)
        }

        fun largeMp4(context: Context, uri: Uri): LargeMp4Extractor? = opener(context, uri)?.let { largeMp4(it) }

        /** Размер moov у MP4 без фрагментов или -1 (только заголовки боксов). */
        fun moovSize(src: ByteSource): Long = BoundedMp4Sniffer.scan(src).let { if (it.unfragmentedWithMoov) it.moovSize else -1 }
    }
}
