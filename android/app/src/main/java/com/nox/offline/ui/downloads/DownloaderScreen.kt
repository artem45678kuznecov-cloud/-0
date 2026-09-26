package com.nox.offline.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.Format
import com.nox.offline.core.SafeUrl
import com.nox.offline.downloader.catalog.AudioVariant
import com.nox.offline.downloader.catalog.CatalogBuilder
import com.nox.offline.downloader.catalog.CodecNames
import com.nox.offline.downloader.catalog.Playback
import com.nox.offline.downloader.catalog.SizeKind
import com.nox.offline.downloader.catalog.Variant
import com.nox.offline.library.SeriesNumbering
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Page
import com.nox.offline.ui.Tab
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.DurationBadge
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.SheetAction
import com.nox.offline.ui.components.SheetController
import com.nox.offline.ui.kit.AmberButton
import com.nox.offline.ui.kit.BrandHeader
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

/** Ссылка — это плейлист (или видео внутри плейлиста). */
object PlaylistLinks {
    fun listId(url: String): String? = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
    fun videoId(url: String): String? = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
    /** Только список, без конкретного видео: сразу страница плейлиста. */
    fun isPurePlaylist(url: String): Boolean = listId(url) != null && (videoId(url) == null || url.contains("/playlist"))
    /** Видео внутри плейлиста: нужен выбор «это видео или весь список». */
    fun isVideoInPlaylist(url: String): Boolean = listId(url) != null && videoId(url) != null && !url.contains("/playlist")
    fun playlistUrl(url: String): String = "https://www.youtube.com/playlist?list=${listId(url)}"
}

/**
 * Загрузчик (макет 02): ссылка → «Найти видео» → карточка → настоящие
 * варианты качества этого видео → звуковая дорожка и субтитры → «Скачать».
 * Ниже — массовая загрузка списком ссылок. После постановки в очередь
 * экран возвращается к очереди.
 */
