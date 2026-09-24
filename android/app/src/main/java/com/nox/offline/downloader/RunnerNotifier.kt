package com.nox.offline.downloader

import android.app.Notification
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.data.db.NoxDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Общая для обоих носителей часть: следит за живыми заданиями в Room и
 * обновляет уведомления. Первая активная загрузка — уведомление
 * носителя (его ставит [setPrimary]), остальные — обычные.
 */
class RunnerNotifier(
    private val db: NoxDatabase,
    private val notifications: DownloadNotifications,
    private val setPrimary: (Notification) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastExtras: Set<Int> = emptySet()

    @OptIn(FlowPreview::class)
    fun start() {
        job?.cancel()
        job = scope.launch {
            db.downloads().observeLive().sample(1000L).collect { list -> render(list) }
        }
    }

    private fun render(list: List<DownloadEntity>) {
        val active = list.filter { it.status.isActive }
        val shown = active.ifEmpty { list.filter { it.status == DownloadStatus.QUEUED } }
        if (shown.isEmpty()) {
            setPrimary(notifications.idle())
            clearExtras(list)
            return
        }
        val primary = shown.first()
        setPrimary(notifications.forDownload(primary, othersCount = shown.size - 1))
        val keep = mutableSetOf<Int>()
        for (e in shown.drop(1)) {
            val id = notifications.extraId(e)
            keep.add(id)
            notifications.post(id, notifications.forDownload(e))
        }
        for (old in lastExtras - keep) notifications.cancel(old)
        lastExtras = keep
    }

    private fun clearExtras(list: List<DownloadEntity>) {
        for (id in lastExtras) notifications.cancel(id)
        lastExtras = emptySet()
        notifications.cancelExtrasExcept(emptySet(), list)
    }

    fun stop() {
        job?.cancel()
        job = null
        for (id in lastExtras) notifications.cancel(id)
        lastExtras = emptySet()
        scope.cancel()
    }
}
