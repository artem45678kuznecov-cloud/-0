package com.nox.offline.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.BuildConfig
import com.nox.offline.settings.AutoNext
import com.nox.offline.settings.BackupPrefs
import com.nox.offline.settings.DownloadPrefs
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.Page
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.SheetController
import com.nox.offline.ui.kit.AmberSwitch
import com.nox.offline.ui.kit.ChoiceRow
import com.nox.offline.ui.kit.InlineNote
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ScreenHeading
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.SettingLine
import com.nox.offline.ui.kit.TileButton
import com.nox.offline.ui.theme.Nox

@Composable
private fun PageFrame(title: String, subtitle: String?, nav: Nav, padding: PaddingValues,
                      content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(contentPadding = padding) {
        item {
            Spacer(Modifier.height(8.dp))
            ScreenHeading(title, subtitle, onBack = { nav.back() })
            SectionGap()
        }
        content()
    }
}

/** Загрузки подробно: папка, качество по умолчанию, 1/2/3, Wi‑Fi, предел скорости, уведомления. */
@Composable
fun DownloadSettingsPage(vm: MainViewModel, actions: NoxActions, nav: Nav, padding: PaddingValues) {
    val dl by vm.downloadPrefs.collectAsState()
    val allowed by vm.notificationsAllowed.collectAsState()
    val sheets = LocalSheets.current
    PageFrame("Загрузки", "Очередь, сеть и место", nav, padding) {
        item {
            Section("Папка готовых видео", icon = Icons.Rounded.Folder) {
                SettingLine(Icons.Rounded.Folder, vm.destinationStatus(), "Незавершённые части (.part) всегда хранятся во временной папке NOX",
                    onClick = actions.pickDestination)
                if (dl.destinationTree.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    SettingLine(Icons.Rounded.FolderOff, "Хранить внутри NOX", "Новые видео останутся в NOX; перенесённые — в папке",
                        onClick = { vm.clearDestination() })
                    val internal = vm.internalCount()
                    if (internal > 0) {
                        Spacer(Modifier.height(6.dp))
                        SettingLine(Icons.AutoMirrored.Rounded.DriveFileMove, "Перенести готовые видео ($internal)",
                            "Только по подтверждению. Идущие загрузки не трогаются", onClick = {
                                sheets.confirm("Перенести $internal видео?", "Каждое видео копируется в папку, проверяется и только потом " +
                                    "удаляется из NOX. Видео, открытое в плеере, пропускается.", "Перенести") { vm.moveLibraryToDestination() }
                            })
                    }
                }
            }
            SectionGap()
        }
        item {
            Section("Сеть и очередь", icon = Icons.Rounded.Speed) {
                Text("Одновременных загрузок", color = Nox.TextPrimary, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                ChoiceRow(DownloadPrefs.CONCURRENCY_CHOICES.map { it to "$it" }, dl.concurrency,
                    { n -> vm.settings.updateDownloads { it.copy(concurrency = n) } })
                InlineNote("Меньше — идущие загрузки не обрываются, новые просто подождут. Файлы от этого не портятся.")
                Spacer(Modifier.height(6.dp))
                SettingLine(null, "Только Wi‑Fi", "В мобильной сети загрузки ждут Wi‑Fi (скачанные части сохраняются) и продолжаются сами",
                    chevron = false, trailing = { AmberSwitch(dl.wifiOnly, vm::setWifiOnly, label = "Только Wi‑Fi") })
                Spacer(Modifier.height(10.dp))
                Text("Общий предел скорости", color = Nox.TextPrimary, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                val speeds = DownloadPrefs.SPEED_CHOICES.map { it to if (it == 0) "Без" else if (it >= 1024) "${it / 1024} МБ/с" else "$it КБ/с" }
                ChoiceRow(speeds.take(3), dl.speedLimitKbps, { v -> vm.settings.updateDownloads { it.copy(speedLimitKbps = v) } }, textSize = 12.5.sp)
                Spacer(Modifier.height(6.dp))
                ChoiceRow(speeds.drop(3), dl.speedLimitKbps, { v -> vm.settings.updateDownloads { it.copy(speedLimitKbps = v) } }, textSize = 12.5.sp)
                InlineNote("Предел — на все загрузки вместе. Обновление NOX им не ограничивается; «Без» — без какого-либо замедления.")
            }
            SectionGap()
        }
        item {
            Section("Качество по умолчанию", icon = Icons.Rounded.HighQuality) {
                InlineNote("Какой вариант выделять заранее, если он есть у видео. Выбор всё равно за вами.")
                val q = listOf(480 to "480p", 720 to "720p", 1080 to "1080p", 1440 to "1440p", 2160 to "2160p", 0 to "Лучшее")
                ChoiceRow(q.take(3), dl.preferredHeight, vm::setPreferredHeight)
                Spacer(Modifier.height(6.dp))
                ChoiceRow(q.drop(3), dl.preferredHeight, vm::setPreferredHeight)
                Spacer(Modifier.height(8.dp))
                SettingLine(null, "Сначала готовый файл со звуком", "Если у ступени есть и готовый файл, и отдельные видео и звук. " +
                    "Объединение идёт без перекодирования.", chevron = false,
                    trailing = { AmberSwitch(dl.preferSingleFile, vm::setPreferSingleFile, label = "Сначала готовый файл") })
            }
            SectionGap()
        }
        item {
            Section("Уведомления и память", icon = Icons.Rounded.Notifications) {
                SettingLine(Icons.Rounded.Notifications, "Прогресс загрузки в шторке",
                    if (allowed) "Разрешены" else "Выключены — загрузка работает, но прогресса в шторке не будет",
                    onClick = if (allowed) null else actions.requestNotifications)
                Spacer(Modifier.height(6.dp))
                SettingLine(Icons.Rounded.Storage, "Память и очистка просмотренного", "Ничего не удаляется само", onClick = {
                    nav.open(Page.Cleanup, com.nox.offline.ui.Tab.DOWNLOADS)
                })
            }
        }
    }
}

/** Резервные копии: ручная (что сохранить) и автоматическая (только записи и настройки). */
@Composable
fun BackupPage(vm: MainViewModel, actions: NoxActions, nav: Nav, padding: PaddingValues) {
    val b by vm.backupPrefs.collectAsState()
    val sheets = LocalSheets.current
    PageFrame("Резервная копия", "Импорт, экспорт и автокопия", nav, padding) {
        item {
            Section("Сделать копию сейчас", icon = Icons.Rounded.Backup) {
                SettingLine(null, "Только данные", "Медиатека (записи), коллекции, серии, главы, закладки, субтитры, позиции, очередь, настройки",
                    onClick = { actions.pickBackupExport(false, false) })
                Spacer(Modifier.height(6.dp))
                SettingLine(null, "Данные и готовые видео", "Может занять много места", onClick = { actions.pickBackupExport(true, false) })
                Spacer(Modifier.height(6.dp))
                SettingLine(null, "Всё, включая незавершённые загрузки", "Видео и файлы .part", onClick = { actions.pickBackupExport(true, true) })
                Spacer(Modifier.height(6.dp))
                SettingLine(Icons.Rounded.Restore, "Восстановить из копии", "Папка NOX-backup-… или NOX-auto-…; повтор не создаёт дублей",
                    onClick = actions.pickBackupImport)
                InlineNote("В копию никогда не попадают ключ подписи, токены и cookies. Доступ к папкам на другом устройстве нужно выдать заново.")
            }
            SectionGap()
        }
        item {
            Section("Автоматическая копия", icon = Icons.Rounded.Restore) {
                SettingLine(null, "Включена", "Только записи и настройки, без видео. Не облако — копия в выбранную вами папку.", chevron = false,
                    trailing = { AmberSwitch(b.autoEnabled, { on -> if (on && b.tree.isBlank()) actions.pickAutoBackupFolder() else vm.setAutoBackup(on) },
                        label = "Автоматическая копия") })
                Spacer(Modifier.height(6.dp))
                SettingLine(Icons.Rounded.Folder, "Папка", if (b.tree.isBlank()) "Не выбрана" else "«${b.treeLabel}»",
                    onClick = actions.pickAutoBackupFolder)
                Spacer(Modifier.height(8.dp))
                Text("Как часто", color = Nox.TextPrimary, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                ChoiceRow(BackupPrefs.PERIOD_CHOICES.map { it to if (it == 1) "Раз в день" else "Раз в неделю" }, b.periodDays,
                    { d -> vm.settings.updateBackup { it.copy(periodDays = d) } })
                Spacer(Modifier.height(8.dp))
                Text("Хранить последних копий", color = Nox.TextPrimary, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                ChoiceRow(BackupPrefs.KEEP_CHOICES.map { it to "$it" }, b.keep, { k -> vm.settings.updateBackup { it.copy(keep = k) } })
                Spacer(Modifier.height(8.dp))
                Text(when {
                    !b.autoEnabled -> "Выключена."
                    b.lastError.isNotBlank() -> "Последняя попытка: ${b.lastError}"
                    b.lastAt > 0 -> "Последняя копия: ${java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(b.lastAt))}"
                    else -> "Копий ещё не было."
                }, color = if (b.lastError.isNotBlank()) Nox.Danger else LavenderText, fontSize = 13.sp)
                InlineNote("Время не гарантируется до минуты: Android запускает фоновые задачи, когда удобно системе. " +
                    "Копия делается и при открытии NOX, если подошёл срок.")
                if (b.autoEnabled && b.tree.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    TileButton("Сделать автокопию сейчас", { vm.runAutoBackupNow() }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** О NOX: версии компонентов и диагностика без секретов. */
@Composable
fun AboutPage(vm: MainViewModel, actions: NoxActions, nav: Nav, padding: PaddingValues) {
    PageFrame("О NOX", "NOX Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", nav, padding) {
        item {
            Section("Приложение") {
                Text("Офлайн-медиатека: скачанные видео смотрятся без сети. Разбор ссылок — yt-dlp со встроенным JS-движком QuickJS-NG, " +
                    "передача — OkHttp, плеер — Media3. Фоновая загрузка: " +
                    (if (Build.VERSION.SDK_INT >= 34) "User-Initiated Data Transfer Job." else "foreground service.") +
                    " Обновления — из официальных релизов NOX на GitHub, установка только по вашему нажатию.",
                    color = LavenderText, fontSize = 13.5.sp)
                Spacer(Modifier.height(10.dp))
                SettingLine(Icons.Rounded.BugReport, "Скопировать диагностику", "Версия, устройство, задания, события. Без cookie и подписанных адресов",
                    onClick = actions.copyDiagnostics)
                Spacer(Modifier.height(6.dp))
                SettingLine(null, "Лицензионные соглашения", null, onClick = { nav.open(Page.Licenses) })
                Spacer(Modifier.height(6.dp))
                SettingLine(null, "Политика конфиденциальности", null, onClick = { nav.open(Page.Privacy) })
            }
        }
    }
}

private val licenses = listOf(
    "yt-dlp" to "The Unlicense (общественное достояние)",
    "yt-dlp-ejs" to "The Unlicense",
    "QuickJS-NG" to "MIT License",
    "Python (Chaquopy)" to "Python Software Foundation License; Chaquopy — MIT License",
    "AndroidX Media3 (ExoPlayer)" to "Apache License 2.0",
    "Jetpack Compose, AndroidX, Room" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "certifi" to "Mozilla Public License 2.0",
)

@Composable
fun LicensesPage(nav: Nav, padding: PaddingValues) {
    PageFrame("Лицензии", "Сторонние компоненты в NOX", nav, padding) {
        item {
            Section(null, contentPadding = PaddingValues(14.dp)) {
                for ((name, lic) in licenses) {
                    Text(name, color = Nox.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(lic, color = LavenderText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                InlineNote("Полные тексты лицензий — на страницах проектов. NOX не изменяет эти компоненты, кроме сборки QuickJS-NG под Android.")
            }
        }
    }
}

@Composable
fun PrivacyPage(nav: Nav, padding: PaddingValues) {
    PageFrame("Конфиденциальность", "Что NOX делает с данными", nav, padding) {
        item {
            Section(null, contentPadding = PaddingValues(14.dp)) {
                for ((t, d) in listOf(
                    "Никакой аналитики" to "NOX не собирает статистику, не показывает рекламу и не отправляет ваши данные разработчику.",
                    "Сеть — только по делу" to "Соединения идут к сайтам, ссылки на которые вы открыли, и к GitHub — за проверкой обновлений " +
                        "(её можно выключить).",
                    "Без аккаунтов" to "NOX не просит пароль Google и не хранит cookies аккаунтов.",
                    "Данные — на устройстве" to "Медиатека, позиции, коллекции и настройки хранятся в памяти телефона. Резервная копия пишется " +
                        "только в папку, которую выбрали вы.",
                    "Журнал" to "Диагностика хранится локально и копируется только по вашей кнопке — без cookies и подписанных адресов.",
                )) {
                    Text(t, color = Nox.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(d, color = LavenderText, fontSize = 13.5.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }
    }
}

/** Настройки плеера (из меню ⋮ плеера): автопереход, отсчёт, «картинка в картинке». */
fun playerSettingsSheet(sheets: SheetController, vm: MainViewModel) {
    sheets.show("Настройки плеера") { _ ->
        val p by vm.playerPrefs.collectAsState()
        Text("После серии", color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        ChoiceRow(listOf(AutoNext.ASK to "С отсчётом", AutoNext.AUTO to "Сразу", AutoNext.OFF to "Не переходить"), p.autoNext,
            { a -> vm.settings.updatePlayer { it.copy(autoNext = a) } }, textSize = 11.sp)
        if (p.autoNext == AutoNext.ASK) {
            Spacer(Modifier.height(8.dp))
            Text("Отсчёт", color = LavenderText, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            ChoiceRow(listOf(5 to "5 с", 8 to "8 с", 15 to "15 с"), p.autoNextSeconds, { s -> vm.settings.updatePlayer { it.copy(autoNextSeconds = s) } })
        }
        InlineNote("Следующее — только из скачанного, по порядку выбранной коллекции или сезона. Ничего не скачивается само.")
        Spacer(Modifier.height(8.dp))
        SettingLine(Icons.Rounded.PictureInPictureAlt, "«Картинка в картинке» при выходе", "Из полноэкранного режима, если видео играет",
            chevron = false, trailing = { AmberSwitch(p.pipOnLeave, vm::setPipOnLeave, label = "Картинка в картинке при выходе") })
    }
}
