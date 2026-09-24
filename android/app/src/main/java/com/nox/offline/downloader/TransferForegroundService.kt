package com.nox.offline.downloader

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog

/**
 * Android 13 и ниже: носитель передачи — foreground service типа
 * dataSync. На этих версиях у него нет лимита времени, а уведомление
 * держит процесс живым, пока идёт загрузка. На Android 14+ этот сервис
 * не запускается: там работает [TransferJobService].
 */
class TransferForegroundService : Service() {
    private var notifier: RunnerNotifier? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = NoxApp.get(this)
        val notifications = app.notifications
        notifications.ensureChannel()
        goForeground(notifications.idle())
        if (!started) {
            started = true
            NoxLog.event("fgs-start")
            notifier = RunnerNotifier(app.db, notifications) { n -> goForeground(n) }.also { it.start() }
            app.coordinator.attachRunner("fgs") {
                NoxLog.event("fgs-finished")
                stopRunner()
            }
        } else {
            app.coordinator.pump()
        }
        return START_STICKY
    }

    private fun goForeground(n: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(DownloadNotifications.PRIMARY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(DownloadNotifications.PRIMARY_ID, n)
            }
        } catch (e: Exception) {
            NoxLog.event("fgs-foreground-error", "error" to "${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }
    }

    private fun stopRunner() {
        val coordinator = NoxApp.get(this).coordinator
        coordinator.detachRunner("fgs")
        notifier?.stop()
        notifier = null
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) {
            NoxLog.event("fgs-destroy")
            val coordinator = NoxApp.get(this).coordinator
            coordinator.detachRunner("fgs")
            coordinator.stopAll("service-destroyed", asPaused = false)
            notifier?.stop()
            notifier = null
            started = false
        }
        super.onDestroy()
    }
}
