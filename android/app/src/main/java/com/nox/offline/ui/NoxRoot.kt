@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.ui

import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nox.offline.ui.downloads.CleanupScreen
import com.nox.offline.ui.downloads.DownloaderScreen
import com.nox.offline.ui.downloads.PlaylistScreen
import com.nox.offline.ui.downloads.QueueScreen
import com.nox.offline.ui.home.CollectionScreen
import com.nox.offline.ui.home.CollectionsListScreen
import com.nox.offline.ui.library.LibraryViewModel
import com.nox.offline.ui.settings.AboutPage
import com.nox.offline.ui.settings.BackupPage
import com.nox.offline.ui.settings.DownloadSettingsPage
import com.nox.offline.ui.settings.LicensesPage
import com.nox.offline.ui.settings.PrivacyPage
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nox.offline.ui.theme.nox
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.AppEvents
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.ui.components.GlassIconButton
import com.nox.offline.ui.components.GlassPill
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.LibraryItem
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxProgress
import com.nox.offline.ui.components.SheetAction
import com.nox.offline.ui.components.SheetController
import com.nox.offline.ui.components.SheetHost
import com.nox.offline.ui.downloads.BatchAddSheet
import com.nox.offline.ui.glass.BarItem
import com.nox.offline.ui.glass.GlassBottomBar
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.glass.LocalContentBackdrop
import com.nox.offline.ui.glass.LocalWallpaperFrame
import com.nox.offline.ui.glass.WallpaperLayer
import com.nox.offline.ui.glass.backdropSource
import com.nox.offline.ui.home.HomeScreen
import com.nox.offline.ui.library.FilterSheet
import com.nox.offline.ui.library.LibraryScreen
import com.nox.offline.ui.player.PlayerTabScreen
import com.nox.offline.ui.components.RenameSheet
import com.nox.offline.ui.settings.AppearanceScreen
import com.nox.offline.ui.settings.SettingsScreen
import com.nox.offline.ui.settings.UpdateBanner
import com.nox.offline.ui.theme.LocalGlassConfig
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.NoxTheme
import com.nox.offline.updates.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Внешние запросы к корню: вкладка из уведомления, ссылки из «Поделиться». */
class RootRequests {
    var tab by mutableStateOf<Tab?>(null)
    var sharedText by mutableStateOf<String?>(null)
}

/** Системные окна выбора (изображение, файл субтитров) для экранов глубже корня. */
class Pickers(val pickImage: ((Uri) -> Unit) -> Unit, val pickSubtitle: ((Uri) -> Unit) -> Unit)

val LocalPickers = staticCompositionLocalOf<Pickers> { error("Pickers не предоставлены") }

@Composable
fun NoxRoot(
    vm: MainViewModel,
    requests: RootRequests,
    onRequestNotifications: () -> Unit,
) {
    val appearance by vm.appearance.collectAsState()
    NoxTheme(appearance) {
        val sheets = remember { SheetController() }
        val frame by vm.wallpaperFrame.collectAsState()
        CompositionLocalProvider(LocalWallpaperFrame provides frame, LocalSheets provides sheets) {
            // Слой с содержимым экрана: его размывает «живое» стекло листов и линза панели (API 29+).
            val layer: GraphicsLayer? = if (Build.VERSION.SDK_INT >= 29) rememberGraphicsLayer() else null
            RootContent(vm, requests, sheets, layer, onRequestNotifications)
        }
    }
}

