package com.nox.offline.updates

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nox.offline.MainActivity
import com.nox.offline.NoxApp
import com.nox.offline.R
import com.nox.offline.core.Format
import com.nox.offline.core.NoxLog
import com.nox.offline.downloader.DownloadNotifications
import com.nox.offline.downloader.TransferJobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Кто несёт скачивание APK, пока NOX свёрнут или экран заблокирован.
 *
 * Android 14+ (API 34): своё User-Initiated Data Transfer задание —
 * пользователь нажал «Обновить», система держит передачу с уведомлением и
 * сама останавливает её без сети и возвращает, когда сеть появится.
 * Android 13 и ниже: foreground service типа dataSync (как у видео).
 *
 * Видеозагрузки здесь не участвуют: у них свой носитель ([TransferJobService]).
 * Бесконечной службы и ручных wake lock нет: задание кончается вместе с
 * передачей; принудительную остановку приложения пользователем оно не обходит.
 */
object UpdateScheduler {
    const val JOB_ID = 7002
    const val NOTIFICATION_ID = 43

    /** Поднять носитель. false — не вышло (тогда передача идёт в процессе NOX, пока он жив). */
    fun start(context: Context, m: UpdateManifest): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) scheduleUidt(context, m) else {
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
            NoxLog.event("update-fgs-start")
            true
        }
    } catch (e: Exception) {
        NoxLog.event("update-carrier-error", "error" to "${e.javaClass.simpleName}: ${e.message?.take(100)}")
        false
    }

    @RequiresApi(34)
    private fun scheduleUidt(context: Context, m: UpdateManifest): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return true
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(m.apkSize.coerceAtLeast(1), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
            .build()
        val code = scheduler.schedule(info)
        NoxLog.event("update-uidt-scheduled", "code" to code)
        return code == JobScheduler.RESULT_SUCCESS
    }

    /** Пользователь отменил: снять задание или службу (часть файла остаётся). */
    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        } else {
            context.stopService(Intent(context, UpdateDownloadService::class.java))
        }
    }

    fun isScheduled(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

    fun notification(context: Context, s: UpdateState): Notification {
        val open = PendingIntent.getActivity(context, 9,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.nox_accent))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        when (s) {
            is UpdateState.Downloading -> {
                b.setContentTitle("Обновление NOX ${s.manifest.versionName}")
                b.setContentText(downloadLine(s))
                if (s.total > 0) b.setProgress(1000, (s.done * 1000 / s.total).toInt(), false) else b.setProgress(0, 0, true)
            }
            is UpdateState.Verifying -> b.setContentTitle("Обновление NOX ${s.manifest.versionName}").setContentText("Проверка файла…")
                .setProgress(0, 0, true)
            else -> b.setContentTitle("Обновление NOX").setContentText("Подготовка…")
        }
        return b.build()
    }

    /** «12,3 МБ из 58,6 МБ · 1,2 МБ/с · ещё ~45 с» или что сейчас мешает. */
    fun downloadLine(s: UpdateState.Downloading): String {
        val got = "${Format.bytes(s.done)} из ${Format.bytes(s.total)}"
        return when (s.phase) {
            DownloadPhase.CONNECTING -> "$got · соединение…"
            DownloadPhase.DOWNLOADING -> buildString {
                append(got)
                if (s.bytesPerSecond > 0) append(" · ${Format.speed(s.bytesPerSecond)}")
                if (s.etaSeconds > 0) append(" · ещё ~${Format.shortDuration(s.etaSeconds)}")
            }
            DownloadPhase.STALLED -> "$got · передача остановилась, ждём сервер"
            DownloadPhase.RETRY_WAIT -> "$got · повтор через ${s.retryInSeconds} с"
            DownloadPhase.WAITING_NETWORK -> "$got · ожидание сети"
            DownloadPhase.PAUSED -> "$got · приостановлено, продолжится само"
        }
    }

    /** Без UIDT (Android 13 и ниже) ждать сеть самим, но не дольше получаса. */
    suspend fun awaitNetwork(context: Context, onWaiting: () -> Unit): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        fun online() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (online()) return true
        onWaiting()
        return withTimeoutOrNull(30 * 60_000L) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(true)
                    }
                }
                cm.registerDefaultNetworkCallback(cb)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        } ?: false
    }
}

/** Android 14+: носитель скачивания APK — User-Initiated Data Transfer задание. */
@RequiresApi(34)
class UpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = NoxApp.get(this)
        app.notifications.ensureChannel()
        setNotification(params, UpdateScheduler.NOTIFICATION_ID, UpdateScheduler.notification(this, app.updates.state.value),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        NoxLog.event("update-uidt-start")
        work = scope.launch {
            var last = 0L
            val result = app.updates.runJob(carrier = "uidt") { s ->
                val now = System.currentTimeMillis()
                if (now - last >= 1000 || s !is UpdateState.Downloading) {
                    last = now
                    runCatching { setNotification(params, UpdateScheduler.NOTIFICATION_ID, UpdateScheduler.notification(this@UpdateJobService, s),
                        JOB_END_NOTIFICATION_POLICY_REMOVE) }
                }
            }
            NoxLog.event("update-uidt-finished", "result" to result?.javaClass?.simpleName)
            // Без сети — вернуть задание в очередь: система продолжит, когда сеть появится.
            jobFinished(params, result is UpdateTransfer.Result.NoNetwork)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val reason = params.stopReason
        val name = TransferJobService.stopReasonName(reason)
        NoxLog.event("update-uidt-stop", "reason" to name)
        val byUser = reason == JobParameters.STOP_REASON_USER || reason == JobParameters.STOP_REASON_CANCELLED_BY_APP
        NoxApp.get(this).updates.onCarrierStopped(name, byUser)
        work?.cancel()
        // Остановил пользователь (или само приложение по «Отменить») — не навязываемся.
        return !byUser
    }
}

/** Android 13 и ниже: носитель скачивания APK — foreground service типа dataSync. */
class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = NoxApp.get(this)
        app.notifications.ensureChannel()
        foreground(UpdateScheduler.notification(this, app.updates.state.value))
        if (work?.isActive == true) return START_STICKY
        work = scope.launch {
            var last = 0L
            val result = app.updates.runJob(carrier = "fgs") { s ->
                val now = System.currentTimeMillis()
                if (now - last >= 1000 || s !is UpdateState.Downloading) {
                    last = now
                    foreground(UpdateScheduler.notification(this@UpdateDownloadService, s))
                }
            }
            NoxLog.event("update-fgs-finished", "result" to result?.javaClass?.simpleName)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    private fun foreground(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(UpdateScheduler.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(UpdateScheduler.NOTIFICATION_ID, n)
        } catch (e: Exception) {
            NoxLog.event("update-fgs-foreground-error", "error" to "${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }
    }

    override fun onDestroy() {
        if (work?.isActive == true) NoxApp.get(this).updates.onCarrierStopped("service-destroyed", byUser = false)
        work?.cancel()
        super.onDestroy()
    }
}
