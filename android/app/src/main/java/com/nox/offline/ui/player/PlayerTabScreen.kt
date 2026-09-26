@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nox.offline.ui.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nox.offline.NoxApp
import com.nox.offline.data.db.CollectionType
import com.nox.offline.library.Segments
import com.nox.offline.player.PlaybackHub
import com.nox.offline.player.PlayerActivity
import com.nox.offline.player.SleepTimer
import com.nox.offline.player.SubtitleStyle
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Page
import com.nox.offline.ui.Tab
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.LibraryItem
import com.nox.offline.ui.kit.ActionCircle
import com.nox.offline.ui.kit.AmberSwitch
import com.nox.offline.ui.kit.Chip
import com.nox.offline.ui.kit.ChoiceRow
import com.nox.offline.ui.kit.EmptyBlock
import com.nox.offline.ui.kit.InlineNote
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ProgressLine
import com.nox.offline.ui.kit.RoundButton
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.Tile
import com.nox.offline.ui.library.LibraryViewModel
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox
import kotlinx.coroutines.delay

enum class PlayerPane(val label: String) { EPISODES("Эпизоды"), ABOUT("О сериале"), SUBTITLES("Субтитры"), AUDIO("Аудио") }

/**
 * Плеер (макет 04): поверхность видео → сведения → эпизоды и главы →
 * звук и таймер → субтитры и озвучка → другие сохранённые видео.
 * Воспроизведением владеет [PlaybackHub]: здесь только показ и команды.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerTabScreen(vm: MainViewModel, lib: LibraryViewModel, nav: Nav, padding: PaddingValues) {
    val context = LocalContext.current
    val hub = remember { NoxApp.get(context).playback }
    val st by hub.state.collectAsState()
    val prefs by vm.playerPrefs.collectAsState()
    val all by vm.allItems.collectAsState()
    val sheets = LocalSheets.current
    val now = st.now
    var pane by remember { mutableStateOf(PlayerPane.EPISODES) }

    LazyColumn(contentPadding = padding) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", { nav.back() }, size = 44.dp)
                Text("Плеер", color = Nox.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 16.dp))
                RoundButton(Icons.Rounded.Monitor, "Экран: масштаб и полноэкранный режим", { screenSheet(sheets, hub, context) },
                    size = 44.dp, enabled = now != null)
                Spacer(Modifier.width(8.dp))
                Tile(Modifier.height(44.dp), onClick = if (now != null && st.hasVideo) ({
                    context.startActivity(PlayerActivity.fullscreen(context, pip = true))
                }) else null, radius = 22.dp) {
                    Row(Modifier.padding(horizontal = 14.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PictureInPictureAlt, null, tint = Color(0xFFE8EAFF), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("PiP", color = Nox.TextPrimary, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Rounded.MoreVert, "Ещё", { playerMenu(sheets, hub, lib, vm, nav, context) }, size = 44.dp,
                    enabled = now != null)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (now == null) {
            item {
                Section(null) {
                    EmptyBlock(Icons.Rounded.VideoLibrary, "Сейчас ничего не открыто",
                        if (all.isEmpty()) "Скачайте или импортируйте видео — и оно будет играть здесь без сети."
                        else "Выберите видео из медиатеки — ниже ваши сохранённые.",
                        action = if (all.isEmpty()) "Открыть загрузчик" else "Все видео",
                        onAction = { if (all.isEmpty()) nav.open(Page.Downloader, Tab.DOWNLOADS) else nav.open(Page.AllVideos, Tab.HOME) })
                }
                SectionGap()
            }
            item { OtherVideos(all, null, hub, nav) }
            return@LazyColumn
        }
        item {
            VideoSurface(hub, st, prefs.subtitleScale, prefs.subtitleBackground)
            SectionGap()
        }
        item {
            InfoCard(st, hub, lib)
            SectionGap()
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in PlayerPane.entries) {
                    Tile(Modifier.weight(1f).height(46.dp), selected = pane == p, onClick = { pane = p }, radius = 14.dp) {
                        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (p) {
                                PlayerPane.EPISODES -> Icons.AutoMirrored.Rounded.List
                                PlayerPane.ABOUT -> Icons.Rounded.BookmarkBorder
                                PlayerPane.SUBTITLES -> Icons.Rounded.ClosedCaption
                                PlayerPane.AUDIO -> Icons.Rounded.GraphicEq
                            }, null, tint = if (pane == p) nox().accentLight else Color(0xFFE0E3FF), modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (p == PlayerPane.ABOUT && now.context?.collectionId == 0L) "О видео" else p.label,
                                color = if (pane == p) nox().accentLight else Nox.TextPrimary, fontSize = 12.5.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (pane) {
                PlayerPane.EPISODES -> EpisodesPane(st, hub, lib, nav, sheets)
                PlayerPane.ABOUT -> AboutPane(st, hub, lib, sheets)
                PlayerPane.SUBTITLES -> SubtitlesPane(st, hub, lib, vm)
                PlayerPane.AUDIO -> AudioPane(st, hub)
            }
            SectionGap()
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ListenCard(st, hub, Modifier.weight(1f))
                SleepCard(st, hub, sheets, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val subsFlow = remember(now.media.id) { lib.subtitles(now.media.id) }
                val subs by subsFlow.collectAsState(initial = emptyList())
                NavCard(Icons.Rounded.ClosedCaption, "Субтитры",
                    subs.firstOrNull { it.id == now.media.subtitleId }?.label ?: if (subs.isEmpty()) "Нет" else "Выключены",
                    Modifier.weight(1f)) { pane = PlayerPane.SUBTITLES }
                NavCard(Icons.Rounded.GraphicEq, "Язык озвучки",
                    st.audioTracks.firstOrNull { it.selected }?.label ?: "Единственная дорожка", Modifier.weight(1f)) { pane = PlayerPane.AUDIO }
            }
            SectionGap()
        }
        item { OtherVideos(all, now.media.id, hub, nav) }
    }
}

// ---------------------------------------------------------------------
//  Поверхность видео
// ---------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(hub: PlaybackHub, st: PlaybackHub.State, subtitleScale: Float, subtitleBg: Int) {
    val context = LocalContext.current
    val p = nox()
    var controls by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(controls, st.isPlaying, dragging) {
        if (controls && st.isPlaying && dragging == null) { delay(3500); controls = false }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(18.dp))
        .border(1.2.dp, p.accent.copy(alpha = 0.85f), RoundedCornerShape(18.dp)).background(Color.Black)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls }) {
        if (st.hasVideo && !st.listen) {
            val view = remember {
                PlayerView(context).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                }
            }
            DisposableEffect(view) {
                hub.attach(view)
                onDispose { hub.detach(view) }
            }
            AndroidView({ view }, Modifier.fillMaxSize(), update = { v ->
                v.resizeMode = if (st.zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                v.keepScreenOn = st.isPlaying
                v.subtitleView?.let { SubtitleStyle.apply(it, subtitleScale, subtitleBg) }
                v.subtitleView?.setCues(hub.subtitleCues())
            })
        } else {
            Cover(st.now?.media?.coverPath.orEmpty(), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                Chip(if (st.now?.media?.isAudio == true) "Только звук" else "Режим «Слушать»", amber = true, icon = Icons.Rounded.MusicNote)
                if (st.subtitleText.isNotBlank()) Text(st.subtitleText, color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 10.dp).background(Color.Black.copy(alpha = 0.6f)).padding(6.dp))
            }
        }
        AnimatedVisibility(controls || !st.isPlaying || st.offer != null || st.ended, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                    Icon(Icons.Rounded.Replay10, "Назад на 10 секунд", tint = Color.White,
                        modifier = Modifier.size(52.dp).clip(CircleShape).clickable { hub.seekBy(-10_000); controls = true }.padding(6.dp))
                    Box(Modifier.size(70.dp).clip(CircleShape).background(Color(0xFF3A2A40).copy(alpha = 0.72f))
                        .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                        .clickable { hub.togglePlay(); controls = true }
                        .semantics { contentDescription = if (st.isPlaying) "Пауза" else "Играть" },
                        contentAlignment = Alignment.Center) {
                        Icon(if (st.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White,
                            modifier = Modifier.size(40.dp))
                    }
                    Icon(Icons.Rounded.Forward10, "Вперёд на 10 секунд", tint = Color.White,
                        modifier = Modifier.size(52.dp).clip(CircleShape).clickable { hub.seekBy(10_000); controls = true }.padding(6.dp))
                }
                if (st.hasVideo && !st.listen) {
                    Icon(Icons.Rounded.Fullscreen, "Во весь экран", tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 40.dp).size(42.dp).clip(CircleShape)
                            .clickable { context.startActivity(PlayerActivity.fullscreen(context)) }.padding(6.dp))
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val pos = dragging?.toLong() ?: st.positionMs
                    Text(Segments.clock(pos), color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = (dragging ?: st.positionMs.toFloat()).coerceIn(0f, st.durationMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = { dragging = it; controls = true },
                        onValueChangeFinished = { dragging?.let { hub.seekTo(it.toLong()) }; dragging = null },
                        valueRange = 0f..st.durationMs.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.22f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Позиция" },
                    )
                    Text(Segments.clock(st.durationMs), color = Color.White, fontSize = 13.sp)
                }
            }
        }
        st.offer?.let { o ->
            Tile(Modifier.align(Alignment.TopEnd).padding(10.dp).width(210.dp), onClick = { hub.acceptOffer() }, selected = true) {
                Column(Modifier.padding(10.dp)) {
                    Text("Далее через ${o.secondsLeft} с", color = nox().accentLight, fontSize = 12.sp)
                    Text(o.title, color = Nox.TextPrimary, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Сейчас", amber = true, icon = Icons.Rounded.SkipNext, onClick = { hub.acceptOffer() }, height = 26.dp)
                        Chip("Отмена", onClick = { hub.cancelOffer() }, height = 26.dp)
                    }
                }
            }
        }
        if (st.ended && st.offer == null) {
            Tile(Modifier.align(Alignment.TopCenter).padding(10.dp)) {
                Text(if (st.now?.context?.next == null) "Это последнее в списке. Следующее не скачивается само."
                else "Автопереход выключен в настройках плеера.",
                    color = Nox.TextPrimary, fontSize = 12.5.sp, modifier = Modifier.padding(10.dp))
            }
        }
        if (st.error.isNotBlank()) {
            Tile(Modifier.align(Alignment.Center).padding(16.dp)) {
                Text(st.error, color = Nox.Danger, fontSize = 13.5.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------
//  Сведения
// ---------------------------------------------------------------------

@Composable
private fun InfoCard(st: PlaybackHub.State, hub: PlaybackHub, lib: LibraryViewModel) {
    val now = st.now ?: return
    val m = now.media
    var fav by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(m.id) { fav = lib.isIn(CollectionType.FAVORITES, m.id) }
    val numbers = seriesLine(now, lib)
    Section(null, contentPadding = PaddingValues(10.dp)) {
        Row {
            Cover(m.coverPath, Modifier.width(118.dp).aspectRatio(16f / 10f), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (now.segment != null) m.title else now.context?.title?.takeIf { it.isNotBlank() && numbers != null } ?: m.title,
                    color = Nox.TextPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (numbers != null) Text(numbers, color = LavenderText, fontSize = 13.sp)
                val subtitle = if (now.segment != null) now.segment.title else if (numbers != null) m.title else m.uploader
                if (subtitle.isNotBlank()) Text(subtitle, color = LavenderText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, Color(0xFF8F9BFF).copy(alpha = 0.4f), CircleShape)
                .clickable { lib.toggleSystem(CollectionType.FAVORITES, m.id) { fav = it } }
                .semantics { contentDescription = if (fav) "Убрать из избранного" else "В избранное" },
                contentAlignment = Alignment.Center) {
                Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = nox().accent, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val q = LibraryItem(m, null).qualityLabel
            if (q.isNotBlank() && !m.isAudio) Chip(q, amber = true)
            if (m.codecs.isNotBlank()) Chip(m.codecs, icon = Icons.Rounded.Equalizer)
            st.audioTracks.firstOrNull { it.selected }?.takeIf { st.audioTracks.size > 1 }?.let { Chip(it.label) }
            Chip(if (m.isAudio) "Только звук" else "Скачано", amber = true, icon = Icons.Rounded.CheckCircle)
        }
        if (now.segment != null) {
            Spacer(Modifier.height(6.dp))
            Text("Часть файла: ${Segments.clock(now.segment.startMs)} – ${Segments.clock(now.segment.endMs)} · в файле сейчас " +
                Segments.clock(st.absoluteMs), color = LavenderText, fontSize = 12.sp)
        }
    }
}

/** «2 сезон • 5 серия» — из коллекции, где пользователь сам расставил номера. */
@Composable
private fun seriesLine(now: PlaybackHub.Now, lib: LibraryViewModel): String? {
    val ctx = now.context ?: return null
    if (ctx.collectionId <= 0) return now.segment?.let { "Серия ${ctx.index + 1} из ${ctx.items.size}" }
    val flow = remember(ctx.collectionId) { lib.detail(ctx.collectionId) }
    val d by flow.collectAsState(initial = null)
    val e = d?.items?.firstOrNull { it.item.mediaId == now.media.id && it.item.chapterId == (now.segment?.id ?: 0) } ?: return null
    if (d?.isSeries != true) return null
    return listOfNotNull(e.item.season.takeIf { it > 0 }?.let { "$it сезон" }, e.item.episode.takeIf { it > 0 }?.let { "$it серия" })
        .joinToString("  •  ").ifBlank { null }
}

