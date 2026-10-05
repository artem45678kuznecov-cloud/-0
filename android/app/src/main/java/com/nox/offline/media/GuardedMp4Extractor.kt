package com.nox.offline.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure
import com.nox.offline.core.NoxLog
import java.io.EOFException

/**
 * Обычный MP4-разборщик Media3 (Mp4Extractor, FragmentedMp4Extractor), чей
 * sniff не может удержать во входе больше [budget] байт.
 *
 * Sniffer Media3 1.5.1, встретив moov, просматривает его целиком; у
 * DefaultExtractorInput всё просмотренное копируется в peekBuffer и остаётся
 * там до чтения. Здесь каждый просмотр сначала сверяется с бюджетом: запрос за
 * его пределы не выполняется вовсе (решение до выделения памяти, а не перехват
 * OutOfMemoryError), а sniff отвечает «не мой формат». MP4 с таким индексом
 * читает [LargeMp4Extractor]; сюда он попадает, только если экономный путь
 * недоступен, и тогда честно не открывается вместо падения процесса.
 *
 * Бюджет по умолчанию — 4 КиБ поиска Sniffer плюс предел moov, с которым
 * обычный разбор безопасен ([NoxExtractorsFactory.thresholdBytes]).
 */
@UnstableApi
class GuardedMp4Extractor(
    private val inner: Extractor,
    private val budget: Long = sniffBudget(NoxExtractorsFactory.thresholdBytes()),
) : Extractor {
    /** Последний sniff отказал из-за бюджета (для журнала и тестов). */
    var refusedOverBudget = false
        private set

    override fun sniff(input: ExtractorInput): Boolean {
        refusedOverBudget = false
        val limited = PeekBudgetInput(input, budget)
        return try {
            inner.sniff(limited)
        } catch (e: PeekBudgetExceeded) {
            refusedOverBudget = true
            NoxLog.event("mp4-sniff-over-budget", "extractor" to inner.javaClass.simpleName, "budgetKiB" to budget / 1024)
            false
        }
    }

    override fun init(output: ExtractorOutput) = inner.init(output)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
    override fun release() = inner.release()
    override fun getSniffFailureDetails(): List<SniffFailure> = inner.sniffFailureDetails
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation

    companion object {
        /** SEARCH_LENGTH Sniffer (4 КиБ) + moov до [moovLimit] + заголовки. */
        fun sniffBudget(moovLimit: Long): Long = 4096 + moovLimit + 64
    }
}

/** Просмотр вышел бы за бюджет; наследник EOFException — BundledExtractorsAdapter считает это «не мой формат». */
class PeekBudgetExceeded(message: String) : EOFException(message)

/**
 * Вход, который не даёт просмотреть (peek) дальше [budget] байт от позиции, с
 * которой начат sniff. Проверка — до обращения к настоящему входу, поэтому его
 * peekBuffer не растёт сверх бюджета. Чтение и пропуск (read/skip) во время
 * sniff не нужны и запрещены контрактом Extractor.sniff.
 */
@UnstableApi
class PeekBudgetInput(private val input: ExtractorInput, private val budget: Long) : ExtractorInput {
    private val start = input.position

    private fun check(length: Int) {
        val end = input.peekPosition + length
        if (end - start > budget) {
            throw PeekBudgetExceeded("просмотр до ${end - start} байт — больше бюджета $budget")
        }
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        check(length)
        return input.peek(target, offset, length)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        check(length)
        return input.peekFully(target, offset, length, allowEndOfInput)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        check(length)
        input.peekFully(target, offset, length)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        check(length)
        return input.advancePeekPosition(length, allowEndOfInput)
    }

    override fun advancePeekPosition(length: Int) {
        check(length)
        input.advancePeekPosition(length)
    }

    override fun resetPeekPosition() = input.resetPeekPosition()
    override fun getPeekPosition(): Long = input.peekPosition
    override fun getPosition(): Long = input.position
    override fun getLength(): Long = input.length

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = refuse()
    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean = refuse()
    override fun readFully(target: ByteArray, offset: Int, length: Int): Unit = refuse()
    override fun skip(length: Int): Int = refuse()
    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean = refuse()
    override fun skipFully(length: Int): Unit = refuse()
    override fun <E : Throwable> setRetryPosition(position: Long, e: E): Unit = input.setRetryPosition(position, e)

    private fun refuse(): Nothing = throw IllegalStateException("sniff не должен читать или пропускать данные входа")
}
