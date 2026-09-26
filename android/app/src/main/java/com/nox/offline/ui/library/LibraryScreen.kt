package com.nox.offline.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.DurationBadge
import com.nox.offline.ui.kit.Chip
import com.nox.offline.ui.kit.EmptyBlock
import com.nox.offline.ui.kit.KitField
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ProgressLine
import com.nox.offline.ui.kit.RoundButton
import com.nox.offline.ui.kit.ScreenHeading
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.Tile
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

/**
 * «Все видео»: вся медиатека списком — поиск, фильтры, импорт, выбор
 * нескольких для экспорта или удаления. Коллекции — на главной; здесь
 * каждое видео один раз, как оно лежит на устройстве.
 */
@Composable
fun LibraryScreen(vm: MainViewModel, actions: NoxActions, contentPadding: PaddingValues) {
    val list by vm.library.collectAsState()
    val all by vm.allItems.collectAsState()
    val loaded by vm.libraryLoaded.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val sheets = LocalSheets.current
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selected.isNotEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("top") {
            Spacer(Modifier.height(8.dp))
            ScreenHeading("Все видео", "${list.size} из ${all.size} · ${filter.sort.label.lowercase()}", onBack = actions.back, trailing = {
                RoundButton(Icons.Rounded.FileDownload, "Импортировать видео с устройства", actions.pickImport, size = 44.dp)
            })
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                KitField(query, { vm.query.value = it }, "Поиск по названию или каналу", Modifier.weight(1f), leading = Icons.Rounded.Search)
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Rounded.Tune, "Фильтр и сортировка", actions.openFilter, size = 48.dp, accent = !filter.isDefault)
            }
            SectionGap()
        }
        if (selecting) {
            item("selection") {
                Section(null, contentPadding = PaddingValues(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Icons.Rounded.Close, "Снять выбор", { selected = emptySet() })
                        Spacer(Modifier.width(10.dp))
                        Text("Выбрано: ${selected.size}", color = Nox.TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Chip("Экспорт", icon = Icons.Rounded.IosShare, height = 36.dp, onClick = {
                            actions.pickExportFor(all.filter { it.id in selected }); selected = emptySet()
                        })
                        Spacer(Modifier.width(8.dp))
                        RoundButton(Icons.Rounded.Delete, "Удалить выбранные", {
                            val chosen = all.filter { it.id in selected }
                            sheets.confirm("Удалить ${chosen.size} видео?",
                                "Файлы будут удалены с устройства, позиции просмотра — тоже. Коллекции сохранят ссылки на ролики, " +
                                    "у которых источник известен. Это нельзя отменить.", "Удалить", danger = true) {
                                chosen.forEach { vm.deleteMedia(it.media) }
                                selected = emptySet()
                            }
                        }, tint = Nox.Danger)
                    }
                }
                SectionGap()
            }
        }
        item("list") {
            Section(null, contentPadding = PaddingValues(10.dp)) {
                when {
                    !loaded -> Spacer(Modifier.height(80.dp))
                    all.isEmpty() -> EmptyBlock(Icons.Rounded.VideoLibrary, "Медиатека пуста",
                        "Скачайте видео во вкладке «Загрузки» или импортируйте файлы с устройства.",
                        action = "Импортировать видео", onAction = actions.pickImport)
                    list.isEmpty() -> EmptyBlock(Icons.Rounded.Search, "Ничего не найдено", "Попробуйте другой запрос или сбросьте фильтры.",
                        action = "Сбросить", onAction = { vm.query.value = ""; vm.filter.value = LibraryFilter() })
                    else -> {
                        Text("Удерживайте видео, чтобы выбрать несколько.", color = LavenderText, fontSize = 12.5.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                        for (item in list) {
                            val isSel = item.id in selected
                            Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = isSel,
                                onClick = { if (selecting) selected = if (isSel) selected - item.id else selected + item.id
                                else actions.openMedia(item, false) },
                                onLongClick = { selected = if (isSel) selected - item.id else selected + item.id }) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box {
                                        Cover(item.media.coverPath, Modifier.width(112.dp).aspectRatio(16f / 9f), RoundedCornerShape(8.dp))
                                        DurationBadge(item.durationLabel, Modifier.align(Alignment.BottomEnd).padding(3.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.media.title, color = Nox.TextPrimary, fontSize = 14.sp, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(if (item.media.isAudio) "Только звук" else null, item.meta.ifBlank { null })
                                            .joinToString(" · "), color = LavenderText, fontSize = 12.sp, maxLines = 1)
                                        val p = item.playback
                                        if (p != null && p.durationMs > 0 && (p.inProgress || p.completed)) {
                                            ProgressLine(if (p.completed) 1f else p.positionMs.toFloat() / p.durationMs,
                                                Modifier.padding(top = 5.dp), height = 4.dp)
                                        }
                                    }
                                    Icon(Icons.Rounded.MoreVert, "Действия", tint = Color(0xFFD5D9FF),
                                        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                                            .clickable { actions.mediaMenu(item) }.padding(6.dp))
                                }
                            }
                        }
                    }
                }
            }
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
