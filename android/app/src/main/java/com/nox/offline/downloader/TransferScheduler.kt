package com.nox.offline.downloader

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog

/**
 * Кто понесёт передачу.
 *
 * Android 14+ (API 34): User-Initiated Data Transfer Job. Пользователь сам
 * нажал «Скачать», дальше идёт потенциально длинная передача — ровно тот
 * случай, под который UIDT и создан. Ограничения по времени у него нет,
 * пока показано уведомление и есть сеть.
 *
 * Android 13 и ниже: foreground service типа dataSync. На этих версиях
 * лимита на dataSync нет.
 *
 * Планировать UIDT можно только пока приложение видно пользователю (или
 * из действия в уведомлении). Поэтому [ensureRunning] зовётся из
 * Activity и из NotificationActionReceiver, а не из произвольного
 * фонового колбэка.
 */
object TransferScheduler {
    const val JOB_ID = 7001

    sealed class Result {
        object AlreadyRunning : Result()
        object Scheduled : Result()
        data class Failed(val reason: String) : Result()
    }

    fun ensureRunning(context: Context): Result {
        val app = NoxApp.get(context)
        val coordinator = app.coordinator
        if (coordinator.runnerAttached) {
            coordinator.pump()
            return Result.AlreadyRunning
        }
        return if (Build.VERSION.SDK_INT >= 34) scheduleUidt(context) else startForeground(context)
    }

    private fun scheduleUidt(context: Context): Result {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pending = scheduler.getPendingJob(JOB_ID)
        if (pending != null) {
            NoxLog.event("uidt-pending", "job" to JOB_ID)
            return Result.AlreadyRunning
        }
        val estimated = try {
            NoxApp.get(context).coordinator.let { c ->
                kotlinx.coroutines.runBlocking { c.remainingKnownBytes() }
            }
        } catch (e: Exception) { 0L }
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, TransferJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        if (Build.VERSION.SDK_INT >= 34) {
            builder.setUserInitiated(true)
            val unknown = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
            builder.setEstimatedNetworkBytes(if (estimated > 0) estimated else unknown, unknown)
        }
        return try {
            val code = scheduler.schedule(builder.build())
            if (code == JobScheduler.RESULT_SUCCESS) {
                NoxLog.event("uidt-scheduled", "estimated" to estimated)
                Result.Scheduled
            } else {
                NoxLog.event("uidt-schedule-failed", "code" to code)
                Result.Failed("JobScheduler вернул $code")
            }
        } catch (e: Exception) {
            NoxLog.event("uidt-schedule-error", "error" to "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startForeground(context: Context): Result {
        return try {
            val intent = Intent(context, TransferForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
            NoxLog.event("fgs-start-requested")
            Result.Scheduled
        } catch (e: Exception) {
            NoxLog.event("fgs-start-error", "error" to "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
