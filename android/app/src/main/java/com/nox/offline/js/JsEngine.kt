package com.nox.offline.js

import com.nox.offline.core.NoxLog
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Ошибка встроенного JS-движка; [status] — код из nox_js.h. */
class JsEngineException(val status: Int, message: String) : Exception(message) {
    val reason: String
        get() = when (status) {
            1 -> "script-error"
            2 -> "timeout"
            3 -> "memory-limit"
            4 -> "output-limit"
            else -> "engine-error"
        }
}

/**
 * Встроенный JS-движок NOX (QuickJS-NG, libnoxjs.so) для задач YouTube.
 *
 * Вызывается из Python (nox_jsc.py) через Chaquopy. Скрипту недоступны
 * файлы, сеть и Android API; каждое выполнение идёт в отдельной среде с
 * пределами памяти, стека, времени и вывода. Одновременно выполняется
 * один скрипт: задача YouTube требует ~200 МБ памяти движка.
 */
object JsEngine {
    const val MEMORY_LIMIT = 384L shl 20
    const val JS_STACK_LIMIT = 4L shl 20
    private const val THREAD_STACK = 16L shl 20
    /** Первый разбор плеера YouTube на слабом телефоне — до минуты; дальше он берётся из кэша. */
    const val TIMEOUT_MS = 150_000L
    const val OUTPUT_LIMIT = 32L shl 20
    private const val QUEUE_WAIT_MS = 180_000L

    private val gate = Semaphore(1, true)

    private val loadError: Throwable? by lazy {
        try {
            System.loadLibrary("noxjs")
            null
        } catch (t: Throwable) {
            NoxLog.event("js-engine", "state" to "load-failed", "error" to t.javaClass.simpleName)
            t
        }
    }

    fun available(): Boolean = loadError == null

    fun version(): String {
        loadError?.let { throw IllegalStateException("libnoxjs unavailable: ${it.javaClass.simpleName}") }
        return nativeVersion()
    }

    /** Пределы одного выполнения (по умолчанию — рабочие; тесты задают свои). */
    data class Limits(
        val memory: Long = MEMORY_LIMIT,
        val stack: Long = JS_STACK_LIMIT,
        val timeoutMs: Long = TIMEOUT_MS,
        val output: Long = OUTPUT_LIMIT,
    )

    /** Выполняет скрипт и возвращает всё, что он вывел через console.log. */
    @JvmOverloads
    fun run(script: String, limits: Limits = Limits()): String {
        loadError?.let { throw IllegalStateException("libnoxjs unavailable: ${it.javaClass.simpleName}") }
        if (!gate.tryAcquire(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw JsEngineException(5, "JS engine busy")
        }
        try {
            val code = script.toByteArray(Charsets.UTF_8)
            val out = AtomicReference<ByteArray?>()
            val err = AtomicReference<Throwable?>()
            val started = System.currentTimeMillis()
            // Отдельный поток с большим стеком: разбор плеера YouTube рекурсивен.
            val worker = Thread(null, {
                try {
                    out.set(nativeRun(code, limits.memory, limits.stack, limits.timeoutMs, limits.output))
                } catch (t: Throwable) {
                    err.set(t)
                }
            }, "nox-js", THREAD_STACK)
            worker.start()
            worker.join(limits.timeoutMs + 15_000L)
            val ms = System.currentTimeMillis() - started
            if (worker.isAlive) {
                NoxLog.event("js-run", "result" to "hung", "ms" to ms)
                throw JsEngineException(2, "JS engine did not stop in time")
            }
            err.get()?.let { t ->
                val reason = (t as? JsEngineException)?.reason ?: t.javaClass.simpleName
                NoxLog.event("js-run", "result" to reason, "ms" to ms, "in" to code.size)
                throw t
            }
            val bytes = out.get() ?: ByteArray(0)
            NoxLog.event("js-run", "result" to "ok", "ms" to ms, "in" to code.size, "out" to bytes.size)
            return String(bytes, Charsets.UTF_8)
        } finally {
            gate.release()
        }
    }

    @JvmStatic
    private external fun nativeRun(
        code: ByteArray,
        memoryLimit: Long,
        stackLimit: Long,
        timeoutMs: Long,
        outputLimit: Long,
    ): ByteArray

    @JvmStatic
    private external fun nativeVersion(): String
}
