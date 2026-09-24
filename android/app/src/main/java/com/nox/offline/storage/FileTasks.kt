package com.nox.offline.storage

import com.nox.offline.core.AppEvents
import com.nox.offline.core.NoxLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Длительная файловая операция: экспорт, импорт, перенос, резервная копия. */
data class FileTask(
    val title: String,
    val detail: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Одна операция за раз, с прогрессом и отменой. Копирование идёт кусками,
 * отмена срабатывает между ними; незаконченная копия удаляется, исходник
 * не трогается.
 */
class FileTasks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _current = MutableStateFlow<FileTask?>(null)
    val current: StateFlow<FileTask?> = _current
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    fun run(title: String, block: suspend Progress.() -> String) {
        if (busy) {
            AppEvents.notice("Дождитесь окончания: ${_current.value?.title ?: "операция"}")
            return
        }
        _current.value = FileTask(title)
        job = scope.launch {
            val p = Progress(title)
            try {
                val summary = p.block()
                _current.value = _current.value?.copy(finished = true, detail = summary, done = _current.value?.total ?: 0)
                AppEvents.notice(summary)
            } catch (c: CancellationException) {
                _current.value = _current.value?.copy(finished = true, error = "Отменено")
            } catch (t: Throwable) {
                NoxLog.event("file-task-error", "task" to title, "error" to "${t.javaClass.simpleName}: ${t.message?.take(100)}")
                _current.value = _current.value?.copy(finished = true, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!busy) _current.value = null
    }

    inner class Progress(private val title: String) {
        fun update(detail: String, done: Long, total: Long) {
            _current.value = FileTask(title, detail, done, total)
        }
    }
}
