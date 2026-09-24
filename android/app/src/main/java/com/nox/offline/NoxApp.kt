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
import com.nox.offline.settings.AppSettings
import com.nox.offline.storage.BackupManager
import com.nox.offline.storage.FileTasks
import com.nox.offline.storage.MediaRelocator
import com.nox.offline.storage.SafStore
import com.nox.offline.storage.VideoImporter
import com.nox.offline.ui.glass.WallpaperController
import com.nox.offline.updates.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Точка сборки приложения: база, настройки, хранилище, резолвер,
 * координатор, обновления, фон. Всё создаётся один раз на процесс.
 */
class NoxApp : Application() {
    lateinit var db: NoxDatabase
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var storage: Storage
        private set
    lateinit var saf: SafStore
        private set
    lateinit var resolver: YtDlpResolver
        private set
    lateinit var coordinator: DownloadCoordinator
        private set
    lateinit var notifications: DownloadNotifications
        private set
    lateinit var relocator: MediaRelocator
        private set
    lateinit var importer: VideoImporter
        private set
    lateinit var backup: BackupManager
        private set
    lateinit var tasks: FileTasks
        private set
    lateinit var wallpaper: WallpaperController
        private set
    lateinit var updates: UpdateRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NoxLog.sink = { line -> Log.d("NOX", line) }
        NoxLog.event("app-start", "version" to BuildConfig.VERSION_NAME, "code" to BuildConfig.VERSION_CODE,
            "sdk" to android.os.Build.VERSION.SDK_INT)
        db = NoxDatabase.build(this)
        settings = AppSettings(this)
        storage = Storage(this)
        saf = SafStore(this)
        resolver = YtDlpResolver(this)
        relocator = MediaRelocator(db, saf, settings)
        val client = HttpDownloader.defaultClient()
        coordinator = DownloadCoordinator(
            db = db, storage = storage, resolver = resolver,
            http = HttpDownloader(client) { line -> NoxLog.event("http", "msg" to line) },
            client = client,
            relocator = relocator,
            splitDefault = { settings.downloads.value.splitTracks },
        )
        notifications = DownloadNotifications(this).also { it.ensureChannel() }
        coordinator.listener = object : DownloadCoordinator.Listener {
            override fun onPaused(e: com.nox.offline.data.db.DownloadEntity) = notifications.showPaused(e)
            override fun onCleared(id: Long) = notifications.clearFor(id)
        }
        importer = VideoImporter(this, db, storage)
        tasks = FileTasks()
        backup = BackupManager(this, db, storage, settings, saf) { e ->
            listOf(coordinator.partFileOf(e), coordinator.videoPartOf(e), coordinator.audioPartOf(e))
        }
        wallpaper = WallpaperController(this, settings)
        updates = UpdateRepository(this, settings, coordinator, client)
        updates.onAppStart()
        // Восстановление после гибели процесса или обновления: всё «в работе» -> в очередь.
        appScope.launch { coordinator.recover() }
    }

    companion object {
        fun get(context: Context): NoxApp = context.applicationContext as NoxApp
    }
}
