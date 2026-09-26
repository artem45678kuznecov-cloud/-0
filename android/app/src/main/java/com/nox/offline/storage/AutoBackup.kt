package com.nox.offline.storage

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nox.offline.NoxApp
import com.nox.offline.core.NoxLog
import com.nox.offline.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Автоматическая копия записей и настроек (без видео) в папку, которую
 * выбрал пользователь. Не облако. Хранит [com.nox.offline.settings.BackupPrefs.keep]
 * последних копий «NOX-auto-…», старые удаляет — только свои.
 * Срок проверяется при запуске NOX и периодической задачей JobScheduler;
 * точного времени Android не обещает.
 */
class AutoBackup(private val context: Context, private val settings: AppSettings, private val backup: BackupManager,
                 private val saf: SafStore) {
    private val lock = Mutex()

    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        val b = settings.backup.value
        return b.autoEnabled && b.tree.isNotBlank() && now - b.lastAt >= b.periodDays * DAY_MS - SLACK_MS
    }

    /** Сделать копию, если пришёл срок (или [force]). Ошибка записывается понятным текстом. */
    suspend fun runIfDue(force: Boolean = false): Boolean = lock.withLock {
        val b = settings.backup.value
        if (!b.autoEnabled || b.tree.isBlank()) return false
        if (!force && !isDue()) return false
        if (!saf.isWritable(b.tree)) {
            settings.updateBackup { it.copy(lastError = "Нет доступа к папке «${b.treeLabel}» — выберите её заново") }
            NoxLog.event("autobackup-no-access")
            return false
        }
        return try {
            backup.export(Uri.parse(b.tree), includeMedia = false, includeParts = false, prefix = PREFIX) { _, _, _ -> }
            prune(Uri.parse(b.tree), b.keep)
            settings.updateBackup { it.copy(lastAt = System.currentTimeMillis(), lastError = "") }
            NoxLog.event("autobackup-done")
            true
        } catch (e: Exception) {
            settings.updateBackup { it.copy(lastError = "Копия не сделана: ${e.message ?: e.javaClass.simpleName}") }
            NoxLog.event("autobackup-error", "error" to e.javaClass.simpleName)
            false
        }
    }

    /** Удалить самые старые автокопии сверх лимита. Ручные копии и чужие папки не трогаются. */
    private fun prune(tree: Uri, keep: Int) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return
        val autos = root.listFiles().filter { it.isDirectory && (it.name ?: "").startsWith("$PREFIX-") }.sortedByDescending { it.name }
        for (old in autos.drop(keep.coerceAtLeast(1))) runCatching { old.delete() }
    }

    fun schedule() {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val b = settings.backup.value
        if (!b.autoEnabled || b.tree.isBlank()) { js.cancel(JOB_ID); return }
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJobService::class.java))
            .setPeriodic(b.periodDays * DAY_MS)
            .setRequiresStorageNotLow(true)
            .build()
        runCatching { js.schedule(info) }.onFailure { NoxLog.event("autobackup-schedule-error", "error" to it.javaClass.simpleName) }
    }

    companion object {
        const val PREFIX = "NOX-auto"
        const val JOB_ID = 7101
        const val DAY_MS = 24L * 3600 * 1000
        private const val SLACK_MS = 60L * 60 * 1000
    }
}

/** Периодическая задача: копия, если подошёл срок. */
class AutoBackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            runCatching { NoxApp.get(this@AutoBackupJobService).autoBackup.runIfDue() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
