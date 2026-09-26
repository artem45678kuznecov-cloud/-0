package com.nox.offline.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush as GBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.BuildConfig
import com.nox.offline.settings.GlassMode
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.Page
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.glass.LocalWallpaperFrame
import com.nox.offline.ui.kit.AmberSwitch
import com.nox.offline.ui.kit.ChoiceRow
import com.nox.offline.ui.kit.InlineNote
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.SettingLine
import com.nox.offline.ui.kit.Tile
import com.nox.offline.ui.kit.TileButton
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.NoxPalettes
import com.nox.offline.updates.UpdateState

/** Четыре темы с макета. Остальные (и свой цвет) — на странице «Оформление». */
private val quickThemes = listOf("amber" to "Янтарь", "classic" to "Лаванда", "ice" to "Лёд", "graphite" to "Графит")

/**
 * Настройки (макет 05): обновления → оформление → фон и стекло →
 * загрузки → язык → импорт/экспорт → резервная копия → о NOX.
 * Подробности каждого раздела — по стрелке справа, на вложенной странице.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, actions: NoxActions, nav: Nav, padding: PaddingValues) {
    val update by vm.updateState.collectAsState()
    val upPrefs by vm.updatePrefs.collectAsState()
    val dl by vm.downloadPrefs.collectAsState()
    val appearance by vm.appearance.collectAsState()
    val backup by vm.backupPrefs.collectAsState()
    val sheets = LocalSheets.current
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    LazyColumn(contentPadding = padding) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Настройки", fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp,
                        style = androidx.compose.ui.text.TextStyle(brush = GBrush.verticalGradient(listOf(Color(0xFFFFE3B8), Color(0xFFF2A65A)))))
                    Text("NOX Android ${BuildConfig.VERSION_NAME}", color = Color(0xFFD9DCF5), fontSize = 15.sp, letterSpacing = 1.2.sp)
                    Box(Modifier.padding(top = 6.dp).width(22.dp).height(2.dp).background(Color(0xFFF5A524)))
                }
                Text("ТВОЙ МИР.\nТВОИ НАСТРОЙКИ.\nВСЕГДА С ТОБОЙ.", color = Color(0xFFD9DCF5).copy(alpha = 0.8f), fontSize = 9.5.sp,
                    letterSpacing = 1.4.sp, lineHeight = 13.sp, textAlign = TextAlign.End, modifier = Modifier.padding(top = 44.dp))
            }
            SectionGap()
        }
        // ---------- обновления ----------
        item {
            Section("Обновления", icon = Icons.Rounded.Sync, onTrailing = { nav.open(Page.About) }) {
                Tile(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C2346)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Sync, null, tint = Color(0xFFBFC6FF), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Текущая версия", color = LavenderText, fontSize = 12.5.sp)
                            Text(version, color = Nox.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(when (val u = update) {
                                is UpdateState.UpToDate -> "Установлена последняя версия"
                                is UpdateState.Checking -> "Проверяем…"
                                is UpdateState.Available -> "Доступна ${u.manifest.versionName}"
                                is UpdateState.Downloading -> "Скачивается обновление"
                                is UpdateState.Failed -> "Проверка не удалась"
                                else -> "Нажмите «Проверить», чтобы узнать о новой версии"
                            }, color = LavenderText, fontSize = 12.sp, maxLines = 2)
                        }
                        TileButton("Проверить ещё раз", { vm.checkUpdates() }, Modifier.width(160.dp), icon = Icons.Rounded.Sync, height = 42.dp)
                    }
                }
                if (update is UpdateState.Available || update is UpdateState.Downloading || update is UpdateState.NeedsPermission ||
                    update is UpdateState.Installing || update is UpdateState.Failed || update is UpdateState.JustUpdated) {
                    Spacer(Modifier.height(6.dp))
                    Tile(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { UpdateSection(update, vm, actions) } }
                }
                Spacer(Modifier.height(6.dp))
                SettingLine(Icons.Rounded.NotificationsNone, "Автоматическая проверка обновлений", "Проверять наличие новых версий в фоне",
                    chevron = false, trailing = { AmberSwitch(upPrefs.autoCheck, vm::setAutoCheck, label = "Автоматическая проверка") })
            }
            SectionGap()
        }
        // ---------- оформление ----------
        item {
            Section("Оформление", icon = Icons.Rounded.Brush, subtitle = "Настройте внешний вид приложения под свой стиль",
                onTrailing = { nav.open(Page.Appearance) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in quickThemes) {
                        val on = appearance.preset == id
                        Tile(Modifier.weight(1f).height(96.dp), selected = on, radius = 14.dp,
                            onClick = { vm.updateAppearance { it.copy(preset = id) } }) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                val c = NoxPalettes.of(id, 0f).accent
                                Box(Modifier.size(46.dp).clip(CircleShape).background(GBrush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.9f), c, NoxPalettes.mix(c, Color.Black, 0.7f)),
                                    center = androidx.compose.ui.geometry.Offset(40f, 36f), radius = 90f)))
                                Spacer(Modifier.height(6.dp))
                                Text(label, color = Nox.TextPrimary, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
            SectionGap()
        }
        // ---------- фон и стекло ----------
        item {
            Section("Темы, фон и стекло", icon = Icons.Rounded.Image, onTrailing = { nav.open(Page.Appearance) }) {
                val frame = LocalWallpaperFrame.current
                SettingLine(Icons.Rounded.Image, "Обои приложения", "Выбери фон из коллекции или загрузи свой", onClick = { nav.open(Page.Appearance) },
                    trailing = {
                        Box(Modifier.size(width = 112.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0F20))) {
                            if (frame != null) Image(frame.display, "Текущие обои: ${appearance.wallpaper.label}", contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(40.dp))
                        }
                    })
                Spacer(Modifier.height(6.dp))
                SettingLine(Icons.Rounded.ViewInAr, "Стекло", "Выбери уровень эффектов для лучшей производительности",
                    onClick = {
                        sheets.show("Стекло") { close ->
                            for (g in listOf(GlassMode.FULL, GlassMode.AUTO, GlassMode.ECONOMY)) {
                                Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = appearance.glassMode == g,
                                    onClick = { vm.updateAppearance { it.copy(glassMode = g) }; close() }) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(glassLabel(g).first, color = Nox.TextPrimary, fontSize = 15.sp)
                                        Text(glassLabel(g).second, color = LavenderText, fontSize = 12.5.sp)
                                    }
                                }
                            }
                        }
                    },
                    trailing = {
                        Tile(Modifier.size(width = 132.dp, height = 46.dp), selected = true, radius = 12.dp) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(glassLabel(appearance.glassMode).first, color = Nox.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("(${glassLabel(appearance.glassMode).third})", color = LavenderText, fontSize = 11.sp)
                            }
                        }
                    })
            }
            SectionGap()
        }
        // ---------- загрузки ----------
        item {
            Section("Загрузки", icon = Icons.Rounded.Download, onTrailing = { nav.open(Page.DownloadSettings) }) {
                SettingLine(Icons.Rounded.FolderOpen, "Папка загрузок", vm.destinationStatus(), onClick = actions.pickDestination)
                Spacer(Modifier.height(6.dp))
                SettingLine(Icons.Rounded.Layers, "Одновременных загрузок", "Загружать несколько видео одновременно", chevron = false,
                    trailing = {
                        ChoiceRow(com.nox.offline.settings.DownloadPrefs.CONCURRENCY_CHOICES.map { it to "$it" }, dl.concurrency,
                            { n -> vm.settings.updateDownloads { it.copy(concurrency = n) } }, Modifier.width(132.dp), height = 38.dp, textSize = 16.sp)
                    })
                Spacer(Modifier.height(6.dp))
                SettingLine(Icons.Rounded.Wifi, "Только Wi‑Fi для загрузок", "Использовать только Wi‑Fi, чтобы экономить трафик", chevron = false,
                    trailing = { AmberSwitch(dl.wifiOnly, vm::setWifiOnly, label = "Только Wi‑Fi") })
            }
            SectionGap()
        }
        // ---------- язык ----------
        item {
            Section("Язык", icon = Icons.Rounded.Language, onTrailing = { languageSheet(sheets) }) {
                SettingLine(Icons.Rounded.Language, "Язык интерфейса", "Выбери язык приложения", chevron = false, trailing = {
                    TileButton("Русский", { languageSheet(sheets) }, Modifier.width(150.dp), chevron = true, height = 42.dp)
                })
            }
            SectionGap()
        }
        // ---------- импорт / экспорт ----------
        item {
            Section("Импорт / экспорт", icon = Icons.Rounded.ImportExport, onTrailing = { nav.open(Page.Backup) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(Icons.Rounded.FileUpload, "Экспорт настроек", "Сохранить в папку", Modifier.weight(1f)) {
                        actions.pickBackupExport(false, false)
                    }
                    SmallAction(Icons.Rounded.FileDownload, "Импорт настроек", "Загрузить из копии", Modifier.weight(1f)) {
                        actions.pickBackupImport()
                    }
                }
            }
            SectionGap()
        }
        // ---------- резервная копия ----------
        item {
            Section("Резервная копия", icon = Icons.Rounded.Cloud, onTrailing = { nav.open(Page.Backup) }) {
                SettingLine(Icons.Rounded.Shield, "Автоматическое резервное копирование",
                    if (backup.autoEnabled && backup.lastError.isNotBlank()) backup.lastError
                    else "Сохранять данные: история, избранное, настройки", chevron = false,
                    trailing = {
                        AmberSwitch(backup.autoEnabled, { on ->
                            if (on && backup.tree.isBlank()) actions.pickAutoBackupFolder() else vm.setAutoBackup(on)
                        }, label = "Автоматическое резервное копирование")
                    })
            }
            SectionGap()
        }
        // ---------- о NOX ----------
        item {
            Section("О NOX", icon = Icons.Rounded.Info, onTrailing = { nav.open(Page.About) }) {
                SettingLine(Icons.Rounded.WorkspacePremium, "Версия приложения", null, onClick = { nav.open(Page.About) },
                    trailing = { Text("NOX Android $version", color = LavenderText, fontSize = 13.sp) })
                Spacer(Modifier.height(4.dp))
                SettingLine(Icons.AutoMirrored.Rounded.Article, "Лицензионные соглашения", null, onClick = { nav.open(Page.Licenses) })
                Spacer(Modifier.height(4.dp))
                SettingLine(Icons.Rounded.Policy, "Политика конфиденциальности", null, onClick = { nav.open(Page.Privacy) })
            }
        }
    }
}

private fun glassLabel(g: GlassMode): Triple<String, String, String> = when (g) {
    GlassMode.FULL -> Triple("Полный", "Живое размытие и преломление (Android 12+)", "красивое")
    GlassMode.AUTO -> Triple("Авто", "Полный там, где телефон тянет; иначе экономичный", "по устройству")
    GlassMode.ECONOMY -> Triple("Экономичный", "То же оформление без покадровых эффектов", "быстрое")
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier,
                        onClick: () -> Unit) {
    Tile(modifier.height(62.dp), onClick = onClick, radius = 14.dp) {
        Row(Modifier.padding(horizontal = 12.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFFBFC6FF), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Nox.TextPrimary, fontSize = 14.sp)
                Text(sub, color = LavenderText, fontSize = 12.sp)
            }
        }
    }
}

/** Честно: перевод есть только на русский — список языков не выдумываем. */
private fun languageSheet(sheets: com.nox.offline.ui.components.SheetController) {
    sheets.show("Язык интерфейса") { _ ->
        Tile(Modifier.fillMaxWidth(), selected = true) {
            Text("Русский", color = Nox.TextPrimary, fontSize = 16.sp, modifier = Modifier.padding(14.dp))
        }
        InlineNote("Сейчас NOX переведён только на русский. Другие языки появятся здесь, когда перевод будет готов.")
    }
}
