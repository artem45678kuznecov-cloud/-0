package com.nox.offline.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VideoLibrary
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
import com.nox.offline.library.Segments
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Tab
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.SheetAction
import com.nox.offline.ui.components.SheetController
import com.nox.offline.ui.kit.AmberButton
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
import com.nox.offline.ui.kit.TileButton
import com.nox.offline.ui.library.CollectionDetail
import com.nox.offline.ui.library.LibraryViewModel
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/**
 * Коллекция или сериал: обложка, описание, «Продолжить», сезоны и серии
 * в ручном порядке, отметки «просмотрено», ожидающие загрузки элементы.
 * Удаление из коллекции не удаляет видео; удаление видео — отдельно и с
 * подтверждением.
 */
@Composable
fun CollectionScreen(id: Long, lib: LibraryViewModel, vm: MainViewModel, nav: Nav, padding: PaddingValues) {
    val flow = remember(id) { lib.detail(id) }
    val detail by flow.collectAsState(initial = null)
    val sheets = LocalSheets.current
    val actions = collectionActions(lib, nav, sheets)
    val d = detail
    LazyColumn(contentPadding = padding) {
        item {
            Spacer(Modifier.height(8.dp))
            ScreenHeading(d?.collection?.title ?: "Коллекция",
                d?.let { "${typeLabel(it.collection.type).replaceFirstChar(Char::uppercase)} · ${it.local.size} видео" +
                    if (it.missing.isNotEmpty()) " · не скачано: ${it.missing.size}" else "" },
                onBack = { nav.back() },
                trailing = if (d != null) ({ RoundButton(Icons.Rounded.MoreVert, "Меню коллекции", { actions.menu(d.collection, d.local.size) }) })
                else null)
            SectionGap()
        }
        if (d == null) return@LazyColumn
        item {
            Section(null, contentPadding = PaddingValues(10.dp)) {
                val cover = d.collection.coverPath.ifBlank {
                    d.items.firstOrNull { it.media?.id == d.collection.coverMediaId }?.media?.coverPath
                        ?: d.local.firstOrNull()?.media?.coverPath.orEmpty()
                }
                Cover(cover, Modifier.fillMaxWidth().aspectRatio(2.4f), RoundedCornerShape(10.dp))
                if (d.collection.description.isNotBlank()) {
                    Text(d.collection.description, color = LavenderText, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                }
                val tags = d.collection.tagList
                if (tags.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in tags.take(4)) Chip(t)
                }
                Spacer(Modifier.height(12.dp))
                val next = d.continueEntry
                if (next != null) {
                    AmberButton(
                        if (next.inProgress) "Продолжить: ${next.title.take(28)}" else "Смотреть: ${next.title.take(30)}",
                        { lib.play(d, next); nav.select(Tab.PLAYER) }, icon = Icons.Rounded.PlayArrow, height = 50.dp, textSize = 15.sp,
                        trailing = if (next.inProgress) Segments.clock(next.positionMs) else null)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TileButton("Добавить видео", { addVideosSheet(sheets, vm, lib, d) }, Modifier.weight(1f), icon = Icons.Rounded.Add)
                    if (d.missing.isNotEmpty()) {
                        TileButton("Скачать недостающие", { downloadMissing(sheets, vm, nav, d) }, Modifier.weight(1f),
                            icon = Icons.Rounded.CloudDownload)
                    }
                }
            }
            SectionGap()
        }
        if (d.items.isEmpty()) {
            item {
                Section(null) {
                    EmptyBlock(Icons.Rounded.VideoLibrary, "Здесь пока пусто",
                        "Добавьте скачанные или импортированные видео. Файлы не копируются — коллекция хранит только связи.",
                        action = "Добавить видео", onAction = { addVideosSheet(sheets, vm, lib, d) })
                }
            }
            return@LazyColumn
        }
        val groups = if (d.isSeries) d.items.groupBy { it.item.season }.toSortedMap().toList() else listOf(-1 to d.items)
        for ((season, entries) in groups) {
            item(key = "season-$season") {
                Section(if (season < 0) "Видео" else d.seasonTitle(season), trailing = "${entries.size}",
                    onTitleClick = if (season >= 0) ({ seasonTitleSheet(sheets, lib, d, season) }) else null) {
                    for ((i, e) in entries.withIndex()) {
                        EntryRow(e, index = if (d.isSeries) (e.item.episode.takeIf { it > 0 } ?: (i + 1)) else null,
                            onPlay = { if (e.media != null) { lib.play(d, e); nav.select(Tab.PLAYER) } },
                            onMenu = { entryMenu(sheets, lib, vm, d, e) })
                        if (i < entries.lastIndex) Spacer(Modifier.height(6.dp))
                    }
                }
                SectionGap()
            }
        }
    }
}

