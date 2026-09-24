package com.nox.offline.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.components.Badge
import com.nox.offline.ui.components.GhostButton
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxCard
import com.nox.offline.ui.components.NoxProgress
import com.nox.offline.ui.components.SectionTitle
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.theme.Nox

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val downloads by vm.downloads.collectAsState()
    val live = downloads.filter { it.status != DownloadStatus.COMPLETED }.reversed()
    val done = downloads.filter { it.status == DownloadStatus.COMPLETED }.reversed()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text("Загрузки", color = Nox.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Muted("до трёх одновременно, остальные ждут", size = 13, color = Nox.TextSecondary)
            }
        }
        if (live.isEmpty()) {
            item { NoxCard { Muted("Очередь пуста.", size = 13, color = Nox.TextSecondary) } }
        }
        items(live, key = { it.id }) { d -> DownloadCard(d, vm) }
        if (done.isNotEmpty()) {
            item { SectionTitle("Готово", trailing = "${done.size}") }
            items(done, key = { "done-${it.id}" }) { d -> DownloadCard(d, vm) }
        }
    }
}

private fun statusLabel(d: DownloadEntity): String = when (d.status) {
    DownloadStatus.QUEUED -> if (d.retries > 0 || d.resolveRetries > 0) "Повтор…" else "В очереди"
    DownloadStatus.RESOLVING -> "Разбор ссылки"
    DownloadStatus.DOWNLOADING -> "Скачивается"
    DownloadStatus.PAUSED -> "Пауза"
    DownloadStatus.PROCESSING -> "Завершение"
    DownloadStatus.COMPLETED -> "Готово"
    DownloadStatus.ERROR -> "Ошибка"
}

private fun statusColor(d: DownloadEntity) = when (d.status) {
    DownloadStatus.DOWNLOADING -> Nox.AccentLight
    DownloadStatus.COMPLETED -> Nox.Ok
    DownloadStatus.ERROR -> Nox.Danger
    DownloadStatus.PAUSED -> Nox.TextSecondary
    else -> Nox.TextSecondary
}

@Composable
fun DownloadCard(d: DownloadEntity, vm: MainViewModel) {
    NoxCard(active = d.status == DownloadStatus.DOWNLOADING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.title.ifBlank { "Подготовка…" }, color = Nox.TextPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            HSpace(8)
            Badge(d.quality)
        }
        VSpace(8)
        val fraction = if (d.totalBytes > 0) d.downloadedBytes.toFloat() / d.totalBytes else 0f
        NoxProgress(fraction, indeterminate = d.totalBytes <= 0 && d.status.isActive)
        VSpace(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statusLabel(d), color = statusColor(d), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            HSpace(8)
            val bytes = if (d.totalBytes > 0) "${Format.bytes(d.downloadedBytes)} / ${Format.bytes(d.totalBytes)}"
            else Format.bytes(d.downloadedBytes)
            Muted(bytes, size = 12, color = Nox.TextSecondary)
            if (d.status == DownloadStatus.DOWNLOADING && d.speedBps > 0) {
                HSpace(8)
                Muted("${Format.speed(d.speedBps)} • ${Format.eta(d.etaSec)}", size = 12)
            }
        }
        if (d.status == DownloadStatus.ERROR && d.error.isNotBlank()) {
            VSpace(4)
            Muted(d.error, size = 12, color = Nox.Danger, maxLines = 3)
        }
        if (d.lastStopReason.isNotBlank() && d.status == DownloadStatus.QUEUED) {
            VSpace(4)
            Muted("система остановила носитель: ${d.lastStopReason}", size = 11)
        }
        VSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (d.status) {
                DownloadStatus.PAUSED, DownloadStatus.ERROR -> {
                    GhostButton("Продолжить", onClick = { vm.resume(d.id) })
                    GhostButton("Удалить", onClick = { vm.cancel(d.id) }, danger = true)
                }
                DownloadStatus.COMPLETED -> GhostButton("Убрать из списка", onClick = { vm.remove(d.id) })
                else -> {
                    GhostButton("Пауза", onClick = { vm.pause(d.id) })
                    GhostButton("Отменить", onClick = { vm.cancel(d.id) }, danger = true)
                }
            }
        }
    }
}