@Composable
private fun RootContent(
    vm: MainViewModel,
    requests: RootRequests,
    sheets: SheetController,
    layer: GraphicsLayer?,
    onRequestNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val lib: LibraryViewModel = viewModel()
    val nav = vm.nav
    val reduce = LocalGlassConfig.current.reduceMotion
    val frame = LocalWallpaperFrame.current

    // ---------- системные окна выбора ----------
    var pendingExport by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var pendingBackup by remember { mutableStateOf(false to false) }
    var imageCallback by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    var subtitleCallback by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importWallpaper) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u -> imageCallback?.invoke(u) }; imageCallback = null
    }
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> subtitleCallback?.invoke(u) }; subtitleCallback = null
    }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importVideos(uris)
    }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setDestination) }
    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && pendingExport.isNotEmpty()) vm.exportMedia(pendingExport.map { it.media }, uri)
        pendingExport = emptyList()
    }
    val pickBackupOut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.backupExport(it, pendingBackup.first, pendingBackup.second) }
    }
    val pickBackupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::backupImport) }
    val pickAutoBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setAutoBackupFolder) }

    fun launchSafely(block: () -> Unit) {
        try { block() } catch (e: ActivityNotFoundException) { AppEvents.notice("На устройстве нет подходящего системного окна") }
    }

    val pickers = remember {
        Pickers(
            pickImage = { cb -> imageCallback = cb; launchSafely { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
            pickSubtitle = { cb ->
                subtitleCallback = cb
                // Многие файловые менеджеры отдают .srt как text/plain или application/octet-stream.
                launchSafely { pickSubtitle.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream")) }
            },
        )
    }

    lateinit var actions: NoxActions
    actions = NoxActions(
        openMedia = { item, fromStart -> openInPlayer(context, item.media.id, fromStart); nav.select(Tab.PLAYER) },
        mediaMenu = { item -> mediaMenu(item, vm, sheets, actions, context::startActivity) },
        openLibrary = { nav.open(Page.AllVideos, Tab.HOME) },
        openDownloads = { nav.root(Tab.DOWNLOADS) },
        openFilter = { sheets.show("Фильтр и сортировка") { close -> FilterSheet(vm, close) } },
        downloadMenu = { d -> downloadMenu(d, vm, sheets, nav, context::startActivity) },
        confirmCancel = { d ->
            sheets.confirm("Удалить загрузку?",
                "«${d.displayTitle.ifBlank { "Без названия" }}» будет остановлена, скачанная часть удалена. Другие загрузки продолжатся.",
                "Удалить", danger = true) { vm.cancel(d.id) }
        },
        openBatch = { initial -> sheets.show("Несколько ссылок") { close -> BatchAddSheet(vm, initial, close) } },
        openDownloadSettings = { nav.open(Page.DownloadSettings, Tab.SETTINGS) },
        openAppearance = { nav.open(Page.Appearance, Tab.SETTINGS) },
        openStorage = { nav.open(Page.Cleanup, Tab.DOWNLOADS) },
        pickImport = { launchSafely { pickImport.launch(arrayOf("video/*", "audio/*")) } },
        pickExportFor = { list -> pendingExport = list; launchSafely { pickExport.launch(null) } },
        pickDestination = { launchSafely { pickDestination.launch(null) } },
        pickWallpaper = { launchSafely { pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
        pickBackupExport = { m, p -> pendingBackup = m to p; launchSafely { pickBackupOut.launch(null) } },
        pickBackupImport = { launchSafely { pickBackupIn.launch(null) } },
        pickAutoBackupFolder = { launchSafely { pickAutoBackup.launch(null) } },
        requestNotifications = onRequestNotifications,
        openInstallPermission = { launchSafely { context.startActivity(vm.installPermissionIntent()) } },
        copyDiagnostics = {
            scope.launch {
                clipboard.setText(AnnotatedString(vm.diagnostics()))
                AppEvents.notice("Диагностика скопирована")
            }
        },
        back = { nav.back() },
    )

    // ---------- внешние запросы ----------
    LaunchedEffect(requests.tab) { requests.tab?.let { nav.select(it); requests.tab = null } }
    LaunchedEffect(requests.sharedText) {
        val t = requests.sharedText ?: return@LaunchedEffect
        requests.sharedText = null
        // Одна ссылка — сразу та же карточка, что и после «Найти видео».
        if (com.nox.offline.core.LinkParser.links(t).size > 1) { nav.root(Tab.DOWNLOADS); actions.openBatch(t) }
        else {
            nav.open(Page.Downloader, Tab.DOWNLOADS)
            vm.openShared(com.nox.offline.core.SafeUrl.extract(t) ?: t)
        }
    }
    val pendingBatch by vm.pendingBatch.collectAsState()
    LaunchedEffect(pendingBatch) {
        pendingBatch?.let { vm.pendingBatch.value = null; actions.openBatch(it) }
    }

    BackHandler(enabled = nav.canGoBack && !sheets.isOpen) { nav.back() }

    val barHeight = 62.dp
    val insetsTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val insetsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Последний блок прокручивается целиком над панелью.
    val contentPadding = PaddingValues(top = insetsTop + 4.dp, bottom = insetsBottom + barHeight + 28.dp)

    CompositionLocalProvider(LocalPickers provides pickers) {
        Box(Modifier.fillMaxSize().onSizeChanged { vm.onWindowSize(it.width, it.height) }) {
            // Всё, что под панелью, записывается в слой — его размывает «живое» стекло.
            Box(Modifier.fillMaxSize().backdropSource(layer)) {
                WallpaperLayer(frame)
                val page = nav.current
                Crossfade(targetState = nav.tab to page, animationSpec = if (reduce) snap() else tween(180), label = "screen") { (_, p) ->
                    Screen(p, vm, lib, nav, actions, contentPadding, sheets, context)
                }
            }
            CompositionLocalProvider(LocalContentBackdrop provides layer) {
                val update by vm.updateState.collectAsState()
                UpdateBanner(update, vm, onOpenSettings = { nav.root(Tab.SETTINGS) }, modifier = Modifier.align(Alignment.TopCenter))

                // Мягкое затемнение под системной навигацией и панелью: текст не просвечивает под кнопками.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(insetsBottom + 48.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to nox().bgDeep.copy(alpha = 0.66f),
                        1f to nox().bgDeep.copy(alpha = 0.92f))))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                    FileTaskBanner(vm)
                    NoticeToast()
                    GlassBottomBar(
                        items = listOf(
                            BarItem("Главная", Icons.Rounded.Home),
                            BarItem("Загрузки", Icons.Rounded.Download),
                            BarItem("Плеер", Icons.Rounded.PlayCircleOutline),
                            BarItem("Настройки", Icons.Rounded.Settings),
                        ),
                        selected = nav.tab.ordinal,
                        onSelect = { i -> nav.select(Tab.entries[i]) },
                        height = barHeight,
                    )
                }
                SheetHost(sheets)
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Screen(page: Page, vm: MainViewModel, lib: LibraryViewModel, nav: Nav, actions: NoxActions, padding: PaddingValues,
                   sheets: SheetController, context: android.content.Context) {
    when (page) {
        Page.Home -> HomeScreen(lib, nav, padding, onPlayMedia = { id -> openInPlayer(context, id, false); nav.select(Tab.PLAYER) })
        is Page.Collection -> CollectionScreen(page.id, lib, vm, nav, padding)
        is Page.Collections -> CollectionsListScreen(page.kind, lib, nav, padding)
        Page.AllVideos -> LibraryScreen(vm, actions, padding)
        Page.Queue -> QueueScreen(vm, nav, padding, onDownloadMenu = actions.downloadMenu, onConfirmCancel = actions.confirmCancel,
            onOpenCompleted = { d ->
                vm.viewModelScope.launch {
                    val m = vm.mediaFor(d)
                    if (m == null) AppEvents.notice("Видео этой загрузки нет в медиатеке — его удалили или перенесли")
                    else { openInPlayer(context, m.id, false); nav.select(Tab.PLAYER) }
                }
            })
        Page.Downloader -> DownloaderScreen(vm, nav, padding, onBatch = actions.openBatch)
        is Page.Playlist -> PlaylistScreen(page.url, vm, nav, padding)
        Page.Cleanup -> CleanupScreen(vm, nav, padding)
        Page.Player -> PlayerTabScreen(vm, lib, nav, padding)
        Page.Settings -> SettingsScreen(vm, actions, nav, padding)
        Page.Appearance -> AppearanceScreen(vm, actions, padding)
        Page.DownloadSettings -> DownloadSettingsPage(vm, actions, nav, padding)
        Page.Backup -> BackupPage(vm, actions, nav, padding)
        Page.About, Page.Diagnostics -> AboutPage(vm, actions, nav, padding)
        Page.Licenses -> LicensesPage(nav, padding)
        Page.Privacy -> PrivacyPage(nav, padding)
    }
}

/** Прогресс экспорта, импорта, переноса или резервной копии. */
@Composable
private fun FileTaskBanner(vm: MainViewModel) {
    val task by vm.fileTask.collectAsState()
    val t = task ?: return
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(22.dp), style = GlassStyles.Sheet,
        contentPadding = PaddingValues(14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Nox.TextPrimary, fontSize = 15.sp)
                    Muted(t.error ?: t.detail, size = 12.sp, color = if (t.error != null) Nox.Danger else Nox.TextSecondary, maxLines = 2)
                }
                HSpace(8)
                if (t.finished) GlassIconButton(Icons.Rounded.Close, "Скрыть", vm::dismissFileTask, size = 36.dp, iconSize = 18.dp)
                else GlassPill("Отмена", height = 36.dp, textSize = 13.sp, onClick = vm::cancelFileTask)
            }
            if (!t.finished) {
                NoxProgress(t.fraction, Modifier.padding(top = 8.dp),
                    kind = if (t.total > 0) com.nox.offline.ui.components.ProgressKind.DETERMINATE else com.nox.offline.ui.components.ProgressKind.INDETERMINATE)
            }
        }
    }
}

