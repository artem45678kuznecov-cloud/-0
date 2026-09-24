package com.nox.offline.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Пауза / Продолжить / Отменить из шторки уведомлений. */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PAUSE = "com.nox.offline.action.PAUSE"
        const val ACTION_RESUME = "com.nox.offline.action.RESUME"
        const val ACTION_CANCEL = "com.nox.offline.action.CANCEL"
        const val EXTRA_ID = "download_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val app = NoxApp.get(context)
        val pending = goAsync()
        NoxLog.event("notification-action", "action" to intent.action?.substringAfterLast('.'), "id" to id)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> app.coordinator.pause(id)
                    ACTION_CANCEL -> {
                        app.coordinator.cancel(id)
                        app.notifications.cancel(DownloadNotifications.EXTRA_BASE_ID + id.toInt())
                    }
                    ACTION_RESUME -> {
                        app.coordinator.resume(id)
                        app.notifications.cancel(DownloadNotifications.EXTRA_BASE_ID + id.toInt())
                        when (val r = TransferScheduler.ensureRunning(context)) {
                            is TransferScheduler.Result.Failed ->
                                app.notifications.hint("Откройте NOX, чтобы продолжить загрузку (${r.reason.take(60)})")
                            else -> Unit
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
