package com.nox.offline

import android.app.Application
import android.content.Context
import android.util.Log
import com.nox.offline.core.NoxLog
import com.nox.offline.core.Storage
import com.nox.offline.data.db.NoxDatabase
import com.nox.offline.downloader.DownloadCoordinator
import com.nox.offline.downloader.DownloadNotifications
import com.nox.offline.downloader.HttpDownloader
import com.nox.offline.downloader.YtDlpResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Точка сборки приложения: база, хранилище, резолвер, координатор.
 * Всё создаётся один раз на процесс и живёт столько же, сколько он.
 */
class NoxApp : Application() {
    lateinit var db: NoxDatabase
        private set
    lateinit var storage: Storage
        private set
    lateinit var resolver: YtDlpResolver
        private set
    lateinit var coordinator: DownloadCoordinator
        private set
    lateinit var notifications: DownloadNotifications
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NoxLog.sink = { line -> Log.d("NOX", line) }
        NoxLog.event("app-start", "version" to BuildConfig.VERSION_NAME, "sdk" to android.os.Build.VERSION.SDK_INT)
        db = NoxDatabase.build(this)
        storage = Storage(this)
        resolver = YtDlpResolver(this)
        val client = HttpDownloader.defaultClient()
        coordinator = DownloadCoordinator(
            db = db, storage = storage, resolver = resolver,
            http = HttpDownloader(client) { line -> NoxLog.event("http", "msg" to line) },
            client = client,
        )
        notifications = DownloadNotifications(this).also { it.ensureChannel() }
        // Восстановление после гибели процесса: всё «в работе» -> в очередь.
        appScope.launch { coordinator.recover() }
    }

    companion object {
        fun get(context: Context): NoxApp = context.applicationContext as NoxApp
    }
}