@Composable
fun DownloaderScreen(vm: MainViewModel, nav: Nav, padding: PaddingValues, onBatch: (String) -> Unit) {
    val url by vm.urlInput.collectAsState()
    val state by vm.finder.state.collectAsState()
    val message by vm.message.collectAsState()
    val sheets = LocalSheets.current
    val clipboard = LocalClipboardManager.current
    var batchText by remember { mutableStateOf("") }

    fun search() {
        val u = SafeUrl.extract(url) ?: url.trim()
        if (PlaylistLinks.isPurePlaylist(u)) { nav.open(Page.Playlist(u)); return }
        vm.findVideo()
    }

    LazyColumn(contentPadding = padding) {
        item {
            BrandHeader(onSearch = { search() }, onSettings = { nav.select(Tab.SETTINGS) }, searchLabel = "Найти видео",
                note = "Хорошие истории\nвсегда рядом.")
            Spacer(Modifier.height(8.dp))
            ScreenHeading("Загрузчик", "Скачивай видео из любимых источников", onBack = { nav.back() })
            SectionGap()
        }
        item {
            Section(null, contentPadding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, null, tint = Color(0xFFDCE0FF), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Ссылка на видео", color = Nox.TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF8F9BFF).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable { supportedSheet(sheets) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Поддерживаются: YouTube, VK и др.", color = Color(0xFFD5D9FF), fontSize = 11.sp, maxLines = 1)
                        Icon(Icons.Rounded.ExpandMore, null, tint = Color(0xFFD5D9FF), modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                KitField(url, vm::setUrl, "https://www.youtube.com/watch?v=…", imeAction = ImeAction.Search, onIme = { search() },
                    label = "Ссылка на видео",
                    trailing = {
                        if (url.isNotEmpty()) Icon(Icons.Rounded.Cancel, "Очистить ссылку", tint = Color(0xFFB9BFE6),
                            modifier = Modifier.size(34.dp).clip(CircleShape).clickable { vm.setUrl(""); vm.finder.cancel() }.padding(5.dp))
                        else Icon(Icons.Rounded.ContentPaste, "Вставить из буфера", tint = Color(0xFFB9BFE6),
                            modifier = Modifier.size(34.dp).clip(CircleShape)
                                .clickable { clipboard.getText()?.text?.let(vm::setUrl) }.padding(5.dp))
                    })
                Spacer(Modifier.height(12.dp))
                val searching = state is FinderState.Searching
                AmberButton(if (searching) "Ищем видео…" else "Найти видео", { if (searching) vm.finder.cancel() else search() },
                    icon = Icons.Rounded.Search, height = 52.dp)
                if (searching) InlineNote("Разбираем страницу источника. Нажмите ещё раз, чтобы отменить.")
                if (message.isNotBlank()) InlineNote(message, danger = true)
                if (PlaylistLinks.isVideoInPlaylist(SafeUrl.extract(url) ?: url)) {
                    Spacer(Modifier.height(10.dp))
                    Tile(Modifier.fillMaxWidth(), onClick = { nav.open(Page.Playlist(PlaylistLinks.playlistUrl(SafeUrl.extract(url) ?: url))) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.List, null, tint = nox().accentLight)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Это видео из плейлиста", color = Nox.TextPrimary, fontSize = 14.5.sp)
                                Text("«Найти видео» — только это видео. Или откройте весь список.", color = LavenderText, fontSize = 12.sp)
                            }
                            Text("Весь список", color = nox().accentLight, fontSize = 13.sp)
                        }
                    }
                }
            }
            SectionGap()
        }
        when (val s = state) {
            is FinderState.Failed -> item {
                Section(null, contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = Nox.Danger, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Не получилось", color = Nox.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(s.error.message, color = LavenderText, fontSize = 13.5.sp)
                        }
                    }
                    if (s.error.retryable) {
                        Spacer(Modifier.height(10.dp))
                        TileButton("Повторить", { vm.finder.retry() }, Modifier.fillMaxWidth())
                    }
                }
                SectionGap()
            }
            is FinderState.Ready -> {
                item { ResultCard(s, vm, sheets); SectionGap() }
                item { QualitySection(s, vm, sheets, nav); SectionGap() }
            }
            else -> Unit
        }
        item {
            Section("Массовая загрузка", icon = Icons.AutoMirrored.Rounded.List, subtitle = "Скачивай сразу несколько видео",
                onTrailing = { onBatch(batchText) }, onTitleClick = { onBatch(batchText) }, contentPadding = PaddingValues(12.dp)) {
                KitField(batchText, { batchText = it }, "Вставьте ссылки (по одной в строке)\nhttps://www.youtube.com/watch?v=…\nhttps://vk.com/video-…",
                    singleLine = false, minHeight = 96.dp, imeAction = ImeAction.Default, label = "Список ссылок")
                Spacer(Modifier.height(10.dp))
                TileButton("Разобрать ссылки", { onBatch(batchText) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    chevron = true, height = 48.dp)
            }
        }
    }
}

