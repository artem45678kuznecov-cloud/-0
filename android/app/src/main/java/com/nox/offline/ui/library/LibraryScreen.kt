package com.nox.offline.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.components.GlassCard
import com.nox.offline.ui.components.GlassIconButton
import com.nox.offline.ui.components.GlassPill
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.PlayerRowCard
import com.nox.offline.ui.components.SearchRow
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.theme.Nox

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: MainViewModel, actions: NoxActions, contentPadding: PaddingValues) {
    val list by vm.library.collectAsState()
    val all by vm.allItems.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val sheets = LocalSheets.current
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selected.isNotEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("top") {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", actions.back, size = 48.dp)
                HSpace(14)
                Column(Modifier.weight(1f)) {
                    Text("Медиатека", color = Nox.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Muted("${list.size} из ${all.size} • ${filter.sort.label.lowercase()}", size = 14.sp, color = Nox.TextSecondary)
                }
                GlassIconButton(Icons.Rounded.FileDownload, "Импорт видео", actions.pickImport, size = 48.dp)
            }
        }
        item("search") {
            SearchRow(query, { vm.query.value = it }, actions.openFilter, filterActive = !filter.isDefault,
                modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (selecting) {
            item("selection") {
                GlassCard(Modifier.padding(horizontal = 20.dp), padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.Rounded.Close, "Снять выбор", { selected = emptySet() }, size = 40.dp, iconSize = 20.dp)
                        HSpace(10)
                        Text("Выбрано: ${selected.size}", color = Nox.TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        GlassPill("Экспорт", icon = Icons.Rounded.IosShare, height = 40.dp, textSize = 14.sp, onClick = {
                            actions.pickExportFor(all.filter { it.id in selected }); selected = emptySet()
                        })
                        HSpace(8)
                        GlassIconButton(Icons.Rounded.Delete, "Удалить выбранные", {
                            val chosen = all.filter { it.id in selected }
                            sheets.confirm("Удалить ${chosen.size} видео?",
                                "Файлы будут удалены с устройства, позиции просмотра — тоже. Это нельзя отменить.",
                                "Удалить", danger = true) {
                                chosen.forEach { vm.deleteMedia(it.media) }
                                selected = emptySet()
                            }
                        }, size = 40.dp, iconSize = 20.dp, tint = Nox.Danger)
                    }
                }
            }
        } else if (all.isNotEmpty()) {
            item("hint") { Muted("Удерживайте карточку, чтобы выбрать несколько видео.", size = 13.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        }
        if (all.isEmpty()) {
            item("empty") {
                GlassCard(Modifier.padding(horizontal = 20.dp)) {
                    Muted("Медиатека пуста. Скачайте видео или импортируйте своё кнопкой вверху.", size = 15.sp, color = Nox.TextSecondary)
                }
            }
        } else if (list.isEmpty()) {
            item("none") {
                GlassCard(Modifier.padding(horizontal = 20.dp)) {
                    Muted("Ничего не найдено.", size = 15.sp, color = Nox.TextSecondary)
                    VSpace(10)
                    GlassPill("Сбросить поиск и фильтры", onClick = { vm.query.value = ""; vm.filter.value = LibraryFilter() })
                }
            }
        }
        items(list, key = { it.id }) { item ->
            val isSel = item.id in selected
            PlayerRowCard(
                item,
                onPlay = { if (selecting) selected = if (isSel) selected - item.id else selected + item.id else actions.openMedia(item, false) },
                onMenu = { actions.mediaMenu(item) },
                selected = isSel,
                onLongPress = { selected = if (isSel) selected - item.id else selected + item.id },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** Лист «Фильтр и сортировка» — общий для Главной и «Все». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.FilterSheet(vm: MainViewModel, close: () -> Unit) {
    val f by vm.filter.collectAsState()
    Text("Сортировка", color = Nox.TextSecondary, fontSize = 14.sp)
    VSpace(8)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in SortKey.entries) {
            GlassPill(k.label, accent = f.sort == k, height = 40.dp, textSize = 14.sp, onClick = { vm.filter.value = f.copy(sort = k) })
        }
        GlassPill(if (f.descending) "По убыванию" else "По возрастанию", icon = Icons.Rounded.SwapVert, height = 40.dp,
            textSize = 14.sp, onClick = { vm.filter.value = f.copy(descending = !f.descending) })
    }
    VSpace(16)
    Text("Просмотр", color = Nox.TextSecondary, fontSize = 14.sp)
    VSpace(8)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (w in WatchFilter.entries) {
            GlassPill(w.label, accent = f.watch == w, height = 40.dp, textSize = 14.sp, onClick = { vm.filter.value = f.copy(watch = w) })
        }
    }
    VSpace(16)
    Text("Качество", color = Nox.TextSecondary, fontSize = 14.sp)
    VSpace(8)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (q in QualityFilter.entries) {
            GlassPill(q.label, accent = f.quality == q, height = 40.dp, textSize = 14.sp, onClick = { vm.filter.value = f.copy(quality = q) })
        }
    }
    VSpace(20)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassPill("Сбросить", onClick = { vm.filter.value = LibraryFilter() }, modifier = Modifier.weight(1f), height = 48.dp)
        GlassPill("Готово", accent = true, onClick = close, modifier = Modifier.weight(1f), height = 48.dp)
    }
}
