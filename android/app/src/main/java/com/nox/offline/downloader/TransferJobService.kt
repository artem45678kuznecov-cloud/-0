package com.nox.offline.downloader

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog

/**
 * Android 14+: носитель передачи — User-Initiated Data Transfer Job.
 *
 * Пока job идёт, система показывает наше уведомление через
 * [setNotification]; координатор внутри ведёт до трёх передач сразу.
 * Когда работы не осталось — [jobFinished]. Когда систему что-то не
 * устроило — [onStopJob] с причиной: она уходит в журнал, передачи
 * обрываются с сохранением .part, задания возвращаются в очередь.
 */
@RequiresApi(34)
class TransferJobService : JobService() {
    private var notifier: RunnerNotifier? = null
    private var params: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = NoxApp.get(this)
        val coordinator = app.coordinator
        this.params = params
        NoxLog.event("uidt-start", "job" to params.jobId)
        if (!coordinator.hasWorkBlocking()) {
            NoxLog.event("uidt-nothing-to-do")
            return false
        }
        val notifications = app.notifications
        notifications.ensureChannel()
        setNotification(params, DownloadNotifications.PRIMARY_ID, notifications.idle(),
            JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
        notifier = RunnerNotifier(app.db, notifications) { n ->
            val p = this.params ?: return@RunnerNotifier
            try {
                setNotification(p, DownloadNotifications.PRIMARY_ID, n, JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
            } catch (e: Exception) {
                NoxLog.event("uidt-notification-error", "error" to e.javaClass.simpleName)
            }
        }.also { it.start() }
        coordinator.attachRunner("uidt") {
            NoxLog.event("uidt-finished")
            finish(params)
        }
        return true
    }

    private fun finish(params: JobParameters) {
        val coordinator = NoxApp.get(this).coordinator
        coordinator.detachRunner("uidt")
        notifier?.stop()
        notifier = null
        try { jobFinished(params, false) } catch (_: Exception) { }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val reason = if (Build.VERSION.SDK_INT >= 31) params.stopReason else -1
        val reasonText = stopReasonName(reason)
        NoxLog.event("uidt-stop", "reason" to reasonText, "code" to reason)
        val coordinator = NoxApp.get(this).coordinator
        val byUser = reason == JobParameters.STOP_REASON_USER
        coordinator.detachRunner("uidt")
        coordinator.stopAll(reasonText, asPaused = byUser)
        notifier?.stop()
        notifier = null
        // Остановил пользователь — не навязываемся; иначе просим перезапуск.
        return !byUser
    }

    companion object {
        fun stopReasonName(code: Int): String = when (code) {
            JobParameters.STOP_REASON_UNDEFINED -> "undefined"
            JobParameters.STOP_REASON_CANCELLED_BY_APP -> "cancelled-by-app"
            JobParameters.STOP_REASON_PREEMPT -> "preempt"
            JobParameters.STOP_REASON_TIMEOUT -> "timeout"
            JobParameters.STOP_REASON_DEVICE_STATE -> "device-state"
            JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery-low"
            JobParameters.STOP_REASON_CONSTRAINT_CHARGING -> "charging"
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "connectivity"
            JobParameters.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "device-idle"
            JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage-low"
            JobParameters.STOP_REASON_QUOTA -> "quota"
            JobParameters.STOP_REASON_BACKGROUND_RESTRICTION -> "background-restriction"
            JobParameters.STOP_REASON_APP_STANDBY -> "app-standby"
            JobParameters.STOP_REASON_USER -> "user"
            JobParameters.STOP_REASON_SYSTEM_PROCESSING -> "system-processing"
            JobParameters.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "launch-time-changed"
            else -> "code-$code"
        }
    }
}
