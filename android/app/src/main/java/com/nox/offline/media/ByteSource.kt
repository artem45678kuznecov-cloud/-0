package com.nox.offline.media

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Файл медиатеки только для чтения, с произвольным доступом по смещению.
 *
 * Открывается всегда на чтение (FileInputStream → O_RDONLY): проверка файла
 * физически не может его изменить. Смещения — Long: фильмы бывают больше 4 ГБ.
 */
interface ByteSource : Closeable {
    val size: Long

    /** Прочитать до [len] байт с [pos]. Меньше — только у конца файла. */
    fun read(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size): Int
}

/**
 * Через FileChannel: и для обычного файла, и для дескриптора документа SAF.
 *
 * Чтение только позиционное (pread): общая позиция дескриптора не читается и
 * не меняется, поэтому источник не мешает другим читателям того же документа.
 * [base] и [size] — границы файла внутри дескриптора (AssetFileDescriptor
 * может начинаться не с нуля).
 */
class ChannelByteSource private constructor(
    private val stream: FileInputStream,
    private val base: Long = 0,
    size: Long = -1,
) : ByteSource {
    private val channel: FileChannel = stream.channel
    override val size: Long = if (size >= 0) size else channel.size() - base

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos < 0 || pos >= size || len <= 0) return 0
        val bb = ByteBuffer.wrap(buf, off, minOf(len.toLong(), size - pos).toInt())
        var done = 0
        while (bb.hasRemaining()) {
            val n = channel.read(bb, base + pos + done)
            if (n < 0) break
            if (n == 0 && pos + done >= size) break
            done += n
        }
        return done
    }

    override fun close() = stream.close()

    companion object {
        fun of(file: File): ChannelByteSource = ChannelByteSource(FileInputStream(file))

        /**
         * Дескриптор документа (openFileDescriptor / openAssetFileDescriptor).
         * [offset] и [length] — границы документа в дескрипторе; length < 0 — до
         * конца. Если провайдер отдал канал без произвольного доступа (pipe) —
         * честная ошибка, а не чтение подряд.
         */
        fun of(fd: FileDescriptor, offset: Long = 0, length: Long = -1): ChannelByteSource {
            val stream = FileInputStream(fd)
            try {
                // Проба позиционным чтением: позиция дескриптора не меняется.
                stream.channel.read(ByteBuffer.allocate(1), offset)
                val end = stream.channel.size()
                if (offset < 0 || offset > end || (length >= 0 && offset + length > end)) {
                    throw IOException("границы документа ($offset+$length) за пределами файла ($end байт)")
                }
                return ChannelByteSource(stream, offset, if (length >= 0) length else end - offset)
            } catch (e: IOException) {
                runCatching { stream.close() }
                throw IOException("источник не даёт читать файл по смещению (${e.message})", e)
            }
        }
    }
}

/**
 * Чтение мелких полей с кэшем окна: заголовки боксов и элементов читаются
 * тысячами, и каждый из них не должен быть отдельным системным вызовом.
 */
class CachedReader(val source: ByteSource, private val window: Int = 4096) {
    private val src get() = source
    val size get() = src.size
    private val cache = ByteArray(window)
    private var cacheStart = -1L
    private var cacheLen = 0
    var reads = 0L
        private set

    /** Байт по смещению или -1 за концом файла. */
    fun u8(pos: Long): Int {
        if (pos < 0 || pos >= src.size) return -1
        if (cacheStart < 0 || pos < cacheStart || pos >= cacheStart + cacheLen) {
            cacheStart = pos
            cacheLen = src.read(pos, cache, 0, window)
            reads++
            if (cacheLen <= 0) { cacheStart = -1; return -1 }
        }
        return cache[(pos - cacheStart).toInt()].toInt() and 0xFF
    }

    fun has(pos: Long, n: Int) = pos >= 0 && n >= 0 && pos + n <= src.size

    fun uint(pos: Long, n: Int): Long {
        var v = 0L
        for (i in 0 until n) {
            val b = u8(pos + i)
            if (b < 0) throw java.io.EOFException("конец файла на ${pos + i}")
            v = (v shl 8) or b.toLong()
        }
        return v
    }

    fun u32(pos: Long) = uint(pos, 4)
    fun u64(pos: Long) = uint(pos, 8)

    fun bytes(pos: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        val got = src.read(pos, out, 0, n)
        reads++
        if (got < n) throw java.io.EOFException("конец файла: нужно $n байт с $pos, есть $got")
        return out
    }

    fun fourcc(pos: Long): String {
        val sb = StringBuilder(4)
        for (i in 0 until 4) {
            val b = u8(pos + i)
            if (b < 0) return ""
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}
