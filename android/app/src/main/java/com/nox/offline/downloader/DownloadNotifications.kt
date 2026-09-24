package com.nox.offline.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nox.offline.MainActivity
import com.nox.offline.R
import com.nox.offline.core.Format
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadStatus

/**
 * Уведомления о ходе загрузки.
 *
 *   NOX • Скачивание
 *   Повар-боец Сома
 *   1.4 ГБ / 7.9 ГБ
 *   3.8 МБ/с • осталось 28 мин
 *   [прогресс]   Пауза   Отменить
 *
 * Одно уведомление принадлежит носителю (job / foreground service) —
 * это первая активная загрузка. Остальные активные получают свои
 * обычные уведомления с теми же действиями.
 */
class DownloadNotifications(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "nox_downloads"
        const val PRIMARY_ID = 41
        const val EXTRA_BASE_ID = 1000
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_downloads_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DOWNLOADS)
        }
        return PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(id: Long, action: String, requestBase: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, (requestBase * 100_000 + id).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun base(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.nox_accent))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    /** Носитель уже поднят, а разобрать ссылку ещё не успели. */
    fun idle(): Notification = base()
        .setContentTitle(context.getString(R.string.notification_title_waiting))
        .setContentText("Подготовка загрузки…")
        .setOngoing(true)
        .build()

    fun forDownload(e: DownloadEntity, othersCount: Int = 0): Notification {
        val b = base()
        val title = when (e.status) {
            DownloadStatus.PAUSED -> R.string.notification_title_paused
            DownloadStatus.QUEUED -> R.string.notification_title_waiting
            else -> R.string.notification_title_downloading
        }
        b.setContentTitle(context.getString(title))
        val name = e.title.ifBlank { "Подготовка…" }
        val suffix = if (othersCount > 0) "  •  ещё $othersCount" else ""
        b.setSubText(name.take(60) + suffix)
        val bytes = if (e.totalBytes > 0) "${Format.bytes(e.downloadedBytes)} / ${Format.bytes(e.totalBytes)}"
        else Format.bytes(e.downloadedBytes)
        val line2 = when (e.status) {
            DownloadStatus.RESOLVING -> "Поиск прямой ссылки…"
            DownloadStatus.PROCESSING -> "Завершение файла…"
            DownloadStatus.PAUSED -> "Пауза"
            DownloadStatus.QUEUED -> "В очереди"
            else -> if (e.speedBps > 0) "${Format.speed(e.speedBps)} • осталось ${Format.eta(e.etaSec)}" else "Соединение…"
        }
        b.setContentText(bytes)
        b.setStyle(NotificationCompat.BigTextStyle().bigText("$bytes\n$line2").setBigContentTitle(name))
        if (e.totalBytes > 0) b.setProgress(100, e.progressPercent, false)
        else b.setProgress(0, 0, e.status == DownloadStatus.DOWNLOADING || e.status == DownloadStatus.RESOLVING)
        b.setOngoing(e.status != DownloadStatus.PAUSED)
        if (e.status == DownloadStatus.PAUSED) {
            b.addAction(0, context.getString(R.string.notification_action_resume),
                action(e.id, NotificationActionReceiver.ACTION_RESUME, 2))
        } else {
            b.addAction(0, context.getString(R.string.notification_action_pause),
                action(e.id, NotificationActionReceiver.ACTION_PAUSE, 1))
        }
        b.addAction(0, context.getString(R.string.notification_action_cancel),
            action(e.id, NotificationActionReceiver.ACTION_CANCEL, 3))
        return b.build()
    }

    fun extraId(e: DownloadEntity): Int = (EXTRA_BASE_ID + e.id).toInt()

    fun post(id: Int, n: Notification) {
        if (!canPost()) return
        try { manager.notify(id, n) } catch (_: SecurityException) { }
    }

    fun cancel(id: Int) {
        try { manager.cancel(id) } catch (_: Exception) { }
    }

    /** Убрать все дополнительные уведомления, кроме указанных. */
    fun cancelExtrasExcept(keep: Set<Int>, known: Collection<DownloadEntity>) {
        for (e in known) {
            val id = extraId(e)
            if (id !in keep) cancel(id)
        }
    }

    fun hint(text: String) {
        val n = base()
            .setContentTitle("NOX")
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        post(PRIMARY_ID + 1, n)
    }
}