@Composable
private fun ResultCard(s: FinderState.Ready, vm: MainViewModel, sheets: SheetController) {
    val d = s.catalog.details
    val guess = remember(d.title) { SeriesNumbering.guess(d.title) }
    val context = LocalContext.current
    Section(null, contentPadding = PaddingValues(10.dp)) {
        Row {
            Box {
                Cover(d.thumbnail, Modifier.width(150.dp).height(78.dp), RoundedCornerShape(10.dp))
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp).size(32.dp).clip(CircleShape)
                    .border(1.5.dp, nox().accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                if (d.durationSec > 0) DurationBadge(Format.clock(d.durationSec), Modifier.align(Alignment.BottomEnd).padding(5.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, color = Nox.TextPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                if (guess != null) {
                    Text(listOfNotNull(guess.season.takeIf { it > 0 }?.let { "$it сезон" }, "${guess.episode} серия").joinToString(" • "),
                        color = LavenderText, fontSize = 13.sp)
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.sourceLabel, color = Nox.TextPrimary, fontSize = 13.sp)
                    if (d.uploader.isNotBlank()) Text("  •  ${d.uploader}", color = LavenderText, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Rounded.MoreVert, "Действия", tint = Color(0xFFD5D9FF),
                modifier = Modifier.size(34.dp).clip(CircleShape).clickable {
                    sheets.actions(d.title, d.sourceLabel, listOf(
                        SheetAction(if (s.audioOnly) "Скачать видео" else "Скачать только звук", Icons.Rounded.MusicNote) {
                            vm.finder.setAudioOnly(!s.audioOnly)
                        },
                        SheetAction("Открыть страницу источника", Icons.Rounded.Link) {
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(d.pageUrl))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        SheetAction("Закрыть карточку", Icons.Rounded.Cancel) { vm.finder.cancel() },
                    ))
                }.padding(5.dp))
        }
    }
}

/** Подписи ступеней как на макете: 4K, QHD, FHD, HD, SD. */
private fun tierBadge(h: Int): String? = when {
    h >= 4320 -> "8K"
    h >= 2160 -> "4K"
    h >= 1440 -> "QHD"
    h >= 1080 -> "FHD"
    h >= 720 -> "HD"
    h >= 480 -> "SD"
    else -> null
}

private fun sizeLabel(bytes: Long, kind: SizeKind): String = when (kind) {
    SizeKind.EXACT -> Format.bytes(bytes)
    SizeKind.APPROX -> "≈ ${Format.bytes(bytes)}"
    SizeKind.UNKNOWN -> "размер неизвестен"
}

@Composable
private fun QualitySection(s: FinderState.Ready, vm: MainViewModel, sheets: SheetController, nav: Nav) {
    val cat = s.catalog
    val audios = s.audioVariants
    val count = if (s.audioOnly) audios.count { it.support.ok } else cat.variants.count { it.support.ok }
    Section(if (s.audioOnly) "Только звук" else "Качество видео", icon = if (s.audioOnly) Icons.Rounded.MusicNote else Icons.Rounded.Hd,
        trailing = "Доступно ${variantsWord(count)}") {
        ChoiceRow(listOf(false to "Видео со звуком", true to "Только звук"), s.audioOnly, { vm.finder.setAudioOnly(it) },
            height = 34.dp, textSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (!s.audioOnly) {
            for (v in cat.main) {
                val alternatives = cat.alternatives(v)
                val groupSelected = s.selectedKey != null && (s.selectedKey == v.key || alternatives.any { it.key == s.selectedKey })
                val shown = if (groupSelected && s.selectedKey != v.key) cat.find(s.selectedKey!!) ?: v else v
                QualityRow(shown, selected = s.selectedKey == shown.key, onClick = { vm.finder.select(shown.key) },
                    more = alternatives.size, onMore = { vm.finder.toggleDetails(v.groupKey) })
                if (s.expanded == v.groupKey) {
                    for (alt in (listOf(v) + alternatives).filter { it.key != shown.key }) {
                        QualityRow(alt, selected = s.selectedKey == alt.key, onClick = { vm.finder.select(alt.key) }, compact = true)
                    }
                }
            }
            s.selected?.let { v ->
                val note = listOfNotNull(techLine(v).ifBlank { null }, v.playbackNote.ifBlank { null }).joinToString(". ")
                if (note.isNotBlank()) InlineNote(note, danger = v.playback == Playback.UNLIKELY)
            }
            if (cat.main.isEmpty()) InlineNote("Источник не отдал ни одного варианта видео.", danger = true)
        } else {
            if (audios.isEmpty()) InlineNote("У этого видео нет отдельной звуковой дорожки — «только звук» недоступен.", danger = true)
            for (a in audios) AudioRow(a, s.audioKey == a.key) { vm.finder.selectAudio(a.key) }
            InlineNote("Звук сохраняется как есть — ${s.selectedAudio?.formatLabel ?: "в исходном формате"}, без перекодирования " +
                "и без переименования в MP3.")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val lang = cat.languages.firstOrNull { it.code == cat.language }
            PickerTile(Icons.AutoMirrored.Rounded.VolumeUp, "Аудиодорожка",
                when {
                    lang != null -> lang.label + if (lang.original) " (оригинал)" else ""
                    cat.languages.size == 1 -> cat.languages[0].label
                    else -> "Единственная"
                }, Modifier.weight(1f), enabled = cat.languages.size > 1) {
                sheets.show("Аудиодорожка") { close ->
                    for (l in cat.languages) {
                        Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = l.code == cat.language,
                            onClick = { vm.finder.setLanguage(l.code); close() }) {
                            Text(l.label + if (l.original) " · оригинал" else "", color = Nox.TextPrimary, fontSize = 15.sp,
                                modifier = Modifier.padding(14.dp))
                        }
                    }
                }
            }
            val subs = cat.details.subtitles
            PickerTile(Icons.Rounded.ClosedCaption, "Субтитры",
                when {
                    subs.isEmpty() -> "Нет у источника"
                    s.audioOnly -> "Только для видео"
                    s.subtitleKeys.isEmpty() -> "Не скачивать"
                    else -> s.subtitles.joinToString { it.label }.take(40)
                }, Modifier.weight(1f), enabled = subs.isNotEmpty() && !s.audioOnly) {
                subtitlesSheet(sheets, vm)
            }
        }
        Spacer(Modifier.height(12.dp))
        val size = if (s.audioOnly) s.selectedAudio?.let { sizeLabel(it.sizeBytes, it.sizeKind) } else s.selected?.let { sizeText(it) }
        val canDownload = if (s.audioOnly) s.selectedAudio != null else s.selected != null
        AmberButton(if (vm.finder.replanId != null) "Скачать заново" else "Скачать", {
            vm.downloadSelected { nav.back() }
        }, icon = Icons.Rounded.Download, trailing = size, enabled = canDownload, height = 56.dp)
        if (vm.finder.replanId != null) InlineNote("Прежний вариант недоступен: его скачанные части удалятся, когда вы подтвердите новый выбор.")
    }
}

private fun variantsWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return "$n " + when {
        m10 == 1 && m100 != 11 -> "вариант"
        m10 in 2..4 && m100 !in 12..14 -> "варианта"
        else -> "вариантов"
    }
}

@Composable
private fun QualityRow(v: Variant, selected: Boolean, onClick: () -> Unit, compact: Boolean = false, more: Int = 0,
                       onMore: () -> Unit = {}) {
    val p = nox()
    val enabled = v.support.ok
    Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp, start = if (compact) 18.dp else 0.dp).heightIn(min = 44.dp)
        .semantics { this.selected = selected; role = Role.RadioButton },
        onClick = if (enabled) onClick else null, selected = selected) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (selected) p.accent else Color(0xFFB8BEE6).copy(alpha = if (enabled) 1f else 0.3f),
                CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(p.accentLight))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.title, color = if (enabled) Nox.TextPrimary else Nox.TextMuted, fontSize = if (compact) 14.sp else 16.sp,
                        fontWeight = FontWeight.Medium)
                    tierBadge(v.tierHeight)?.let { Spacer(Modifier.width(10.dp)); Badge(it, amber = selected) }
                    if (v.hdr) { Spacer(Modifier.width(6.dp)); Badge("HDR", amber = selected) }
                    if (compact || v.needsMerge.not()) {
                        Spacer(Modifier.width(6.dp))
                        Text(CodecNames.container(v.outputContainer), color = LavenderText, fontSize = 11.sp)
                    }
                }
                if (!enabled) Text((v.support as? com.nox.offline.downloader.catalog.Support.No)?.reason.orEmpty(), color = Nox.TextMuted,
                    fontSize = 11.5.sp, maxLines = 2)
                else if (compact) Text(techLine(v), color = LavenderText, fontSize = 11.5.sp, maxLines = 1)
            }
            Text(sizeText(v), color = Color(0xFFE3E6FA), fontSize = 14.sp)
            if (more > 0) {
                Icon(Icons.Rounded.ExpandMore, "Другие кодеки этой ступени: $more", tint = LavenderText,
                    modifier = Modifier.padding(start = 4.dp).size(28.dp).clip(CircleShape).clickable(onClick = onMore).padding(3.dp))
            }
        }
    }
}