// ---------------------------------------------------------------------
//  Эпизоды / главы
// ---------------------------------------------------------------------

@Composable
private fun EpisodesPane(st: PlaybackHub.State, hub: PlaybackHub, lib: LibraryViewModel, nav: Nav,
                         sheets: com.nox.offline.ui.components.SheetController) {
    val now = st.now ?: return
    val ctx = now.context
    val detailFlow = remember(ctx?.collectionId) { ctx?.collectionId?.takeIf { it > 0 }?.let { lib.detail(it) } }
    val detail by (detailFlow ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(initial = null)
    val title = when {
        detail != null && ctx != null && ctx.season >= 0 -> detail!!.seasonTitle(ctx.season)
        detail != null -> detail!!.collection.title
        now.segments.any { it.isEpisode } -> "Серии файла"
        now.segments.isNotEmpty() -> "Главы"
        else -> "Серии и главы"
    }
    Section(title, trailing = if (detail != null) "Все серии" else "Разметка",
        onTrailing = { if (detail != null) nav.open(Page.Collection(detail!!.collection.id), Tab.HOME) else chaptersSheet(sheets, hub, lib) }) {
        val entries: List<Triple<com.nox.offline.player.Playable, String, String>> = when {
            detail != null && ctx != null -> ctx.items.map { p ->
                val e = detail!!.items.firstOrNull { it.item.mediaId == p.mediaId && it.item.chapterId == p.chapterId }
                Triple(p, e?.let { x -> (x.item.episode.takeIf { it > 0 }?.let { "$it. " } ?: "") + x.title } ?: "",
                    e?.media?.coverPath.orEmpty())
            }
            now.segments.isNotEmpty() -> now.segments.map { s ->
                Triple(com.nox.offline.player.Playable(now.media.id, s.id), s.title, now.media.coverPath)
            }
            else -> emptyList()
        }
        if (entries.isEmpty()) {
            InlineNote("У этого видео нет серий и глав. Если это марафон из нескольких серий в одном файле — отметьте их: файл останется одним, " +
                "а у каждой серии будет своя позиция и отметка «просмотрено».")
            com.nox.offline.ui.kit.TileButton("Разметить серии и главы", { chaptersSheet(sheets, hub, lib) }, Modifier.fillMaxWidth())
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(entries) { i, (p, t, cover) ->
                    val current = p == now.playable || (now.segment == null && p.chapterId > 0 &&
                        now.segments.firstOrNull { it.id == p.chapterId }?.contains(st.absoluteMs) == true)
                    Column(Modifier.width(92.dp).clickable {
                        val seg = now.segments.firstOrNull { it.id == p.chapterId }
                        if (seg != null && p.mediaId == now.media.id) hub.jumpTo(seg) else hub.open(p.mediaId, p.chapterId, ctx)
                    }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(9.dp))
                            .border(if (current) 1.6.dp else 1.dp, if (current) nox().accent else Color(0xFF8F9BFF).copy(alpha = 0.35f),
                                RoundedCornerShape(9.dp))) {
                            Cover(cover, Modifier.fillMaxSize(), RoundedCornerShape(9.dp))
                            if (current) Icon(Icons.Rounded.Equalizer, "Сейчас играет", tint = nox().accent,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp))
                            val seg = now.segments.firstOrNull { it.id == p.chapterId }
                            if (seg != null) Text(Segments.clock(seg.lengthMs), color = Color.White, fontSize = 10.5.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color.Black.copy(alpha = 0.55f),
                                    RoundedCornerShape(5.dp)).padding(horizontal = 4.dp))
                        }
                        Text(t.ifBlank { "Серия ${i + 1}" }, color = Nox.TextPrimary, fontSize = 12.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), lineHeight = 14.sp)
                    }
                }
            }
            if (now.segment != null) {
                Spacer(Modifier.height(8.dp))
                com.nox.offline.ui.kit.TileButton("Смотреть весь файл", { hub.playWholeFile() }, Modifier.fillMaxWidth(), height = 40.dp)
            }
        }
    }
}

