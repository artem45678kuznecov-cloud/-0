package com.nox.offline.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.nox.offline.core.NoxLog
import java.io.File
import java.io.FileInputStream

/**
 * Установка через публичный PackageInstaller. Система сама покажет своё
 * окно подтверждения — обойти его нельзя и мы не пытаемся. Об исходе
 * сообщает [InstallStatusReceiver].
 */
class UpdateInstaller(private val context: Context) {

    /** Разрешено ли NOX предлагать установку (Android 8+: «Установка неизвестных приложений»). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Экран системных настроек, где пользователь выдаёт разрешение именно NOX. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(apk: File, versionCode: Int): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                // Явно: подтверждение пользователя обязательно.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("NOX.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 256 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallStatusReceiver::class.java)
                    .setAction(InstallStatusReceiver.ACTION)
                    .putExtra(InstallStatusReceiver.EXTRA_VERSION, versionCode)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pi.intentSender)
            }
            NoxLog.event("update-session-commit", "session" to sessionId, "version" to versionCode)
            return sessionId
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    /** Жива ли ещё сессия (пользователь мог закрыть окно подтверждения). */
    fun sessionAlive(sessionId: Int): Boolean =
        sessionId > 0 && context.packageManager.packageInstaller.getSessionInfo(sessionId) != null
}
