package com.nox.offline.ui

import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.ui.components.LibraryItem

/**
 * Действия, которые экраны отдают корню приложения: навигация, листы,
 * подтверждения и системные окна (выбор файлов, «Поделиться»). Экраны
 * остаются простыми и не знают про Activity.
 */
class NoxActions(
    val openMedia: (item: LibraryItem, fromStart: Boolean) -> Unit,
    val mediaMenu: (LibraryItem) -> Unit,
    val openLibrary: () -> Unit,
    val openDownloads: () -> Unit,
    val openFilter: () -> Unit,
    val downloadMenu: (DownloadEntity) -> Unit,
    val confirmCancel: (DownloadEntity) -> Unit,
    val openBatch: (initial: String) -> Unit,
    val openDownloadSettings: () -> Unit,
    val openAppearance: () -> Unit,
    val openStorage: () -> Unit,
    val pickImport: () -> Unit,
    val pickExportFor: (List<LibraryItem>) -> Unit,
    val pickDestination: () -> Unit,
    val pickWallpaper: () -> Unit,
    val pickBackupExport: (includeMedia: Boolean, includeParts: Boolean) -> Unit,
    val pickBackupImport: () -> Unit,
    /** 0.4.0: папка для автоматической копии. */
    val pickAutoBackupFolder: () -> Unit,
    val requestNotifications: () -> Unit,
    val openInstallPermission: () -> Unit,
    val copyDiagnostics: () -> Unit,
    val back: () -> Unit,
)