@Composable
private fun AboutPane(st: PlaybackHub.State, hub: PlaybackHub, lib: LibraryViewModel, sheets: com.nox.offline.ui.components.SheetController) {
    val now = st.now ?: return
    val m = now.media
    val bmFlow = remember(m.id) { lib.bookmarks(m.id) }
    val bookmarks by bmFlow.collectAsState(initial = emptyList())
    Section(if (now.context?.collectionId?.let { it > 0 } == true) "О сериале" else "О видео") {
        val lines = listOfNotNull(
            m.uploader.ifBlank { null }?.let { "Канал: $it" },
            if (m.durationSec > 0) "Длительность: ${Segments.clock(m.durationSec * 1000)}" else null,
            m.codecs.ifBlank { null }?.let { "Кодеки: $it" },
            if (m.sizeBytes > 0) "Размер: ${com.nox.offline.core.Format.bytes(m.sizeBytes)}" else null,
            m.pageUrl.ifBlank { null }?.let { "Источник: ${com.nox.offline.core.SafeUrl.host(it)}" },
        )
        for (l in lines) Text(l, color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Закладки", color = Nox.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Добавить на ${Segments.clock(st.absoluteMs)}", amber = true, icon = Icons.Rounded.BookmarkBorder,
                onClick = { bookmarkSheet(sheets, lib, m.id, st.absoluteMs, null) })
        }
        if (bookmarks.isEmpty()) InlineNote("Закладка — момент с названием и заметкой; переход к нему в одно касание.")
        for (b in bookmarks) {
            Tile(Modifier.fillMaxWidth().padding(top = 6.dp), onClick = {
                if (now.segment != null && !now.segment.contains(b.positionMs)) hub.playWholeFile()
                hub.seekTo(if (now.segment != null && now.segment.contains(b.positionMs)) now.segment.toRelative(b.positionMs) else b.positionMs)
            }, onLongClick = { bookmarkSheet(sheets, lib, m.id, b.positionMs, b) }) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Segments.clock(b.positionMs), color = nox().accentLight, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title.ifBlank { "Закладка" }, color = Nox.TextPrimary, fontSize = 14.sp)
                        if (b.note.isNotBlank()) Text(b.note, color = LavenderText, fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPane(st: PlaybackHub.State, hub: PlaybackHub) {
    Section("Аудио") {
        if (st.audioTracks.size <= 1) InlineNote("В файле одна звуковая дорожка.")
        for (t in st.audioTracks) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = t.selected, onClick = { hub.selectAudioTrack(t) }) {
                Text(t.label, color = Nox.TextPrimary, fontSize = 14.5.sp, modifier = Modifier.padding(12.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Скорость", color = Nox.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        ChoiceRow(listOf(0.75f to "0.75×", 1f to "1×", 1.25f to "1.25×", 1.5f to "1.5×", 2f to "2×"), st.speed, { hub.setSpeed(it) },
            textSize = 12.5.sp)
    }
}

// ---------------------------------------------------------------------
//  «Только звук», таймер, навигационные карточки
// ---------------------------------------------------------------------

@Composable
private fun ListenCard(st: PlaybackHub.State, hub: PlaybackHub, modifier: Modifier) {
    val audioFile = st.now?.media?.isAudio == true
    Tile(modifier.heightIn(min = 118.dp), radius = 14.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Color(0xFFDCE0FF), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Только звук", color = Nox.TextPrimary, fontSize = 15.sp)
                    Text(if (audioFile) "Это звуковой файл" else "Продолжать воспроизведение в фоне", color = LavenderText, fontSize = 11.5.sp,
                        lineHeight = 14.sp)
                }
            }
            Spacer(Modifier.weight(1f, fill = false))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                AmberSwitch(st.listen, { hub.setListen(it) }, label = "Только звук")
            }
        }
    }
}