@Composable
private fun AudioRow(a: AudioVariant, selected: Boolean, onClick: () -> Unit) {
    val enabled = a.support.ok
    Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp).semantics { this.selected = selected; role = Role.RadioButton },
        onClick = if (enabled) onClick else null, selected = selected) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (selected) nox().accent else Color(0xFFB8BEE6), CircleShape),
                contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(nox().accentLight))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.title.ifBlank { a.codecLabel }, color = Nox.TextPrimary, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Badge(a.outputExt.uppercase(), amber = selected)
                }
                val lang = a.track.language.takeIf { it.isNotBlank() }?.let { CatalogBuilder.languageName(it) }
                if (lang != null || !enabled) Text(listOfNotNull(lang, (a.support as? com.nox.offline.downloader.catalog.Support.No)?.reason)
                    .joinToString(" · "), color = LavenderText, fontSize = 11.5.sp)
            }
            Text(sizeLabel(a.sizeBytes, a.sizeKind), color = Color(0xFFE3E6FA), fontSize = 14.sp)
        }
    }
}

@Composable
private fun Badge(text: String, amber: Boolean) {
    val p = nox()
    Box(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, if (amber) p.accent else Color(0xFF8F9BFF).copy(alpha = 0.45f),
        RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, color = if (amber) p.accentLight else Color(0xFFD5D9FF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PickerTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, modifier: Modifier,
                       enabled: Boolean, onClick: () -> Unit) {
    Tile(modifier.heightIn(min = 58.dp), onClick = if (enabled) onClick else null, radius = 14.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFFDCE0FF), modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Nox.TextPrimary, fontSize = 12.5.sp)
                Text(value, color = if (enabled) Color(0xFFE6E8FF) else LavenderText, fontSize = 13.5.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (enabled) Icon(Icons.Rounded.ExpandMore, null, tint = Color(0xFFD5D9FF))
        }
    }
}

