package com.nox.offline.ui

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
import androidx.compose.material.icons.rounded.PlayCircle
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
import com.nox.offline.player.PlayerActivity
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
import com.nox.offline.ui.downloads.DownloadsScreen
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
import com.nox.offline.ui.settings.DownloadSettingsContent
import com.nox.offline.ui.settings.SettingsScreen
import com.nox.offline.ui.settings.UpdateBanner
import com.nox.offline.ui.theme.LocalGlassConfig
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.NoxTheme
import com.nox.offline.updates.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab { HOME, DOWNLOADS, PLAYER, SETTINGS }
enum class Route { LIBRARY, APPEARANCE }

/** Внешние запросы к корню: вкладка из уведомления, ссылки из «Поделиться». */
class RootRequests {
    var tab by mutableStateOf<Tab?>(null)
    var sharedText by mutableStateOf<String?>(null)
}

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
        val cfg = LocalGlassConfig.current
        CompositionLocalProvider(LocalWallpaperFrame provides frame, LocalSheets provides sheets) {
            // Слой с содержимым экрана. «Живое» стекло карточек и листов берёт его
            // только в полном режиме; линза нижней панели — всегда (API 29+),
            // чтобы и в экономичном режиме показывать настоящее содержимое под собой.
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
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var route by rememberSaveable { mutableStateOf<Route?>(null) }
    val reduce = LocalGlassConfig.current.reduceMotion
    val frame = LocalWallpaperFrame.current

    // ---------- системные окна выбора ----------
    var pendingExport by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var pendingBackup by remember { mutableStateOf(false to false) }
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importWallpaper) }
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

    fun launchSafely(block: () -> Unit) {
        try { block() } catch (e: ActivityNotFoundException) { AppEvents.notice("На устройстве нет подходящего системного окна") }
    }

    lateinit var actions: NoxActions
    actions = NoxActions(
        openMedia = { item, fromStart -> openInPlayer(context, item.media.id, fromStart); tab = Tab.PLAYER },
        mediaMenu = { item -> mediaMenu(item, vm, sheets, actions, context::startActivity) },
        openLibrary = { route = Route.LIBRARY },
        openDownloads = { route = null; tab = Tab.DOWNLOADS },
        openFilter = { sheets.show("Фильтр и сортировка") { close -> FilterSheet(vm, close) } },
        downloadMenu = { d -> downloadMenu(d, vm, sheets, context::startActivity) },
        confirmCancel = { d ->
            sheets.confirm("Удалить загрузку?",
                "«${d.displayTitle.ifBlank { "Без названия" }}» будет остановлена, скачанная часть удалена. Другие загрузки продолжатся.",
                "Удалить", danger = true) { vm.cancel(d.id) }
        },
        openBatch = { initial -> sheets.show("Несколько ссылок") { close -> BatchAddSheet(vm, initial, close) } },
        openDownloadSettings = { sheets.show("Настройки загрузок") { _ -> DownloadSettingsContent(vm, actions) } },
        openAppearance = { route = Route.APPEARANCE },
        openStorage = { route = null; tab = Tab.SETTINGS },
        pickImport = { launchSafely { pickImport.launch(arrayOf("video/*")) } },
        pickExportFor = { list -> pendingExport = list; launchSafely { pickExport.launch(null) } },
        pickDestination = { launchSafely { pickDestination.launch(null) } },
        pickWallpaper = { launchSafely { pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
        pickBackupExport = { m, p -> pendingBackup = m to p; launchSafely { pickBackupOut.launch(null) } },
        pickBackupImport = { launchSafely { pickBackupIn.launch(null) } },
        requestNotifications = onRequestNotifications,
        openInstallPermission = { launchSafely { context.startActivity(vm.installPermissionIntent()) } },
        copyDiagnostics = {
            scope.launch {
                clipboard.setText(AnnotatedString(vm.diagnostics()))
                AppEvents.notice("Диагностика скопирована")
            }
        },
        back = { route = null },
    )

    // ---------- внешние запросы ----------
    LaunchedEffect(requests.tab) { requests.tab?.let { tab = it; route = null; requests.tab = null } }
    LaunchedEffect(requests.sharedText) {
        val t = requests.sharedText ?: return@LaunchedEffect
        requests.sharedText = null
        route = null; tab = Tab.DOWNLOADS
        // Одна ссылка — сразу та же карточка, что и после «Найти видео».
        if (com.nox.offline.core.LinkParser.links(t).size > 1) actions.openBatch(t) else vm.openShared(com.nox.offline.core.SafeUrl.extract(t) ?: t)
    }
    val pendingBatch by vm.pendingBatch.collectAsState()
    LaunchedEffect(pendingBatch) {
        pendingBatch?.let { vm.pendingBatch.value = null; actions.openBatch(it) }
    }

    BackHandler(enabled = route != null && !sheets.isOpen) { route = null }
    BackHandler(enabled = route == null && tab != Tab.HOME && !sheets.isOpen) { tab = Tab.HOME }

    val barHeight = 72.dp
    val insetsTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val insetsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentPadding = PaddingValues(top = insetsTop + 10.dp, bottom = insetsBottom + barHeight + 36.dp)

    Box(Modifier.fillMaxSize().onSizeChanged { vm.onWindowSize(it.width, it.height) }) {
        // Всё, что под панелью, записывается в слой — его размывает «живое» стекло.
        Box(Modifier.fillMaxSize().backdropSource(layer)) {
            WallpaperLayer(frame)
            Crossfade(targetState = route to tab, animationSpec = if (reduce) snap() else tween(180), label = "screen") { (r, t) ->
                when (r) {
                    Route.LIBRARY -> LibraryScreen(vm, actions, contentPadding)
                    Route.APPEARANCE -> AppearanceScreen(vm, actions, contentPadding)
                    null -> when (t) {
                        Tab.HOME -> HomeScreen(vm, actions, contentPadding)
                        Tab.DOWNLOADS -> DownloadsScreen(vm, actions, contentPadding)
                        Tab.PLAYER -> PlayerTabScreen(vm, actions, contentPadding)
                        Tab.SETTINGS -> SettingsScreen(vm, actions, contentPadding)
                    }
                }
            }
        }
        CompositionLocalProvider(LocalContentBackdrop provides layer) {
            val update by vm.updateState.collectAsState()
            UpdateBanner(update, vm, onOpenSettings = { route = null; tab = Tab.SETTINGS }, modifier = Modifier.align(Alignment.TopCenter))

            // Мягкое затемнение под системной навигацией и плавающей панелью:
            // прокручиваемый текст не просвечивает под кнопками системы.
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(insetsBottom + 56.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to nox().bgDeep.copy(alpha = 0.72f), 1f to nox().bgDeep.copy(alpha = 0.94f))))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                FileTaskBanner(vm)
                NoticeToast()
                GlassBottomBar(
                    items = listOf(
                        BarItem("Главная", Icons.Rounded.Home),
                        BarItem("Загрузки", Icons.Rounded.Download),
                        BarItem("Плеер", Icons.Rounded.PlayCircle),
                        BarItem("Настройки", Icons.Rounded.Settings),
                    ),
                    selected = tab.ordinal,
                    onSelect = { i -> route = null; tab = Tab.entries[i] },
                )
            }
            SheetHost(sheets)
        }
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

