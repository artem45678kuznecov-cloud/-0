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
    /** 0.4.0: коллекции и сериалы. */
    lateinit var library: com.nox.offline.library.LibraryRepository
        private set
    /** 0.4.0: главы, виртуальные серии, закладки. */
    lateinit var markup: com.nox.offline.library.MarkupRepository
        private set
    lateinit var subtitles: com.nox.offline.subtitles.SubtitleRepository
        private set
    lateinit var network: com.nox.offline.downloader.NetworkGate
        private set
    lateinit var autoBackup: com.nox.offline.storage.AutoBackup
        private set

    /** Версия Android и декодеры телефона — для каталога вариантов качества. */
    val deviceCaps: com.nox.offline.downloader.catalog.DeviceCaps by lazy { com.nox.offline.downloader.catalog.AndroidDeviceCaps() }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 0.4.0: единственный владелец воспроизведения (создаётся при первом обращении). */
    @get:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    val playback: com.nox.offline.player.PlaybackHub by lazy { com.nox.offline.player.PlaybackHub(this) }

    /** Короткая фоновая запись в базу, которая не должна зависеть от экрана. */
    fun appScopeLaunch(block: suspend () -> Unit) {
        appScope.launch { runCatching { block() }.onFailure { NoxLog.event("bg-error", "error" to it.javaClass.simpleName) } }
    }

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
        library = com.nox.offline.library.LibraryRepository(db, java.io.File(storage.covers, "Collections"),
            getSharedPreferences("nox_library", MODE_PRIVATE))
        markup = com.nox.offline.library.MarkupRepository(db)
        subtitles = com.nox.offline.subtitles.SubtitleRepository(db, storage.subtitles)
        network = com.nox.offline.downloader.NetworkGate(this) { settings.downloads.value.wifiOnly }
        val limiter = com.nox.offline.downloader.SpeedLimiter { settings.downloads.value.speedLimitKbps * 1024L }
        coordinator = DownloadCoordinator(
            db = db, storage = storage, resolver = resolver,
            http = HttpDownloader(client) { line -> NoxLog.event("http", "msg" to line) },
            client = client,
            relocator = relocator,
            splitDefault = { settings.downloads.value.splitTracks },
            policy = DownloadCoordinator.Policy(
                concurrency = { settings.downloads.value.concurrency },
                network = network,
                limiter = limiter,
                externalTarget = { settings.downloads.value.destinationTree.isNotBlank() },
                externalFree = { settings.downloads.value.destinationTree.let { if (it.isBlank()) -1L else saf.freeBytes(it) } },
            ),
        )
        coordinator.subtitleStore = subtitles
        coordinator.libraryHook = object : DownloadCoordinator.LibraryHook {
            override suspend fun onMediaAdded(mediaId: Long, e: com.nox.offline.data.db.DownloadEntity) {
                library.onDownloaded(mediaId, e.sourceKey, e.collectionId, e.collectionPosition, e.pageUrl)
            }
        }
        network.start()
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
        autoBackup = com.nox.offline.storage.AutoBackup(this, settings, backup, saf)
        wallpaper = WallpaperController(this, settings)
        updates = UpdateRepository(this, settings, coordinator, client)
        updates.onAppStart()
        // Восстановление после гибели процесса или обновления: всё «в работе» -> в очередь.
        appScope.launch { coordinator.recover() }
        appScope.launch { runCatching { library.ensureDefaults() } }
        // Автокопия: срок проверяется и при запуске (JobScheduler не обещает точного времени).
        appScope.launch { runCatching { autoBackup.schedule(); autoBackup.runIfDue() } }
    }

    companion object {
        fun get(context: Context): NoxApp = context.applicationContext as NoxApp
    }
}