private fun subtitlesSheet(sheets: SheetController, vm: MainViewModel) {
    sheets.show("Субтитры") { _ ->
        val st by vm.finder.state.collectAsState()
        val s = st as? FinderState.Ready ?: return@show
        Text("Только дорожки, которые есть у источника. Автоматические — распознанная речь на языке оригинала; перевода NOX не обещает.",
            color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        SubtitleGroup("Авторские", s.catalog.details.subtitles.filter { !it.auto }, s.subtitleKeys) { vm.finder.toggleSubtitle(it) }
        SubtitleGroup("Автоматические", s.catalog.details.subtitles.filter { it.auto }, s.subtitleKeys) { vm.finder.toggleSubtitle(it) }
        Text("Субтитры скачаются вместе с видео и будут работать без сети. Если они не скачаются, видео останется — субтитры можно повторить.",
            color = LavenderText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ColumnScope.SubtitleGroup(title: String, list: List<com.nox.offline.downloader.catalog.SourceSubtitle>, chosen: Set<String>,
                                      onToggle: (String) -> Unit) {
    if (list.isEmpty()) return
    Text(title, color = Nox.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
    for (sub in list) {
        val on = sub.key in chosen
        Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = on, onClick = { onToggle(sub.key) }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (on) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = if (on) nox().accent else LavenderText)
                Spacer(Modifier.width(12.dp))
                Text(sub.label, color = Nox.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(sub.ext.uppercase(), color = LavenderText, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** Честный список: что NOX умеет скачивать и что — нет. */
private fun supportedSheet(sheets: SheetController) {
    sheets.show("Какие ссылки подходят") { _ ->
        for ((t, d) in listOf(
            "YouTube" to "Видео, Shorts и плейлисты (список — отдельной страницей). Закрытые, возрастные и видео для спонсоров без входа в аккаунт недоступны.",
            "VK Видео" to "Публичные видео, которые отдаются цельным файлом.",
            "Другие сайты" to "Если источник отдаёт цельный файл. Потоковые HLS/DASH по частям NOX пока не скачивает — такой вариант будет показан с причиной.",
            "Прямые трансляции" to "Нельзя, пока трансляция не закончилась.",
        )) {
            Text(t, color = Nox.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(d, color = LavenderText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}