/** Короткие сообщения из фоновых частей: появляются на 3 секунды. */
@Composable
private fun NoticeToast() {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        AppEvents.notices.collect { msg ->
            text = msg
            delay(3200)
            if (text == msg) text = null
        }
    }
    val t = text ?: return
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(20.dp), style = GlassStyles.Sheet,
        onClick = { text = null }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Text(t, color = Nox.TextPrimary, fontSize = 14.sp)
    }
}

private fun mediaMenu(item: LibraryItem, vm: MainViewModel, sheets: SheetController, actions: NoxActions, start: (Intent) -> Unit) {
    val m = item.media
    val inProgress = item.playback?.inProgress == true
    sheets.actions(m.title, item.meta, listOfNotNull(
        SheetAction(if (inProgress) "Продолжить" else "Смотреть", Icons.Rounded.PlayArrow) { actions.openMedia(item, false) },
        if (item.playback != null) SheetAction("Начать сначала", Icons.Rounded.Replay) { actions.openMedia(item, true) } else null,
        SheetAction("Переименовать", Icons.Rounded.Edit) {
            sheets.show("Переименовать") { close ->
                RenameSheet(m.title, allowFile = true) { title, renameFile -> vm.renameMedia(m, title, renameFile); close() }
            }
        },
        SheetAction("Экспортировать", Icons.Rounded.SaveAlt, hint = "Копия в выбранную папку") { actions.pickExportFor(listOf(item)) },
        SheetAction("Поделиться файлом", Icons.Rounded.IosShare) {
            try { start(vm.shareIntent(m)) } catch (e: Exception) { AppEvents.notice("Не удалось поделиться: ${e.message}") }
        },
        if (m.pageUrl.isNotBlank()) SheetAction("Источник", Icons.Rounded.Language, hint = com.nox.offline.core.SafeUrl.host(m.pageUrl)) {
            try { start(Intent(Intent.ACTION_VIEW, Uri.parse(m.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        } else null,
        SheetAction("Удалить", Icons.Rounded.Delete, danger = true) {
            sheets.confirm("Удалить видео?", "«${m.title}» будет удалено с устройства вместе с позицией просмотра.", "Удалить", danger = true) {
                vm.deleteMedia(m)
            }
        },
    ))
}

private fun downloadMenu(d: DownloadEntity, vm: MainViewModel, sheets: SheetController, nav: Nav, start: (Intent) -> Unit) {
    val st = d.status
    val rechoose = st == com.nox.offline.data.db.DownloadStatus.ERROR &&
        d.errorKind in setOf("format-gone", "format-changed", "size-mismatch")
    val waiting = st == com.nox.offline.data.db.DownloadStatus.QUEUED || st == com.nox.offline.data.db.DownloadStatus.PAUSED ||
        st == com.nox.offline.data.db.DownloadStatus.ERROR
    sheets.actions(d.displayTitle.ifBlank { "Загрузка" }, com.nox.offline.ui.components.statusLabel(d), listOfNotNull(
        if (waiting) SheetAction("Скачать следующим", Icons.Rounded.VerticalAlignTop, hint = "То же задание, скачанные части сохраняются") {
            vm.playNext(listOf(d.id))
        } else null,
        if (rechoose) SheetAction("Выбрать качество заново", Icons.Rounded.Edit,
            hint = "Прежний вариант недоступен; части будут удалены после нового выбора") { vm.rechoose(d); nav.open(Page.Downloader, Tab.DOWNLOADS) } else null,
        if (st == com.nox.offline.data.db.DownloadStatus.COMPLETED && d.subtitleError.isNotBlank())
            SheetAction("Повторить субтитры", Icons.Rounded.Replay, hint = d.subtitleError.take(80)) { vm.retrySubtitles(d.id) } else null,
        if (st != com.nox.offline.data.db.DownloadStatus.COMPLETED) SheetAction("Переименовать", Icons.Rounded.Edit, hint = "Файл получит имя при завершении") {
            sheets.show("Название загрузки") { close ->
                RenameSheet(d.displayTitle, allowFile = false) { title, _ -> vm.renameDownload(d.id, title); close() }
            }
        } else null,
        SheetAction("Источник", Icons.Rounded.Language, hint = com.nox.offline.core.SafeUrl.host(d.pageUrl)) {
            try { start(Intent(Intent.ACTION_VIEW, Uri.parse(d.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        },
        if (st == com.nox.offline.data.db.DownloadStatus.COMPLETED) SheetAction("Убрать из списка", Icons.Rounded.Close, hint = "Видео останется в медиатеке") {
            vm.removeMany(listOf(d.id))
        } else SheetAction("Удалить", Icons.Rounded.Delete, danger = true, hint = "Скачанная часть будет удалена") {
            sheets.confirm("Удалить загрузку?", "Скачанная часть «${d.displayTitle}» будет удалена.", "Удалить", danger = true) { vm.cancel(d.id) }
        },
    ))
}

/** Плеер помечен @UnstableApi (Media3); сам вызов Intent от этого не зависит. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun openInPlayer(context: android.content.Context, mediaId: Long, fromStart: Boolean) =
    com.nox.offline.NoxApp.get(context).playback.open(mediaId, fromStart = fromStart)