@Composable
private fun EntryRow(e: CollectionDetail.Entry, index: Int?, onPlay: () -> Unit, onMenu: () -> Unit) {
    val p = nox()
    Tile(Modifier.fillMaxWidth(), onClick = onPlay, onLongClick = onMenu) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Cover(e.media?.coverPath.orEmpty(), Modifier.width(104.dp).aspectRatio(16f / 9f), RoundedCornerShape(8.dp),
                    showPlaceholderIcon = e.media != null)
                if (e.media == null) Icon(Icons.Rounded.CloudDownload, null, tint = LavenderText,
                    modifier = Modifier.align(Alignment.Center).size(24.dp))
                if (e.watched) Icon(Icons.Rounded.CheckCircle, "Просмотрено", tint = p.accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text((if (index != null) "$index. " else "") + e.title, color = if (e.media != null) Nox.TextPrimary else Nox.TextSecondary,
                    fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val meta = listOfNotNull(
                    e.durationMs.takeIf { it > 0 }?.let { Segments.clock(it) },
                    if (e.chapter != null) "часть файла" else null,
                    e.state.ifBlank { null },
                ).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = LavenderText, fontSize = 12.sp, maxLines = 2)
                if (e.inProgress) ProgressLine(e.fraction, Modifier.padding(top = 5.dp), height = 4.dp)
            }
            Icon(Icons.Rounded.MoreVert, "Действия", tint = Color(0xFFD5D9FF),
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).clickable(onClick = onMenu).padding(6.dp))
        }
    }
}

private fun entryMenu(sheets: SheetController, lib: LibraryViewModel, vm: MainViewModel, d: CollectionDetail, e: CollectionDetail.Entry) {
    val m = e.media
    sheets.actions(e.title, e.state.ifBlank { null }, listOfNotNull(
        if (m != null) SheetAction(if (e.inProgress) "Продолжить" else "Смотреть", Icons.Rounded.PlayArrow) { lib.play(d, e) } else null,
        if (m != null && e.fraction > 0f) SheetAction("Смотреть сначала", Icons.Rounded.Replay) { lib.play(d, e, fromStart = true) } else null,
        if (m != null) SheetAction(if (e.watched) "Отметить непросмотренной" else "Отметить просмотренной",
            if (e.watched) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.CheckCircle) { lib.setWatched(e, !e.watched) } else null,
        if (d.isSeries) SheetAction("Номер и название серии", Icons.Rounded.Edit, hint = "Сезон ${e.item.season}, серия ${e.item.episode}") {
            numbersSheet(sheets, lib, e)
        } else null,
        SheetAction("Выше", Icons.Rounded.ArrowUpward) { lib.moveItem(e.item.id, -1) },
        SheetAction("Ниже", Icons.Rounded.ArrowDownward) { lib.moveItem(e.item.id, 1) },
        if (m != null) SheetAction("Сделать обложкой коллекции", Icons.Rounded.Image) { lib.setCoverMedia(d.collection.id, m.id) } else null,
        SheetAction("Убрать из коллекции", Icons.Rounded.RemoveCircleOutline, hint = "Видео останется в медиатеке") {
            lib.removeItem(e.item.id)
        },
        if (m != null) SheetAction("Удалить видео с устройства", Icons.Rounded.Delete, danger = true) {
            sheets.confirm("Удалить видео?", "Файл «${m.title}» будет удалён с устройства. Если у серии известна ссылка, она " +
                "останется в коллекции как «не скачано».", "Удалить", danger = true) { vm.deleteMedia(m) }
        } else null,
    ))
}