@Composable
private fun SleepCard(st: PlaybackHub.State, hub: PlaybackHub, sheets: com.nox.offline.ui.components.SheetController, modifier: Modifier) {
    val mode = st.sleep
    Tile(modifier.heightIn(min = 118.dp), radius = 14.dp, onClick = { sleepSheet(sheets, hub) }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Bedtime, null, tint = Color(0xFFDCE0FF), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Таймер сна", color = Nox.TextPrimary, fontSize = 15.sp)
                    Text(when {
                        mode is SleepTimer.Mode.At -> "Осталось ${Segments.clock(st.sleepRemainingMs ?: 0)}"
                        mode != null -> mode.label
                        else -> "Остановить воспроизведение через…"
                    }, color = LavenderText, fontSize = 11.5.sp, lineHeight = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val sel: String? = when (mode) {
                is SleepTimer.Mode.At -> if (mode.minutes == 30) "30" else if (mode.minutes == 60) "60" else null
                SleepTimer.Mode.AfterSegment -> "seg"
                else -> null
            }
            ChoiceRow(listOf("30" to "30 мин", "60" to "1 час", "seg" to "После серии"), sel, { v ->
                if (v == sel) hub.setSleep(null) else when (v) {
                    "30" -> hub.sleepMinutes(30)
                    "60" -> hub.sleepMinutes(60)
                    else -> hub.setSleep(SleepTimer.Mode.AfterSegment)
                }
            }, height = 30.dp, textSize = 10.5.sp)
        }
    }
}

