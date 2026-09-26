@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material.icons.rounded.Tune
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
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import com.nox.offline.data.db.BookmarkEntity
import com.nox.offline.data.db.ChapterKind
import com.nox.offline.data.db.CollectionType
import com.nox.offline.library.Segments
import com.nox.offline.player.PlaybackHub
import com.nox.offline.player.PlayerActivity
import com.nox.offline.player.SleepTimer
import com.nox.offline.ui.LocalPickers
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.components.SheetAction
import com.nox.offline.ui.components.SheetController
import com.nox.offline.ui.kit.AmberButton
import com.nox.offline.ui.kit.Chip
import com.nox.offline.ui.kit.ChoiceRow
import com.nox.offline.ui.kit.InlineNote
import com.nox.offline.ui.kit.KitField
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.Tile
import com.nox.offline.ui.kit.TileButton
import com.nox.offline.ui.library.LibraryViewModel
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/** Кнопка «экран» (монитор): масштаб в окне и полноэкранный режим. Это не трансляция на ТВ. */
@OptIn(UnstableApi::class)
fun screenSheet(sheets: SheetController, hub: PlaybackHub, context: Context) {
    sheets.show("Экран") { close ->
        val st by hub.state.collectAsState()
        Text("Масштаб в окне", color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        ChoiceRow(listOf(false to "Вписать", true to "Заполнить"), st.zoom, { hub.setZoom(it) })
        InlineNote("Без растягивания: «Заполнить» обрезает края, пропорции кадра не меняются.")
        Spacer(Modifier.height(8.dp))
        TileButton("Во весь экран", { close(); context.startActivity(PlayerActivity.fullscreen(context)) }, Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Fullscreen, enabled = st.hasVideo && !st.listen)
        Spacer(Modifier.height(6.dp))
        TileButton("Картинка в картинке", { close(); context.startActivity(PlayerActivity.fullscreen(context, pip = true)) },
            Modifier.fillMaxWidth(), icon = Icons.Rounded.CropFree, enabled = st.hasVideo && !st.listen)
    }
}

@OptIn(UnstableApi::class)
fun playerMenu(sheets: SheetController, hub: PlaybackHub, lib: LibraryViewModel, vm: MainViewModel, nav: Nav, context: Context) {
    val now = hub.state.value.now ?: return
    val m = now.media
    sheets.actions(now.title, null, listOfNotNull(
        SheetAction("Серии и главы файла", Icons.Rounded.Toc, hint = "Разметка без разрезания файла") { chaptersSheet(sheets, hub, lib) },
        SheetAction("Закладка на ${Segments.clock(hub.state.value.absoluteMs)}", Icons.Rounded.BookmarkBorder) {
            bookmarkSheet(sheets, lib, m.id, hub.state.value.absoluteMs, null)
        },
        if (now.segment != null) SheetAction("Смотреть весь файл", Icons.Rounded.Fullscreen) { hub.playWholeFile() } else null,
        SheetAction("Добавить в коллекцию", Icons.AutoMirrored.Rounded.PlaylistAdd) { addToCollectionSheet(sheets, lib, m.id) },
        SheetAction("Посмотреть позже", Icons.Rounded.Schedule) { lib.toggleSystem(CollectionType.WATCH_LATER, m.id) {
            com.nox.offline.core.AppEvents.notice(if (it) "Добавлено в «Посмотреть позже»" else "Убрано из «Посмотреть позже»") } },
        SheetAction(if (m.protectedFromCleanup) "Снять защиту от очистки" else "Защитить от очистки", Icons.Rounded.Shield) {
            vm.setProtected(m, !m.protectedFromCleanup)
        },
        SheetAction("Таймер сна", Icons.Rounded.Timelapse) { sleepSheet(sheets, hub) },
        SheetAction("Настройки плеера", Icons.Rounded.Tune, hint = "Автопереход, «картинка в картинке»") {
            com.nox.offline.ui.settings.playerSettingsSheet(sheets, vm)
        },
        SheetAction("Закрыть видео", Icons.Rounded.Close, hint = "Позиция сохранится") { hub.close() },
    ))
}

private fun addToCollectionSheet(sheets: SheetController, lib: LibraryViewModel, mediaId: Long) {
    sheets.show("В коллекцию") { close ->
        val all by lib.collections.collectAsState()
        val own = all.filter { it.type != CollectionType.FAVORITES && it.type != CollectionType.WATCH_LATER }
        if (own.isEmpty()) InlineNote("Коллекций пока нет — создайте её на главной.")
        for (c in own) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), onClick = { lib.addMedia(c.id, listOf(mediaId)); close() }) {
                Row(Modifier.padding(12.dp)) {
                    Text(c.title, color = Nox.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${c.local} видео", color = LavenderText, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Таймер: 30 минут, час, своё время, после серии/главы, после файла. */
@OptIn(UnstableApi::class)
fun sleepSheet(sheets: SheetController, hub: PlaybackHub) {
    sheets.show("Таймер сна") { close ->
        val st by hub.state.collectAsState()
        var minutes by remember { mutableStateOf("45") }
        st.sleep?.let {
            Text("Сейчас: ${it.label}" + (st.sleepRemainingMs?.let { r -> " · осталось ${Segments.clock(r)}" } ?: ""),
                color = nox().accentLight, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in SleepTimer.PRESETS) TileButton(if (m == 60) "1 час" else "$m мин", { hub.sleepMinutes(m); close() }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KitField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, "Минут", Modifier.weight(1f))
            TileButton("Своё время", { minutes.toIntOrNull()?.takeIf { it in 1..1440 }?.let { hub.sleepMinutes(it); close() } },
                Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        TileButton("После текущей серии или главы", { hub.setSleep(SleepTimer.Mode.AfterSegment); close() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        TileButton("После текущего файла", { hub.setSleep(SleepTimer.Mode.AfterFile); close() }, Modifier.fillMaxWidth())
        if (st.sleep != null) {
            Spacer(Modifier.height(6.dp))
            TileButton("Выключить таймер", { hub.setSleep(null); close() }, Modifier.fillMaxWidth())
        }
        InlineNote("Звук плавно стихает громкостью самого плеера (системная громкость не меняется), позиция сохраняется. " +
            "Таймер работает и во весь экран, и в «картинке в картинке», и в режиме «Слушать».")
    }
}

/** Закладка: момент, название, заметка. */
fun bookmarkSheet(sheets: SheetController, lib: LibraryViewModel, mediaId: Long, positionMs: Long, existing: BookmarkEntity?) {
    sheets.show(if (existing == null) "Закладка на ${Segments.clock(positionMs)}" else "Закладка") { close ->
        var title by remember { mutableStateOf(existing?.title.orEmpty()) }
        var note by remember { mutableStateOf(existing?.note.orEmpty()) }
        KitField(title, { title = it }, "Название")
        Spacer(Modifier.height(8.dp))
        KitField(note, { note = it }, "Заметка", singleLine = false, minHeight = 80.dp)
        Spacer(Modifier.height(12.dp))
        AmberButton("Сохранить", {
            if (existing == null) lib.addBookmark(mediaId, positionMs, title, note) else lib.updateBookmark(existing.copy(title = title, note = note))
            close()
        }, height = 48.dp, textSize = 16.sp)
        if (existing != null) {
            Spacer(Modifier.height(8.dp))
            TileButton("Удалить закладку", { lib.deleteBookmark(existing.id); close() }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Разметка файла: главы и виртуальные серии как интервалы. Файл остаётся
 * одним файлом. Никакого автоматического деления «по 24 минуты» —
 * отметки ставит пользователь (или приходят от источника).
 */
@OptIn(UnstableApi::class)
fun chaptersSheet(sheets: SheetController, hub: PlaybackHub, lib: LibraryViewModel) {
    val now0 = hub.state.value.now ?: return
    val mediaId = now0.media.id
    sheets.show("Серии и главы") { _ ->
        val st by hub.state.collectAsState()
        val flow = remember(mediaId) { lib.chapters(mediaId) }
        val raw by flow.collectAsState(initial = emptyList())
        val duration = st.now?.durationMs ?: now0.durationMs
        val segs = Segments.resolve(raw, duration)
        val abs = st.absoluteMs
        Text("Сейчас в файле: ${Segments.clock(abs)}", color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileButton("Серия отсюда", { lib.addChapterAt(mediaId, abs, "", ChapterKind.EPISODE, duration) }, Modifier.weight(1f),
                icon = Icons.Rounded.Add)
            TileButton("Глава отсюда", { lib.addChapterAt(mediaId, abs, "", ChapterKind.CHAPTER, duration) }, Modifier.weight(1f),
                icon = Icons.Rounded.Add)
        }
        InlineNote("Серия — со своей позицией и отметкой «просмотрено»; глава — пункт оглавления. Конец — до следующей отметки того же вида.")
        if (raw.isEmpty()) InlineNote("Отметок пока нет.")
        for (c in raw.sortedBy { it.startMs }) {
            val seg = segs.firstOrNull { it.id == c.id }
            Tile(Modifier.fillMaxWidth().padding(top = 6.dp), onClick = { editChapterSheet(sheets, lib, c, duration) }) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip(if (c.kind == ChapterKind.EPISODE) "Серия" else "Глава", amber = c.kind == ChapterKind.EPISODE, height = 22.dp,
                            textSize = 11.sp)
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(c.title, color = Nox.TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    }
                    Text("${Segments.clock(c.startMs)} – ${seg?.endMs?.let { Segments.clock(it) } ?: "?"}" +
                        if (c.origin == "source") " · от источника" else "", color = LavenderText, fontSize = 12.5.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (seg != null) Chip("Перейти", onClick = { hub.jumpTo(seg) }, height = 28.dp)
                        Chip(if (c.kind == ChapterKind.EPISODE) "Сделать главой" else "Сделать серией", height = 28.dp,
                            onClick = { lib.setChapterKind(c.id, if (c.kind == ChapterKind.EPISODE) ChapterKind.CHAPTER else ChapterKind.EPISODE) })
                        Chip("Объединить со следующей", height = 28.dp, onClick = { lib.mergeChapter(c.id) })
                    }
                }
            }
        }
        val pageUrl = st.now?.media?.pageUrl.orEmpty()
        if (pageUrl.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val context = androidx.compose.ui.platform.LocalContext.current
            TileButton("Главы источника (нужна сеть)", {
                scope.launch_io {
                    val r = com.nox.offline.NoxApp.get(context).resolver.analyze(pageUrl)
                    val ch = (r as? com.nox.offline.downloader.catalog.AnalyzeResult.Ok)?.analysis?.details?.chapters.orEmpty()
                    if (ch.isEmpty()) com.nox.offline.core.AppEvents.notice(
                        if (r is com.nox.offline.downloader.catalog.AnalyzeResult.Ok) "Источник не передаёт глав для этого видео"
                        else "Не удалось связаться с источником")
                    else lib.importSourceChapters(mediaId, ch.map { Triple(it.title, it.startMs, it.endMs) })
                }
            }, Modifier.fillMaxWidth())
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launch_io(block: suspend () -> Unit) {
    launch(kotlinx.coroutines.Dispatchers.IO) { block() }
}

private fun editChapterSheet(sheets: SheetController, lib: LibraryViewModel, c: com.nox.offline.data.db.ChapterEntity, duration: Long) {
    sheets.show("Изменить отметку") { close ->
        var title by remember { mutableStateOf(c.title) }
        var start by remember { mutableStateOf(Segments.clock(c.startMs)) }
        var end by remember { mutableStateOf(if (c.endMs > 0) Segments.clock(c.endMs) else "") }
        var error by remember { mutableStateOf<String?>(null) }
        KitField(title, { title = it }, "Название")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitField(start, { start = it }, "Начало ч:мм:сс", Modifier.weight(1f))
            KitField(end, { end = it }, "Конец (пусто — до следующей)", Modifier.weight(1f))
        }
        InlineNote("Формат: 1:02:03, 45:10 или 1:00:00:00 для многосуточных записей. Файл длится ${Segments.clock(duration)}.")
        error?.let { InlineNote(it, danger = true) }
        Spacer(Modifier.height(10.dp))
        AmberButton("Сохранить", {
            val s = Segments.parseClock(start)
            val e = if (end.isBlank()) -1L else Segments.parseClock(end)
            if (s == null || (end.isNotBlank() && e == null)) { error = "Не понял время"; return@AmberButton }
            lib.editChapter(c.id, title, s, e ?: -1, duration) { err -> if (err == null) close() else error = err }
        }, height = 48.dp, textSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        TileButton("Удалить отметку", {
            sheets.confirm("Удалить отметку?", "Удаляется только разметка — видео остаётся целым.", "Удалить", danger = true) {
                lib.deleteChapter(c.id)
            }
            close()
        }, Modifier.fillMaxWidth())
    }
}

/** Субтитры: дорожки видео, свой файл SRT/VTT, размер, подложка, сдвиг синхронизации. */
@OptIn(UnstableApi::class)
@Composable
fun SubtitlesPane(st: PlaybackHub.State, hub: PlaybackHub, lib: LibraryViewModel, vm: MainViewModel) {
    val now = st.now ?: return
    val m = now.media
    val flow = remember(m.id) { lib.subtitles(m.id) }
    val subs by flow.collectAsState(initial = emptyList())
    val prefs by vm.playerPrefs.collectAsState()
    val pickers = LocalPickers.current
    Section("Субтитры") {
        Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = m.subtitleId == 0L, onClick = { hub.selectSubtitle(0) }) {
            Text("Выключены", color = Nox.TextPrimary, fontSize = 14.5.sp, modifier = Modifier.padding(12.dp))
        }
        for (s in subs) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = m.subtitleId == s.id, onClick = { hub.selectSubtitle(s.id) },
                onLongClick = { lib.deleteSubtitle(s.id); if (m.subtitleId == s.id) hub.selectSubtitle(0) }) {
                Row(Modifier.padding(12.dp)) {
                    Text(s.label, color = Nox.TextPrimary, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                    Text(when (s.origin) { "auto" -> "автоматические"; "source" -> "авторские"; else -> "свой файл" },
                        color = LavenderText, fontSize = 12.sp)
                }
            }
        }
        TileButton("Добавить свой SRT или VTT", { pickers.pickSubtitle { uri -> lib.importSubtitle(m.id, uri) { id -> hub.selectSubtitle(id) } } },
            Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
        if (subs.isNotEmpty()) InlineNote("Удержание дорожки — удалить её (видео не трогается).")
        Spacer(Modifier.height(10.dp))
        Text("Размер", color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        ChoiceRow(listOf(0.8f to "Меньше", 1f to "Обычный", 1.3f to "Крупнее", 1.7f to "Крупный"), prefs.subtitleScale,
            { v -> vm.settings.updatePlayer { it.copy(subtitleScale = v) } }, textSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Подложка", color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        ChoiceRow(listOf(0 to "Нет (контур)", 1 to "Полупрозрачная", 2 to "Плотная"), prefs.subtitleBackground,
            { v -> vm.settings.updatePlayer { it.copy(subtitleBackground = v) } }, textSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        val off = m.subtitleOffsetMs
        Text("Синхронизация: " + when {
            off > 0 -> "+${off / 1000.0} с (субтитры позже)"
            off < 0 -> "${off / 1000.0} с (субтитры раньше)"
            else -> "0 с"
        }, color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((d, l) in listOf(-1000L to "−1 с", -250L to "−0.25", 250L to "+0.25", 1000L to "+1 с")) {
                TileButton(l, { hub.setSubtitleOffset(off + d) }, Modifier.weight(1f), height = 40.dp)
            }
        }
        if (off != 0L) TileButton("Сбросить сдвиг", { hub.setSubtitleOffset(0) }, Modifier.fillMaxWidth().padding(top = 6.dp), height = 40.dp)
    }
}