private fun downloadMenu(d: DownloadEntity, vm: MainViewModel, sheets: SheetController, start: (Intent) -> Unit) {
    val rechoose = d.status == com.nox.offline.data.db.DownloadStatus.ERROR &&
        d.errorKind in setOf("format-gone", "format-changed", "size-mismatch")
    sheets.actions(d.displayTitle.ifBlank { "Загрузка" }, com.nox.offline.ui.components.statusLabel(d), listOfNotNull(
        if (rechoose) SheetAction("Выбрать качество заново", Icons.Rounded.Edit,
            hint = "Прежний вариант недоступен; части будут удалены после нового выбора") { vm.rechoose(d) } else null,
        SheetAction("Переименовать", Icons.Rounded.Edit, hint = "Файл получит имя при завершении") {
            sheets.show("Название загрузки") { close ->
                RenameSheet(d.displayTitle, allowFile = false) { title, _ -> vm.renameDownload(d.id, title); close() }
            }
        },
        SheetAction("Источник", Icons.Rounded.Language, hint = com.nox.offline.core.SafeUrl.host(d.pageUrl)) {
            try { start(Intent(Intent.ACTION_VIEW, Uri.parse(d.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        },
        SheetAction("Удалить", Icons.Rounded.Delete, danger = true, hint = "Скачанная часть будет удалена") {
            sheets.confirm("Удалить загрузку?", "Скачанная часть «${d.displayTitle}» будет удалена.", "Удалить", danger = true) { vm.cancel(d.id) }
        },
    ))
}

/** Плеер помечен @UnstableApi (Media3); сам вызов Intent от этого не зависит. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun openInPlayer(context: android.content.Context, mediaId: Long, fromStart: Boolean) =
    com.nox.offline.NoxApp.get(context).playback.open(mediaId, fromStart = fromStart)