@Composable
private fun NavCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Tile(modifier.height(58.dp), onClick = onClick, radius = 14.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFFDCE0FF), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Nox.TextPrimary, fontSize = 14.sp, maxLines = 1)
                Text(value, color = LavenderText, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFD5D9FF))
        }
    }
}

// ---------------------------------------------------------------------
//  Другие сохранённые
// ---------------------------------------------------------------------

@Composable
private fun OtherVideos(all: List<LibraryItem>, currentId: Long?, hub: PlaybackHub, nav: Nav) {
    // Только готовые файлы медиатеки: незавершённые загрузки (.part) сюда не попадают.
    val list = all.filter { it.id != currentId }.sortedByDescending { it.playback?.updatedAt ?: it.media.createdAt }.take(6)
    Section("Другие сохранённые видео", trailing = "Все", onTrailing = { nav.open(Page.AllVideos, Tab.HOME) }) {
        if (list.isEmpty()) InlineNote("Других сохранённых видео пока нет.")
        for (it in list) {
            Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), onClick = { hub.open(it.id) }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(it.media.coverPath, Modifier.width(96.dp).aspectRatio(16f / 10f), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.media.title, color = Nox.TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val pb = it.playback
                        val frac = when {
                            pb == null || pb.durationMs <= 0 -> 0f
                            pb.completed -> 1f
                            else -> pb.positionMs.toFloat() / pb.durationMs
                        }
                        Text(when {
                            pb?.completed == true -> "Просмотрено"
                            frac > 0f -> "Остановились на ${Segments.clock(pb!!.positionMs)} · ${it.meta}"
                            else -> it.meta
                        }, color = LavenderText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ProgressLine(frac, Modifier.padding(top = 6.dp), lavender = frac < 1f, height = 4.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    ActionCircle(Icons.Rounded.PlayArrow, "Смотреть «${it.media.title}»", { hub.open(it.id) })
                }
            }
        }
    }
}
