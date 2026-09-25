package com.nox.offline.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.BuildConfig
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.components.GlassCard
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxHeader
import com.nox.offline.ui.components.ScreenTitle
import com.nox.offline.ui.components.SettingRow
import com.nox.offline.ui.components.SettingSwitch
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.theme.Nox

@Composable
fun SettingsScreen(vm: MainViewModel, actions: NoxActions, contentPadding: PaddingValues) {
    val update by vm.updateState.collectAsState()
    val upPrefs by vm.updatePrefs.collectAsState()
    val dl by vm.downloadPrefs.collectAsState()
    val player by vm.playerPrefs.collectAsState()
    val appearance by vm.appearance.collectAsState()
    val allowed by vm.notificationsAllowed.collectAsState()
    val status by vm.status.collectAsState()
    val sheets = LocalSheets.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("header") { NoxHeader(status, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), onStatusClick = actions.openDownloads) }
        item("title") { ScreenTitle("Настройки", "NOX Android ${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 20.dp)) }

        item("updates") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Section("Обновления")
                UpdateSection(update, vm, actions)
                VSpace(6)
                SettingSwitch("Проверять автоматически", "Не чаще раза в 12 часов; без интернета — молча",
                    upPrefs.autoCheck, vm::setAutoCheck)
            }
        }
        item("look") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Section("Оформление")
                SettingRow("Темы, фон и стекло", "Сейчас: ${com.nox.offline.ui.theme.NoxPalettes.presets.firstOrNull { it.id == appearance.preset }?.label ?: "свой цвет"}, " +
                    "${appearance.glassMode.label.lowercase()}", Icons.Rounded.Palette, actions.openAppearance)
            }
        }
        item("downloads") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Section("Загрузки")
                DownloadSettingsContent(vm, actions)
            }
        }
        item("player") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Section("Плеер")
                SettingSwitch("«Картинка в картинке» при выходе",
                    "Если видео играет и вы выходите из плеера, оно продолжится в маленьком окне",
                    player.pipOnLeave, vm::setPipOnLeave)
            }
        }
        item("data") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Section("Данные")
                SettingRow("Импорт видео", "Добавить свои файлы в медиатеку", Icons.Rounded.FileDownload, actions.pickImport)
                SettingRow("Резервная копия", "Медиатека, позиции, очередь, настройки; по желанию — сами видео",
                    Icons.Rounded.Backup, {
                        sheets.actions("Резервная копия", "Выберите, что сохранить, затем — папку.", listOf(
                            com.nox.offline.ui.components.SheetAction("Только данные", Icons.Rounded.Backup,
                                hint = "Записи медиатеки, позиции, очередь, обложки, настройки") { actions.pickBackupExport(false, false) },
                            com.nox.offline.ui.components.SheetAction("Данные и готовые видео", Icons.Rounded.Backup,
                                hint = "Может занять много места") { actions.pickBackupExport(true, false) },
                            com.nox.offline.ui.components.SheetAction("Всё, включая незавершённые загрузки", Icons.Rounded.Backup,
                                hint = "Видео и файлы .part") { actions.pickBackupExport(true, true) },
                        ))
                    })
                SettingRow("Восстановить из копии", "Выберите папку NOX-backup-…", Icons.Rounded.Restore, actions.pickBackupImport)
                Muted("Копию умеет делать эта версия и новее. Установленная ранее v0.1.0 такой копии не создаёт.", size = 12.sp)
            }
        }
        item("notif") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Section("Уведомления")
                SettingRow("Прогресс загрузки в шторке", if (allowed) "Разрешены" else "Выключены — фоновая загрузка работает, но прогресса в шторке не будет",
                    Icons.Rounded.Notifications, if (allowed) null else actions.requestNotifications)
            }
        }
        item("diag") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Section("Диагностика")
                SettingRow("Скопировать диагностику", "Версия, устройство, задания, события. Без cookie и подписанных адресов",
                    Icons.Rounded.BugReport, actions.copyDiagnostics)
            }
        }
        item("about") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Section("О NOX")
                Muted("Офлайн-медиатека. Разбор ссылок — yt-dlp, передача — OkHttp, плеер — Media3. " +
                    "Фоновая загрузка: ${if (Build.VERSION.SDK_INT >= 34) "User-Initiated Data Transfer Job" else "foreground service"}. " +
                    "Не больше трёх загрузок одновременно.", size = 13.sp, color = Nox.TextSecondary)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Nox.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    VSpace(8)
}

/** Настройки загрузок: общие для раздела «Настройки» и кнопки ⚙ на экране загрузчика. */
@Composable
fun DownloadSettingsContent(vm: MainViewModel, actions: NoxActions) {
    val dl by vm.downloadPrefs.collectAsState()
    val sheets = LocalSheets.current
    PreferredQuality(dl.preferredHeight, vm::setPreferredHeight)
    VSpace(6)
    SettingSwitch("Сначала предлагать готовый файл со звуком",
        "Если у ступени качества есть и готовый файл, и отдельные видео и звук, заранее выделять готовый файл. " +
            "Это только предварительный выбор: любой вариант, включая 1440p и выше, можно выбрать в списке — " +
            "видео и звук NOX объединит сам, без перекодирования.",
        dl.preferSingleFile, vm::setPreferSingleFile)
    VSpace(4)
    SettingRow("Папка готовых видео", vm.destinationStatus(), Icons.Rounded.Folder, actions.pickDestination,
        trailing = if (dl.destinationTree.isBlank()) "Выбрать" else "Изменить")
    if (dl.destinationTree.isNotBlank()) {
        SettingRow("Хранить внутри NOX", "Новые видео останутся в NOX; уже перенесённые останутся в папке",
            Icons.Rounded.Folder, { vm.clearDestination() })
        val internal = vm.internalCount()
        if (internal > 0) {
            SettingRow("Перенести готовые видео ($internal)", "Отдельно и только по подтверждению. Идущие загрузки не трогаются",
                Icons.AutoMirrored.Rounded.DriveFileMove, {
                    sheets.confirm("Перенести $internal видео?",
                        "Каждое видео копируется в папку, проверяется и только потом удаляется из NOX. Видео, открытое в плеере, пропускается.",
                        "Перенести") { vm.moveLibraryToDestination() }
                })
        }
    }
    Muted("Незавершённые загрузки (.part) всегда хранятся во временной папке NOX — это надёжнее для докачки. " +
        "Готовые видео в выбранной вами папке не удаляются вместе с приложением.", size = 12.sp)
    VSpace(4)
    Muted("Объединение без перекодирования: H.264/H.265 + AAC → MP4, VP9 + Opus → WebM (Android 10+), AV1 + AAC → MP4 (Android 14+). " +
        "DRM и закрытый контент не обходятся.", size = 12.sp)
}

/**
 * Какое качество выделять заранее в списке вариантов. Не фиксированная
 * панель: список всегда строится из настоящих форматов видео.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PreferredQuality(current: Int, onChange: (Int) -> Unit) {
    Text("Качество по умолчанию", color = Nox.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    VSpace(2)
    Muted("Какой вариант выделять заранее, если он есть у видео. Выбор всё равно за вами.", size = 12.sp, color = Nox.TextSecondary)
    VSpace(8)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        for ((h, label) in listOf(480 to "до 480p", 720 to "до 720p", 1080 to "до 1080p", 1440 to "до 1440p",
            2160 to "до 2160p", 0 to "Наилучшее")) {
            com.nox.offline.ui.components.GlassPill(label, accent = current == h, height = 38.dp, textSize = 14.sp, onClick = { onChange(h) })
        }
    }
}
