package com.nox.offline.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.nox.offline.BuildConfig
import com.nox.offline.MainActivity
import com.nox.offline.NoxApp
import com.nox.offline.R
import com.nox.offline.core.NoxLog
import com.nox.offline.downloader.DownloadNotifications
import com.nox.offline.downloader.TransferScheduler

/**
 * Исход установки от PackageInstaller.
 *
 * STATUS_PENDING_USER_ACTION — система ждёт подтверждения: окно
 * открывается сразу, если NOX на экране, иначе — через уведомление.
 * Отмена или ошибка возвращают NOX в рабочее состояние: загрузки,
 * остановленные перед установкой, продолжаются.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.nox.offline.action.INSTALL_STATUS"
        const val EXTRA_VERSION = "version"
        const val CONFIRM_NOTIFICATION = 77
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = NoxApp.get(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        NoxLog.event("update-status", "status" to status, "msg" to message?.take(80))
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    app.updates.onInstallFinished(false, "система не прислала окно подтверждения"); return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (visible) {
                    try { context.startActivity(confirm); return } catch (e: Exception) {
                        NoxLog.event("update-confirm-start-failed", "error" to e.javaClass.simpleName)
                    }
                }
                val pi = PendingIntent.getActivity(context, 7, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Обновление NOX готово")
                    .setContentText("Нажмите, чтобы подтвердить установку")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                postUpdateNotification(context, CONFIRM_NOTIFICATION, n)
            }
            PackageInstaller.STATUS_SUCCESS -> app.updates.onInstallFinished(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> app.updates.onInstallFinished(false, null)
            else -> app.updates.onInstallFinished(false, message ?: "код $status")
        }
    }
}

/**
 * Пакет NOX только что заменён новой версией. Это единственный момент,
 * когда можно честно сказать «обновление установлено»: код уже новый.
 * Сообщаем уведомлением с кнопкой «Открыть»; на Android 13 и ниже заодно
 * продолжаем остановленные перед установкой загрузки (этот broadcast —
 * разрешённый системой повод запустить foreground service). На Android
 * 14+ User-Initiated job можно запланировать только из видимого
 * приложения, поэтому загрузки продолжатся при открытии NOX.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = NoxApp.get(context)
        NoxLog.event("package-replaced", "version" to BuildConfig.VERSION_NAME)
        app.notifications.ensureChannel()
        val open = PendingIntent.getActivity(context, 8,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pending = app.coordinator.hasWorkBlocking()
        val text = if (pending && Build.VERSION.SDK_INT >= 34) "Откройте NOX, чтобы продолжить загрузки"
        else "Медиатека и настройки на месте"
        val n = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NOX обновлён до ${BuildConfig.VERSION_NAME}")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Открыть", open)
            .setAutoCancel(true)
            .build()
        postUpdateNotification(context, InstallStatusReceiver.CONFIRM_NOTIFICATION + 1, n)
        if (pending && Build.VERSION.SDK_INT < 34) {
            runCatching { TransferScheduler.ensureRunning(context) }
        }
    }
}

/** Уведомление об обновлении — только если пользователь разрешил уведомления. */
internal fun postUpdateNotification(context: Context, id: Int, n: android.app.Notification) {
    if (Build.VERSION.SDK_INT >= 33 &&
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) return
    try {
        NotificationManagerCompat.from(context).notify(id, n)
    } catch (e: SecurityException) {
        NoxLog.event("update-notification-denied")
    }
}
