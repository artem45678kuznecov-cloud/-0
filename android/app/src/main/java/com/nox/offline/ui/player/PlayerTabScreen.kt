package com.nox.offline.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.Format
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.components.GhostButton
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxCard
import com.nox.offline.ui.components.NoxProgress
import com.nox.offline.ui.components.SectionTitle
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.home.Cover
import com.nox.offline.ui.theme.Nox

/** Вкладка «Плеер»: продолжить просмотр и вся медиатека. Само видео — в PlayerActivity. */
@Composable
fun PlayerTabScreen(vm: MainViewModel, onOpen: (MediaEntity) -> Unit) {
    val media by vm.media.collectAsState()
    val playback by vm.playback.collectAsState()
    val positions = playback.associateBy { it.mediaId }
    val recent = playback.mapNotNull { p -> media.firstOrNull { it.id == p.mediaId } }.take(5)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text("Плеер", color = Nox.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Muted("работает без интернета", size = 13, color = Nox.TextSecondary)
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Продолжить просмотр") }
            items(recent, key = { "recent-${it.id}" }) { m ->
                val p = positions[m.id]
                NoxCard(onClick = { onOpen(m) }, active = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(m.coverPath, Modifier.size(width = 112.dp, height = 68.dp))
                        HSpace(12)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.title, color = Nox.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            VSpace(6)
                            if (p != null && p.durationMs > 0) {
                                NoxProgress(p.positionMs.toFloat() / p.durationMs)
                                VSpace(4)
                                Muted("${Format.duration(p.positionMs / 1000)} из ${Format.duration(p.durationMs / 1000)}", size = 12)
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Все видео", trailing = if (media.isEmpty()) "" else "${media.size}") }
        if (media.isEmpty()) {
            item { NoxCard { Muted("Скачанных видео пока нет.", size = 13, color = Nox.TextSecondary) } }
        }
        items(media, key = { it.id }) { m ->
            NoxCard(onClick = { onOpen(m) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(m.coverPath, Modifier.size(width = 96.dp, height = 58.dp))
                    HSpace(12)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.title, color = Nox.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        VSpace(4)
                        Muted("${Format.bytes(m.sizeBytes)}${if (m.durationSec > 0) " • " + Format.duration(m.durationSec) else ""}", size = 12)
                    }
                    HSpace(8)
                    GhostButton("Удалить", onClick = { vm.deleteMedia(m) }, danger = true)
                }
            }
        }
    }
}
