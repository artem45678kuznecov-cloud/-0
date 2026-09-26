package com.nox.offline.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.Format
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.downloader.NetworkGate
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Page
import com.nox.offline.ui.Tab
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.statusLabel
import com.nox.offline.ui.kit.ActionCircle
import com.nox.offline.ui.kit.BrandHeader
import com.nox.offline.ui.kit.Chip
import com.nox.offline.ui.kit.EmptyBlock
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ProgressLine
import com.nox.offline.ui.kit.ScreenHeading
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.Tile
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/** «1 элемент», «2 элемента», «5 элементов». */
fun elements(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> "элемент"
        m10 in 2..4 && m100 !in 12..14 -> "элемента"
        else -> "элементов"
    }
    return "$n $w"
}

/**
 * Очередь (макет 03): активные, на паузе, завершённые и память. Если
 * есть — отдельные разделы «В очереди», «Обработка» и «Ошибки». Кнопка
 * поиска вверху открывает загрузчик внутри этой же вкладки.
 */
@Composable
fun QueueScreen(vm: MainViewModel, nav: Nav, padding: PaddingValues, onDownloadMenu: (DownloadEntity) -> Unit,
                onConfirmCancel: (DownloadEntity) -> Unit, onOpenCompleted: (DownloadEntity) -> Unit) {
    val list by vm.downloads.collectAsState()
    val space by vm.space.collectAsState()
    val net by vm.networkState.collectAsState()
    val prefs by vm.downloadPrefs.collectAsState()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val sheets = com.nox.offline.ui.components.LocalSheets.current

    val active = list.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.RESOLVING }
    val queued = list.filter { it.status == DownloadStatus.QUEUED }
    val processing = list.filter { it.status == DownloadStatus.PROCESSING }
    val paused = list.filter { it.status == DownloadStatus.PAUSED }
    val failed = list.filter { it.status == DownloadStatus.ERROR }
    val done = list.filter { it.status == DownloadStatus.COMPLETED }.sortedByDescending { it.updatedAt }

    fun toggleSel(d: DownloadEntity) { selected = if (d.id in selected) selected - d.id else selected + d.id }

    LazyColumn(contentPadding = padding) {
        item {
            BrandHeader(onSearch = { nav.open(Page.Downloader) }, onSettings = { nav.select(Tab.SETTINGS) },
                searchLabel = "Загрузчик: найти видео по ссылке")
            Spacer(Modifier.height(14.dp))
            ScreenHeading("Загрузки", "Скачивайте видео для просмотра без сети")
            Spacer(Modifier.height(10.dp))
            // Действия над очередью целиком — только те, что сейчас имеют смысл.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Добавить видео", amber = true, icon = Icons.Rounded.Download, onClick = { nav.open(Page.Downloader) }, height = 30.dp, textSize = 11.5.sp)
                if (active.isNotEmpty() || queued.isNotEmpty()) Chip("Пауза для всех", icon = Icons.Rounded.Pause,
                    onClick = { vm.pauseAll() }, height = 30.dp, textSize = 11.5.sp)
                if (paused.isNotEmpty()) Chip("Продолжить все", icon = Icons.Rounded.PlayArrow, onClick = { vm.resumeAll() }, height = 30.dp, textSize = 11.5.sp)
                if (failed.isNotEmpty()) Chip("Повторить ошибки", icon = Icons.Rounded.Refresh, onClick = { vm.retryErrors() }, height = 30.dp, textSize = 11.5.sp)
                if (done.isNotEmpty()) Chip("Убрать готовые", icon = Icons.Rounded.DoneAll, onClick = { vm.clearCompleted() }, height = 30.dp, textSize = 11.5.sp)
            }
            SectionGap()
            if (net != NetworkGate.State.OK && (queued.isNotEmpty() || active.isNotEmpty())) {
                Section(null, contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WifiOff, null, tint = nox().accent, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (net == NetworkGate.State.METERED) "Ждём Wi‑Fi" else "Нет сети", color = Nox.TextPrimary, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(if (net == NetworkGate.State.METERED) "Включено «Только Wi‑Fi». Загрузки продолжатся сами, скачанные части сохранены."
                            else "Загрузки продолжатся сами, когда появится связь. Скачанные части сохранены.",
                                color = LavenderText, fontSize = 12.5.sp)
                        }
                        if (net == NetworkGate.State.METERED && prefs.wifiOnly) {
                            Chip("Настройки", onClick = { nav.open(Page.DownloadSettings, Tab.SETTINGS) })
                        }
                    }
                }
                SectionGap()
            }
            if (selected.isNotEmpty()) {
                Section("Выбрано: ${selected.size}", trailing = "Снять", onTrailing = { selected = emptySet() }) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Пауза", icon = Icons.Rounded.Pause, height = 34.dp, onClick = { vm.pauseMany(selected); selected = emptySet() })
                        Chip("Продолжить", icon = Icons.Rounded.PlayArrow, height = 34.dp, onClick = { vm.resumeMany(selected); selected = emptySet() })
                        Chip("Скачать следующими", icon = Icons.Rounded.VerticalAlignTop, height = 34.dp,
                            onClick = { vm.playNext(selected.toList().reversed()); selected = emptySet() })
                        Chip("Удалить", icon = Icons.Rounded.Close, height = 34.dp, onClick = {
                            val ids = selected
                            sheets.confirm("Удалить ${elements(ids.size)}?", "Незавершённые загрузки остановятся, их скачанные части " +
                                "будут удалены. Готовые видео останутся в медиатеке.", "Удалить", danger = true) {
                                vm.removeMany(ids); selected = emptySet()
                            }
                        })
                    }
                }
                SectionGap()
            }
        }
        if (list.isEmpty()) {
            item {
                Section(null) {
                    EmptyBlock(Icons.Rounded.Download, "Загрузок пока нет",
                        "Вставьте ссылку в загрузчике: NOX покажет настоящие варианты качества этого видео.",
                        action = "Открыть загрузчик", onAction = { nav.open(Page.Downloader) })
                }
                SectionGap()
            }
        }
        fun group(title: String, items: List<DownloadEntity>) {
            if (items.isEmpty()) return
            item(key = "g-$title") {
                Section(title, trailing = elements(items.size)) {
                    for ((i, d) in items.withIndex()) {
                        QueueCard(d, selected = d.id in selected,
                            onToggle = { vm.toggle(d) },
                            onCancel = { onConfirmCancel(d) },
                            onMenu = { onDownloadMenu(d) },
                            onSelect = { toggleSel(d) },
                            onOpen = { onOpenCompleted(d) },
                            selecting = selected.isNotEmpty())
                        if (i < items.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
                SectionGap()
            }
        }
        group("Активные загрузки", active)
        group("В очереди", queued)
        group("Обработка", processing)
        group("На паузе", paused)
        group("Ошибки", failed)
        group("Завершено", done.take(30))
        item {
            val sp = space
            Section(null, contentPadding = PaddingValues(0.dp)) {
                Tile(Modifier.fillMaxWidth(), onClick = { nav.open(Page.Cleanup) }, radius = 16.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tile(Modifier.size(50.dp), radius = 12.dp, selected = true) {
                            Icon(Icons.Rounded.Storage, null, tint = nox().accentLight, modifier = Modifier.align(Alignment.Center).size(26.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Память устройства", color = Nox.TextPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
                            if (sp != null && sp.totalBytes > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${Format.bytes(sp.freeBytes)} свободно из ${Format.bytes(sp.totalBytes)}", color = LavenderText,
                                        fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("${((sp.totalBytes - sp.freeBytes) * 100 / sp.totalBytes)}% занято", color = LavenderText, fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(6.dp))
                                ProgressLine((sp.totalBytes - sp.freeBytes).toFloat() / sp.totalBytes)
                                Text("NOX занимает ${Format.bytes(sp.usedByNox)} · очистка просмотренного", color = LavenderText, fontSize = 11.5.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                            } else Text("Считаем…", color = LavenderText, fontSize = 13.sp)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFD5D9FF))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueCard(
    d: DownloadEntity,
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    onMenu: () -> Unit,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val done = d.status == DownloadStatus.COMPLETED
    val running = d.status == DownloadStatus.DOWNLOADING
    val total = d.totalBytes
    val fraction = when {
        done -> 1f
        total > 0 -> d.downloadedBytes.toFloat() / total
        else -> 0f
    }
    Tile(Modifier.fillMaxWidth(), selected = selected, onClick = { if (selecting) onSelect() else if (done) onOpen() else onMenu() },
        onLongClick = onSelect) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(d.thumbnailUrl, Modifier.size(width = 76.dp, height = 76.dp), RoundedCornerShape(9.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(d.displayTitle.ifBlank { "Загрузка" }, color = Nox.TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                val sub = when {
                    d.status == DownloadStatus.ERROR -> d.error.ifBlank { "Ошибка" }
                    d.subtitleError.isNotBlank() && done -> "Субтитры не скачались — повторить в меню ⋮"
                    else -> listOfNotNull(d.qualityLabel.ifBlank { null }, d.uploader.ifBlank { null }).joinToString(" · ")
                }
                Text(sub, color = if (d.status == DownloadStatus.ERROR) Nox.Danger else LavenderText, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
                Row(Modifier.padding(top = 3.dp)) {
                    Text(if (total > 0) "${Format.bytes(d.downloadedBytes.coerceAtMost(total))} / ${Format.bytes(total)}"
                    else Format.bytes(d.downloadedBytes), color = Color(0xFFE3E6FA), fontSize = 11.sp, modifier = Modifier.weight(1f),
                        lineHeight = 13.sp)
                    if (running && d.speedBps > 0) Text(Format.speed(d.speedBps), color = LavenderText, fontSize = 11.sp, lineHeight = 13.sp)
                }
                ProgressLine(fraction, Modifier.padding(vertical = 4.dp), lavender = !running && !done, height = 4.dp)
                Row {
                    Text("${(fraction * 100).toInt()}%", color = Color(0xFFE3E6FA), fontSize = 11.sp, modifier = Modifier.weight(1f),
                        lineHeight = 13.sp)
                    Text(when {
                        running && d.etaSec >= 0 -> "Осталось: ${Format.etaClock(d.etaSec)}"
                        done -> "Завершено"
                        else -> statusLabel(d)
                    }, color = Color(0xFFE3E6FA), fontSize = 11.sp, maxLines = 1, lineHeight = 13.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (done) {
                ActionCircle(Icons.Rounded.Check, "Смотреть «${d.displayTitle}»", onOpen, size = 32.dp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.MoreVert, "Действия", tint = Color(0xFFD5D9FF),
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onMenu).padding(6.dp))
            } else if (d.status != DownloadStatus.PROCESSING) {
                val paused = d.status == DownloadStatus.PAUSED || d.status == DownloadStatus.ERROR
                ActionCircle(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (paused) "Продолжить" else "Пауза", onToggle, size = 32.dp)
                Spacer(Modifier.width(8.dp))
                ActionCircle(Icons.Rounded.Close, "Удалить загрузку", onCancel, amber = false, size = 32.dp)
            }
        }
    }
}
