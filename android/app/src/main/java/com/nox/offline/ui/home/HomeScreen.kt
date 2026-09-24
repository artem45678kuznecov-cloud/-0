package com.nox.offline.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nox.offline.core.Format
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.downloader.Quality
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.components.AccentButton
import com.nox.offline.ui.components.Badge
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxCard
import com.nox.offline.ui.components.NoxProgress
import com.nox.offline.ui.components.QualityChip
import com.nox.offline.ui.components.SectionTitle
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.theme.Nox
import java.io.File

@Composable
fun HomeScreen(vm: MainViewModel, onOpenMedia: (MediaEntity) -> Unit) {
    val media by vm.media.collectAsState()
    val space by vm.space.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val activeCount = downloads.count { it.status.isPending }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header() }
        item { DownloaderCard(vm, activeCount) }
        item { StorageCard(space, media.size) }
        item { SectionTitle("Медиатека", trailing = if (media.isEmpty()) "" else "${media.size}") }
        if (media.isEmpty()) {
            item {
                NoxCard {
                    Muted("Пока пусто. Вставьте ссылку на VK Video выше, выберите качество и нажмите «Скачать».",
                        size = 13, color = Nox.TextSecondary)
                }
            }
        }
        items(media, key = { it.id }) { m -> MediaCard(m) { onOpenMedia(m) } }
    }
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("NOX", color = Nox.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Muted("офлайн-медиатека", size = 13, color = Nox.TextSecondary)
    }
}

@Composable
fun DownloaderCard(vm: MainViewModel, activeCount: Int) {
    val url by vm.urlInput.collectAsState()
    val quality by vm.quality.collectAsState()
    val message by vm.message.collectAsState()
    val clipboard = LocalClipboardManager.current

    NoxCard(strong = true) {
        SectionTitle("Загрузить", trailing = if (activeCount > 0) "в работе: $activeCount" else null)
        VSpace(12)
        OutlinedTextField(
            value = url,
            onValueChange = vm::setUrl,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ссылка на VK Video", color = Nox.TextFaint) },
            singleLine = true,
            shape = RoundedCornerShape(Nox.FieldRadius),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.startDownload() }),
            trailingIcon = {
                IconButton(onClick = {
                    val text = clipboard.getText()?.text ?: ""
                    if (text.isNotBlank()) vm.setUrl(text.trim())
                }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "Вставить", tint = Nox.AccentLight)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Nox.GlassDeep, unfocusedContainerColor = Nox.GlassDeep,
                focusedBorderColor = Nox.BorderActive.copy(alpha = 0.8f),
                unfocusedBorderColor = Nox.Border.copy(alpha = 0.25f),
                focusedTextColor = Nox.TextPrimary, unfocusedTextColor = Nox.TextPrimary,
                cursorColor = Nox.AccentLight,
            ),
        )
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (q in Quality.entries) {
                QualityChip(q.label, selected = q == quality, onClick = { vm.setQuality(q) },
                    modifier = Modifier.weight(1f))
            }
        }
        VSpace(12)
        AccentButton("Скачать", onClick = vm::startDownload, modifier = Modifier.fillMaxWidth(),
            enabled = url.isNotBlank())
        if (message.isNotBlank()) {
            VSpace(8)
            Muted(message, size = 12, color = Nox.AccentLight)
        }
        VSpace(6)
        Muted("Файл со звуком целиком, без склейки. Загрузка идёт в фоне: можно свернуть NOX или выключить экран.",
            size = 11)
    }
}

@Composable
fun StorageCard(space: com.nox.offline.core.Storage.Space?, count: Int) {
    NoxCard {
        SectionTitle("Офлайн-хранилище")
        VSpace(8)
        if (space == null) {
            Muted("Считаем…")
        } else {
            val used = space.usedByNox
            val free = space.freeBytes
            val total = space.totalBytes
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.bytes(used), color = Nox.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                HSpace(8)
                Muted("занято NOX • $count видео", size = 12, color = Nox.TextSecondary)
            }
            VSpace(8)
            val fraction = if (total > 0) (total - free).toFloat() / total else 0f
            NoxProgress(fraction)
            VSpace(6)
            Muted("Свободно ${Format.bytes(free)} из ${Format.bytes(total)}", size = 12)
        }
    }
}

@Composable
fun MediaCard(m: MediaEntity, onClick: () -> Unit) {
    NoxCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(m.coverPath, Modifier.size(width = 112.dp, height = 68.dp))
            HSpace(12)
            Column(modifier = Modifier.weight(1f)) {
                Text(m.title, color = Nox.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                VSpace(6)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("Офлайн", Nox.Ok)
                    Badge(if (m.height > 0) "${m.height}p" else m.quality)
                    Muted(Format.bytes(m.sizeBytes), size = 12)
                    if (m.durationSec > 0) Muted(Format.duration(m.durationSec), size = 12)
                }
            }
        }
    }
}

@Composable
fun Cover(path: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier = modifier.clip(shape).background(Nox.GlassDeep), contentAlignment = Alignment.Center) {
        if (path.isNotBlank() && File(path).exists()) {
            AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(68.dp))
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Nox.AccentLight.copy(alpha = 0.7f),
                modifier = Modifier.size(30.dp))
        }
    }
}

@Suppress("unused")
private val transparent = Color.Transparent
