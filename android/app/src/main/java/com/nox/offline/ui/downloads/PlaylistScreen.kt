package com.nox.offline.ui.downloads

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.Format
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Page
import com.nox.offline.ui.Tab
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.DurationBadge
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.kit.AmberButton
import com.nox.offline.ui.kit.Chip
import com.nox.offline.ui.kit.ChoiceRow
import com.nox.offline.ui.kit.InlineNote
import com.nox.offline.ui.kit.KitField
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ScreenHeading
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.Tile
import com.nox.offline.ui.kit.TileButton
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/**
 * Плейлист: список в порядке источника, выбор (все / ничего / диапазон /
 * недостающие), настоящие качества каждого выбранного, оценка объёма,
 * «Скачать» или «Создать коллекцию». Закрытые и удалённые — с причиной.
 */
@Composable
fun PlaylistScreen(url: String, vm: MainViewModel, nav: Nav, padding: PaddingValues) {
    val session = vm.playlist
    val st by session.state.collectAsState()
    val sheets = LocalSheets.current
    LaunchedEffect(url) { session.open(url) }
    DisposableEffect(Unit) { onDispose { session.cancel() } }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }

    LazyColumn(contentPadding = padding) {
        item {
            Spacer(Modifier.height(8.dp))
            ScreenHeading(st.title.ifBlank { "Плейлист" },
                listOfNotNull(st.uploader.ifBlank { null }, if (st.total > 0) "${st.total} видео у источника" else null,
                    "показано ${st.items.size}").joinToString(" · "),
                onBack = { nav.back() })
            SectionGap()
        }
        st.error?.let { e ->
            item {
                Section(null, contentPadding = PaddingValues(12.dp)) {
                    Row {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = Nox.Danger)
                        Spacer(Modifier.width(10.dp))
                        Text(e.message, color = LavenderText, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    TileButton("Повторить", { session.reset(); session.open(url) }, Modifier.fillMaxWidth())
                }
                SectionGap()
            }
        }
        item {
            val (sum, unknown) = st.estimate()
            Section("Выбор", trailing = "${st.selected.size} из ${st.items.count { it.available }}", contentPadding = PaddingValues(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Все", onClick = { session.selectAll(true) }, height = 34.dp)
                    Chip("Ничего", onClick = { session.selectAll(false) }, height = 34.dp)
                    Chip("Недостающие", onClick = { session.selectMissing() }, height = 34.dp, amber = true)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KitField(from, { from = it.filter(Char::isDigit).take(4) }, "с №", Modifier.weight(1f))
                    KitField(to, { to = it.filter(Char::isDigit).take(4) }, "по №", Modifier.weight(1f))
                    Chip("Диапазон", height = 40.dp, onClick = {
                        val a = from.toIntOrNull(); val b = to.toIntOrNull()
                        if (a != null && b != null) session.selectRange(a, b)
                    })
                }
                Spacer(Modifier.height(10.dp))
                ChoiceRow(listOf(false to "Видео", true to "Только звук"), st.audioOnly, { session.setAudioOnly(it) }, height = 34.dp)
                Spacer(Modifier.height(10.dp))
                if (st.pendingAnalysis > 0) {
                    TileButton("Посмотреть качества выбранных (${st.pendingAnalysis})", { session.analyzeSelected() }, Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.Search)
                    InlineNote("Каждое видео разбирается отдельно — видны его настоящие варианты. Одновременно не больше двух.")
                }
                val ready = st.analyzedSelected.size
                Text(buildString {
                    append("Готово к загрузке: $ready")
                    if (sum > 0) append(" · ≈ ${Format.bytes(sum)}")
                    if (unknown > 0) append(" · размер неизвестен у $unknown")
                }, color = Nox.TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                AmberButton("Скачать выбранные", {
                    vm.enqueuePlaylist(collection = false) { nav.root(Tab.DOWNLOADS) }
                }, icon = Icons.Rounded.Download, enabled = ready > 0, height = 50.dp, textSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                TileButton("Создать коллекцию и скачать", {
                    sheets.confirm("Создать коллекцию?", "Весь список попадёт в коллекцию в порядке источника. Выбранные (" +
                        "$ready) встанут в очередь, остальные останутся «не скачано» — их можно докачать позже без дублей.",
                        "Создать") {
                        vm.enqueuePlaylist(collection = true) { id -> if (id != null) nav.open(Page.Collection(id), Tab.HOME) }
                    }
                }, Modifier.fillMaxWidth(), icon = Icons.Rounded.CollectionsBookmark)
            }
            SectionGap()
        }
        item {
            Section("Видео списка", trailing = if (st.loading) "загрузка…" else null) {
                if (st.items.isEmpty() && st.loading) InlineNote("Открываем список источника…")
                for (it in st.items) {
                    PlaylistRow(it, onToggle = { session.toggle(it.entry.index) },
                        onQuality = {
                            val r = it.state as? FinderState.Ready ?: return@PlaylistRow
                            sheets.show(it.entry.title) { close ->
                                for (v in r.catalog.main) {
                                    Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = v.key == r.selectedKey,
                                        onClick = if (v.support.ok) ({ session.selectVariant(it.entry.index, v.key); close() }) else null) {
                                        Row(Modifier.padding(12.dp)) {
                                            Text(v.title, color = Nox.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                            Text(sizeText(v), color = LavenderText, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        })
                    Spacer(Modifier.height(6.dp))
                }
                if (st.hasMore) {
                    TileButton(if (st.loading) "Загружаем…" else "Показать ещё", { session.loadMore() }, Modifier.fillMaxWidth(),
                        enabled = !st.loading)
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(it: PlaylistSession.Item, onToggle: () -> Unit, onQuality: () -> Unit) {
    val e = it.entry
    Tile(Modifier.fillMaxWidth(), selected = it.selected, onClick = if (it.available) onToggle else null) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(when {
                !it.available -> Icons.Rounded.Block
                it.selected -> Icons.Rounded.CheckBox
                else -> Icons.Rounded.CheckBoxOutlineBlank
            }, null, tint = if (it.selected) nox().accent else LavenderText, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.layout.Box {
                Cover(e.thumbnail, Modifier.size(width = 92.dp, height = 52.dp), RoundedCornerShape(7.dp))
                if (e.durationSec > 0) DurationBadge(Format.clock(e.durationSec), Modifier.align(Alignment.BottomEnd).padding(2.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${e.index}. ${e.title}", color = if (it.available) Nox.TextPrimary else Nox.TextMuted, fontSize = 13.5.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val line = when {
                    !it.available -> e.unavailable
                    it.presence != null -> it.presence.replaceFirstChar(Char::uppercase)
                    else -> when (val s = it.state) {
                        is FinderState.Searching -> "Смотрим качества…"
                        is FinderState.Ready -> s.selected?.let { v -> "${v.title} · ${sizeText(v)}" } ?: "Нет подходящего варианта"
                        is FinderState.Failed -> s.error.message
                        else -> ""
                    }
                }
                if (line.isNotBlank()) Text(line, color = if (it.state is FinderState.Failed) Nox.Danger else LavenderText, fontSize = 12.sp,
                    maxLines = 2)
            }
            if (it.state is FinderState.Ready) Chip("Качество", onClick = onQuality, height = 30.dp, textSize = 11.5.sp)
        }
    }
}