/** Номера — только подсказка по названию; пользователь исправляет, NOX ничего не переименовывает сам. */
private fun numbersSheet(sheets: SheetController, lib: LibraryViewModel, e: CollectionDetail.Entry) {
    sheets.show("Серия") { close ->
        val guess = e.media?.title?.let { com.nox.offline.library.SeriesNumbering.guess(it) }
        var season by remember { mutableStateOf(e.item.season.toString()) }
        var episode by remember { mutableStateOf(e.item.episode.takeIf { it > 0 }?.toString().orEmpty()) }
        var title by remember { mutableStateOf(e.item.title) }
        if (guess != null) {
            Text("По названию файла похоже на: сезон ${guess.season.takeIf { it > 0 } ?: "—"}, серия ${guess.episode}",
                color = LavenderText, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Chip("Подставить", amber = true, onClick = {
                if (guess.season > 0) season = guess.season.toString()
                episode = guess.episode.toString()
            })
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitField(season, { season = it.filter(Char::isDigit).take(3) }, "Сезон", Modifier.weight(1f))
            KitField(episode, { episode = it.filter(Char::isDigit).take(5) }, "Серия", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        KitField(title, { title = it }, "Своё название серии (необязательно)")
        Spacer(Modifier.height(12.dp))
        AmberButton("Сохранить", {
            lib.setNumbers(e.item.id, season.toIntOrNull() ?: 0, episode.toIntOrNull() ?: 0, title)
            close()
        }, height = 48.dp, textSize = 16.sp)
    }
}

private fun seasonTitleSheet(sheets: SheetController, lib: LibraryViewModel, d: CollectionDetail, season: Int) {
    sheets.show("Название сезона $season") { close ->
        var t by remember { mutableStateOf(d.seasons.firstOrNull { it.number == season }?.title.orEmpty()) }
        KitField(t, { t = it }, "Например: Тренировка в Деревне Кузнецов")
        Spacer(Modifier.height(12.dp))
        AmberButton("Сохранить", { lib.setSeasonTitle(d.collection.id, season, t); close() }, height = 48.dp, textSize = 16.sp)
    }
}

/** Выбор видео из медиатеки для добавления в коллекцию. */
private fun addVideosSheet(sheets: SheetController, vm: MainViewModel, lib: LibraryViewModel, d: CollectionDetail) {
    sheets.show("Добавить в «${d.collection.title}»") { close ->
        val all by vm.allItems.collectAsState()
        var q by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf(setOf<Long>()) }
        var season by remember { mutableStateOf("1") }
        val present = d.items.mapNotNull { it.media?.id }.toSet()
        KitField(q, { q = it }, "Поиск по названию")
        if (d.isSeries) {
            Spacer(Modifier.height(8.dp))
            KitField(season, { season = it.filter(Char::isDigit).take(3) }, "Сезон для новых серий")
            Text("Номера серий NOX предложит по названиям файлов — их можно исправить.", color = LavenderText, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        val list = all.filter { it.media.id !in present && (q.isBlank() || it.media.title.contains(q, true)) }
        if (list.isEmpty()) Text(if (all.isEmpty()) "В медиатеке пока нет видео." else "Все подходящие видео уже здесь.",
            color = LavenderText, fontSize = 14.sp)
        for (it in list.take(200)) {
            val on = it.media.id in chosen
            Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = on,
                onClick = { chosen = if (on) chosen - it.media.id else chosen + it.media.id }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(it.media.coverPath, Modifier.width(78.dp).aspectRatio(16f / 9f), RoundedCornerShape(7.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.media.title, color = Nox.TextPrimary, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(it.meta, color = LavenderText, fontSize = 11.5.sp, maxLines = 1)
                    }
                    Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (on) nox().accent else LavenderText)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        AmberButton("Добавить (${chosen.size})", {
            lib.addMedia(d.collection.id, chosen.toList(), if (d.isSeries) season.toIntOrNull() ?: 0 else 0)
            close()
        }, enabled = chosen.isNotEmpty(), height = 50.dp, textSize = 16.sp)
    }
}

/**
 * «Скачать недостающие»: только элементы с известной ссылкой. Каждая
 * ссылка проходит обычный выбор качества в пакете — после подтверждения.
 */
private fun downloadMissing(sheets: SheetController, vm: MainViewModel, nav: Nav, d: CollectionDetail) {
    val urls = d.missing.map { it.item.sourceUrl }.distinct()
    sheets.confirm("Скачать недостающие?",
        "Не скачано: ${urls.size}. Для каждой ссылки NOX покажет варианты качества, и вы подтвердите загрузку. " +
            "Скачанное само встанет на своё место в коллекции.", "Выбрать качество") {
        nav.select(Tab.DOWNLOADS)
        vm.pendingBatch.value = urls.joinToString("\n")
    }
}
