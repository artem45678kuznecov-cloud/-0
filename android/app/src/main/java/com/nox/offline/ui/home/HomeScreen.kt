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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.data.db.CollectionType
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Page
import com.nox.offline.ui.Tab
import com.nox.offline.ui.TopOnReselect
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.kit.BrandHeader
import com.nox.offline.ui.kit.EmptyBlock
import com.nox.offline.ui.kit.KitField
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ProgressLine
import com.nox.offline.ui.kit.ScreenHeading
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.Tile
import com.nox.offline.ui.library.CollectionCard
import com.nox.offline.ui.library.LibraryViewModel
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox
import kotlinx.coroutines.launch

/**
 * Главная (макет 01): логотип и фон → «Коллекции» → поиск и фильтры →
 * все видео → категории → закреплённые → альбомы.
 * Всё — из базы пользователя; пустая медиатека остаётся пустой и
 * предлагает создать первую коллекцию.
 */
@Composable
fun HomeScreen(lib: LibraryViewModel, nav: Nav, padding: PaddingValues, onPlayMedia: (Long) -> Unit) {
    val data by lib.home.collectAsState()
    val query by lib.query.collectAsState()
    val filter by lib.filter.collectAsState()
    val sheets = LocalSheets.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val searchFocus = androidx.compose.runtime.remember { FocusRequester() }
    val actions = collectionActions(lib, nav, sheets)
    TopOnReselect(nav, Tab.HOME, listState)

    LazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
        item {
            BrandHeader(
                onSearch = { scope.launch { listState.animateScrollToItem(0); runCatching { searchFocus.requestFocus() } } },
                onSettings = { nav.select(Tab.SETTINGS) },
                searchLabel = "Поиск в коллекциях",
            )
            Spacer(Modifier.height(12.dp))
            ScreenHeading("Коллекции", "Ваша медиатека, как вы любите", trailing = {
                Tile(Modifier.height(32.dp), onClick = { showCollectionFilter(sheets, lib, nav) }, radius = 12.dp, selected = filter.active) {
                    Row(Modifier.padding(horizontal = 10.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tune, null, tint = Color(0xFFE8EAFF), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Фильтры", color = Nox.TextPrimary, fontSize = 12.5.sp)
                    }
                }
            })
            Spacer(Modifier.height(12.dp))
            KitField(query, { lib.query.value = it }, "Поиск в коллекциях…",
                Modifier.padding(horizontal = 12.dp).focusRequester(searchFocus), leading = Icons.Rounded.Search, minHeight = 38.dp,
                trailing = if (query.isNotEmpty()) ({
                    Icon(Icons.Rounded.Close, "Очистить поиск", tint = Color(0xFFBFC6FF),
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable { lib.query.value = "" }.padding(5.dp))
                }) else null)
            SectionGap()
        }
        val d = data
        if (d == null) return@LazyColumn

        if (query.isNotBlank() && d.matchedMedia.isNotEmpty()) {
            item {
                Section("Видео", trailing = "${d.matchedMedia.size}") {
                    for (m in d.matchedMedia.take(8)) {
                        Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), onClick = { onPlayMedia(m.id) }) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Cover(m.coverPath, Modifier.width(88.dp).aspectRatio(16f / 9f), RoundedCornerShape(8.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(m.title, color = Nox.TextPrimary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                SectionGap()
            }
        }

        // ---------- все видео ----------
        // Видео медиатеки видны на главной всегда, даже без единой коллекции.
        if (d.totalMedia > 0 && query.isBlank()) item(key = "all-videos") {
            Section("Все видео", trailing = "${d.totalMedia}", onTrailing = { nav.open(Page.AllVideos) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(d.recent, key = { it.media.id }) { v -> RecentTile(v) { onPlayMedia(v.media.id) } }
                }
            }
            SectionGap()
        }

        // ---------- категории ----------
        item {
            Section("Категории", trailing = "Все", onTrailing = { nav.open(Page.Collections(CollectionType.CATEGORY)) },
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                if (d.categories.isEmpty()) {
                    Text("Категорий нет. Создайте свою метку в «Все».", color = LavenderText, fontSize = 13.sp,
                        modifier = Modifier.padding(6.dp))
                } else {
                    // Пять плиток ровно по ширине, как на макете; больше пяти — прокрутка вбок.
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        val w = (maxWidth - 6.dp * 4) / 5
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(d.categories, key = { it.id }) { c ->
                                CategoryTile(c, Modifier.width(w), onOpen = { nav.open(Page.Collection(c.id)) },
                                    onMenu = { actions.menu(c) })
                            }
                        }
                    }
                }
            }
            SectionGap()
        }

        // ---------- закреплённые ----------
        item {
            Section("Закреплённые коллекции", trailing = "Все", onTrailing = { nav.open(Page.Collections("pinned")) },
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                if (d.pinned.isEmpty()) {
                    Text("Закрепите коллекцию через ⋮ — она будет здесь, сверху.", color = LavenderText, fontSize = 13.sp,
                        modifier = Modifier.padding(6.dp))
                } else {
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        val w = (maxWidth - 8.dp * 2) / 3
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(d.pinned, key = { it.id }) { c ->
                                CollectionTile(c, Modifier.width(w), onOpen = { nav.open(Page.Collection(c.id)) }, onMenu = { actions.menu(c) })
                            }
                        }
                    }
                }
            }
            SectionGap()
        }

        // ---------- альбомы ----------
        item {
            Section("Альбомы", trailing = "Все", onTrailing = { nav.open(Page.Collections(CollectionType.ALBUM)) },
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                if (d.albums.isEmpty()) {
                    EmptyBlock(Icons.Rounded.VideoLibrary, "Альбомов пока нет",
                        "Альбом — это ваша подборка: одно видео может быть в нескольких альбомах, файл не копируется.",
                        action = "Создать коллекцию", onAction = { createCollectionSheet(sheets, lib) { id -> nav.open(Page.Collection(id)) } })
                } else {
                    val shown = d.albums.take(6)
                    for (row in shown.chunked(3)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in row) CollectionTile(c, Modifier.weight(1f), onOpen = { nav.open(Page.Collection(c.id)) },
                                onMenu = { actions.menu(c) })
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** Плитка ряда «Все видео»: обложка, название и доля просмотра. */
@Composable
private fun RecentTile(v: com.nox.offline.ui.library.RecentVideo, onOpen: () -> Unit) {
    Tile(Modifier.width(104.dp), onClick = onOpen, radius = 10.dp) {
        Column(Modifier.padding(4.dp)) {
            Box {
                Cover(v.media.coverPath, Modifier.fillMaxWidth().aspectRatio(16f / 10f), RoundedCornerShape(8.dp))
                if (v.watched) Icon(Icons.Rounded.CheckCircle, "Просмотрено", tint = nox().accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp))
            }
            Text(v.media.title, color = Nox.TextPrimary, fontSize = 10.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp).height(26.dp))
            ProgressLine(v.fraction, Modifier.padding(start = 2.dp, end = 2.dp, top = 3.dp, bottom = 2.dp), lavender = !v.watched, height = 3.dp)
        }
    }
}

/** Иконка категории: встроенные метки из макета и «Избранное»/«Позже». */
private fun categoryIcon(c: CollectionCard): ImageVector = when (c.summary.systemKey) {
    "anime" -> Icons.Rounded.Pets
    "movies" -> Icons.Rounded.LocalMovies
    "series" -> Icons.Rounded.Tv
    CollectionType.FAVORITES -> Icons.Rounded.FavoriteBorder
    CollectionType.WATCH_LATER -> Icons.Rounded.Schedule
    else -> Icons.Rounded.Sell
}

@Composable
private fun CategoryTile(c: CollectionCard, modifier: Modifier, onOpen: () -> Unit, onMenu: () -> Unit) {
    Tile(modifier, onClick = onOpen, onLongClick = onMenu) {
        Column {
            Cover(c.cover, Modifier.fillMaxWidth().height(54.dp), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                showPlaceholderIcon = false)
            Row(Modifier.padding(horizontal = 4.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(categoryIcon(c), null, tint = Color(0xFFE8EAFF), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Column {
                    Text(c.title, color = Nox.TextPrimary, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        lineHeight = 11.sp, letterSpacing = (-0.1).sp)
                    Text(com.nox.offline.ui.library.videosLabel(c.summary.local), color = LavenderText, fontSize = 8.5.sp, maxLines = 1,
                        lineHeight = 10.sp)
                }
            }
        }
    }
}

@Composable
fun CollectionTile(c: CollectionCard, modifier: Modifier, onOpen: () -> Unit, onMenu: () -> Unit) {
    Tile(modifier, onClick = onOpen, onLongClick = onMenu) {
        Column {
            Cover(c.cover, Modifier.fillMaxWidth().aspectRatio(2.1f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            Row(Modifier.padding(start = 6.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, color = Nox.TextPrimary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
                    Text(com.nox.offline.ui.library.videosLabel(c.summary.local), color = LavenderText, fontSize = 9.5.sp, maxLines = 1,
                        lineHeight = 12.sp)
                }
                Icon(Icons.Rounded.MoreVert, "Меню «${c.title}»", tint = Color(0xFFD5D9FF),
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onMenu).padding(5.dp))
            }
        }
    }
}
