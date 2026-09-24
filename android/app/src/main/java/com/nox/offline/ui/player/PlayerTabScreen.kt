package com.nox.offline.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.components.GlassCard
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxHeader
import com.nox.offline.ui.components.PlayerHeroCard
import com.nox.offline.ui.components.PlayerRowCard
import com.nox.offline.ui.components.ScreenTitle
import com.nox.offline.ui.theme.Nox

/**
 * Вкладка «Плеер» по третьему эталону: крупная первая карточка —
 * то, что смотрели последним (или самое новое), ниже компактные карточки.
 * Само воспроизведение — в PlayerActivity на Media3.
 */
@Composable
fun PlayerTabScreen(vm: MainViewModel, actions: NoxActions, contentPadding: PaddingValues) {
    val all by vm.allItems.collectAsState()
    val status by vm.status.collectAsState()
    val cont by vm.continueItem.collectAsState()
    val lastWatched = all.filter { it.playback != null }.maxByOrNull { it.playback!!.updatedAt }
    val hero = cont ?: lastWatched ?: all.firstOrNull()
    val rest = all.filter { it.id != hero?.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") { NoxHeader(status, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), onStatusClick = actions.openDownloads) }
        item("title") { ScreenTitle("Плеер", "Только локальные файлы", Modifier.padding(horizontal = 20.dp)) }
        if (hero == null) {
            item("empty") {
                GlassCard(Modifier.padding(horizontal = 20.dp)) {
                    Muted("Скачанных видео пока нет. Всё, что вы скачаете или импортируете, воспроизводится здесь без интернета.",
                        size = 15.sp, color = Nox.TextSecondary)
                }
            }
        } else {
            item("hero") {
                PlayerHeroCard(hero, onPlay = { actions.openMedia(hero, false) }, onMenu = { actions.mediaMenu(hero) },
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        items(rest, key = { it.id }) { item ->
            PlayerRowCard(item, onPlay = { actions.openMedia(item, false) }, onMenu = { actions.mediaMenu(item) },
                modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}
