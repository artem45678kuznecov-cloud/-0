package com.nox.offline.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.components.ContinueWatchingCard
import com.nox.offline.ui.components.DownloadCard
import com.nox.offline.ui.components.EmptyDownloads
import com.nox.offline.ui.components.GlassCard
import com.nox.offline.ui.components.MediaTile
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxHeader
import com.nox.offline.ui.components.SearchRow
import com.nox.offline.ui.components.SectionHeader
import com.nox.offline.ui.theme.Nox

@Composable
fun HomeScreen(vm: MainViewModel, actions: NoxActions, contentPadding: PaddingValues) {
    val library by vm.library.collectAsState()
    val all by vm.allItems.collectAsState()
    val cont by vm.continueItem.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val status by vm.status.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val live = downloads.filter { it.status != DownloadStatus.COMPLETED }.sortedByDescending { it.updatedAt }.take(3)
    val searching = query.isNotBlank() || !filter.isDefault

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") { NoxHeader(status, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), onStatusClick = actions.openDownloads) }
        item("search") {
            SearchRow(query, { vm.query.value = it }, actions.openFilter, filterActive = !filter.isDefault,
                modifier = Modifier.padding(horizontal = 20.dp))
        }
        item("lib-title") {
            SectionHeader("Медиатека", Modifier.padding(horizontal = 20.dp),
                action = if (all.isEmpty()) null else "Все", onAction = actions.openLibrary)
        }
        if (!searching && cont != null) {
            item("continue") {
                ContinueWatchingCard(cont!!, onContinue = { actions.openMedia(cont!!, false) }, onMenu = { actions.mediaMenu(cont!!) },
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        when {
            all.isEmpty() -> item("lib-empty") {
                GlassCard(Modifier.padding(horizontal = 20.dp)) {
                    Muted("Пока пусто. Вставьте ссылку на вкладке «Загрузки» — готовое видео появится здесь и будет доступно без интернета.",
                        size = 15.sp, color = Nox.TextSecondary)
                }
            }
            library.isEmpty() -> item("lib-none") {
                GlassCard(Modifier.padding(horizontal = 20.dp)) {
                    Muted("Ничего не найдено. Измените запрос или фильтр.", size = 15.sp, color = Nox.TextSecondary)
                }
            }
            else -> item("lib-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(library.take(30), key = { it.id }) { item ->
                        MediaTile(item, onOpen = { actions.openMedia(item, false) }, onMenu = { actions.mediaMenu(item) },
                            modifier = Modifier.width(128.dp))
                    }
                }
            }
        }
        item("dl-title") {
            SectionHeader("Загрузки", Modifier.padding(horizontal = 20.dp), action = "Все", onAction = actions.openDownloads)
        }
        if (live.isEmpty()) {
            item("dl-empty") {
                EmptyDownloads("Нет активных загрузок", "Вставьте ссылку на вкладке «Загрузки»",
                    Modifier.padding(horizontal = 20.dp))
            }
        } else {
            items(live, key = { "dl-${it.id}" }) { d ->
                DownloadCard(d, onToggle = { vm.toggle(d) }, onCancel = { actions.confirmCancel(d) },
                    onLongPress = { actions.downloadMenu(d) }, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}
