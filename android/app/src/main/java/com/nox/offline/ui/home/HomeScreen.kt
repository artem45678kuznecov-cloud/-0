package com.nox.offline.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CollectionsBookmark
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.data.db.CollectionType
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Page
import com.nox.offline.ui.Tab
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.kit.ActionCircle
import com.nox.offline.ui.kit.BrandHeader
import com.nox.offline.ui.kit.Chip
import com.nox.offline.ui.kit.EmptyBlock
import com.nox.offline.ui.kit.KitField
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ScreenHeading
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.Tile
import com.nox.offline.ui.library.CollectionCard
import com.nox.offline.ui.library.LibraryViewModel
import com.nox.offline.ui.theme.Nox
import kotlinx.coroutines.launch

/**
 * Главная (макет 01): логотип и фон → «Коллекции» → поиск и фильтры →
 * рекомендуемая коллекция → категории → закреплённые → альбомы.
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

    LazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
        item {
            BrandHeader(
                onSearch = { scope.launch { listState.animateScrollToItem(0); runCatching { searchFocus.requestFocus() } } },
                onSettings = { nav.select(Tab.SETTINGS) },
                searchLabel = "Поиск в коллекциях",
            )
            Spacer(Modifier.height(22.dp))
            ScreenHeading("Коллекции", "Ваша медиатека, как вы любите", trailing = {
                Tile(Modifier.height(44.dp), onClick = { showCollectionFilter(sheets, lib, nav) }, radius = 16.dp, selected = filter.active) {
                    Row(Modifier.padding(horizontal = 16.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tune, null, tint = Color(0xFFE8EAFF), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Фильтры", color = Nox.TextPrimary, fontSize = 15.sp)
                    }
                }
            })
            Spacer(Modifier.height(14.dp))
            KitField(query, { lib.query.value = it }, "Поиск в коллекциях…",
                Modifier.padding(horizontal = 12.dp).focusRequester(searchFocus), leading = Icons.Rounded.Search,
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

        // ---------- рекомендуемая ----------
        item {
            Section("Рекомендуемая коллекция", trailing = "Смотреть все", onTrailing = { nav.open(Page.AllVideos) }) {
                val f = d.featured
                if (f == null) {
                    EmptyBlock(Icons.Rounded.CollectionsBookmark,
                        if (d.totalMedia == 0) "Медиатека пока пуста" else "Коллекций пока нет",
                        if (d.totalMedia == 0) "Скачайте или импортируйте видео, а потом соберите их в коллекции: сериалы, подборки, категории."
                        else "Соберите свои видео в коллекцию — здесь появится та, к которой вы вернётесь чаще всего.",
                        action = if (d.totalMedia == 0) "Открыть загрузчик" else "Создать коллекцию",
                        onAction = {
                            if (d.totalMedia == 0) { nav.open(Page.Downloader, Tab.DOWNLOADS) }
                            else createCollectionSheet(sheets, lib) { id -> nav.open(Page.Collection(id)) }
                        })
                } else FeaturedCard(f, d.featuredReason) { nav.open(Page.Collection(f.id)) }
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(d.categories, key = { it.id }) { c ->
                            CategoryTile(c, Modifier.width(74.dp), onOpen = { nav.open(Page.Collection(c.id)) },
                                onMenu = { actions.menu(c) })
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(d.pinned, key = { it.id }) { c ->
                            CollectionTile(c, Modifier.width(122.dp), onOpen = { nav.open(Page.Collection(c.id)) }, onMenu = { actions.menu(c) })
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

@Composable
private fun FeaturedCard(c: CollectionCard, reason: String, onOpen: () -> Unit) {
    Tile(Modifier.fillMaxWidth().height(96.dp), onClick = onOpen) {
        Row(Modifier.fillMaxHeight()) {
            Cover(c.cover, Modifier.fillMaxHeight().aspectRatio(1.95f), RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, end = 4.dp, bottom = 6.dp)) {
                Text(c.title, color = Nox.TextPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(com.nox.offline.ui.library.videosLabel(c.summary.local), color = LavenderText, fontSize = 13.sp)
                Text(c.summary.description.ifBlank { reason }, color = LavenderText, fontSize = 11.5.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                val tags = c.summary.entity.tagList
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (t in tags.take(3)) Chip(t, height = 20.dp, textSize = 10.5.sp)
                    }
                }
            }
            Box(Modifier.fillMaxHeight().padding(end = 8.dp), contentAlignment = Alignment.Center) {
                ActionCircle(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Открыть «${c.title}»", onOpen, size = 38.dp)
            }
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
                Icon(categoryIcon(c), null, tint = Color(0xFFE8EAFF), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(3.dp))
                Column {
                    Text(c.title, color = Nox.TextPrimary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        lineHeight = 12.sp)
                    Text(com.nox.offline.ui.library.videosLabel(c.summary.local), color = LavenderText, fontSize = 9.5.sp, maxLines = 1,
                        lineHeight = 11.sp)
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
            Row(Modifier.padding(start = 7.dp, top = 4.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, color = Nox.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.nox.offline.ui.library.videosLabel(c.summary.local), color = LavenderText, fontSize = 11.sp, maxLines = 1)
                }
                Icon(Icons.Rounded.MoreVert, "Меню «${c.title}»", tint = Color(0xFFD5D9FF),
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onMenu).padding(5.dp))
            }
        }
    }
}
