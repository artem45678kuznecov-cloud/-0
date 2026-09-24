package com.nox.offline.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.Format
import com.nox.offline.core.Storage
import com.nox.offline.data.db.DownloadEntity
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/** Честная подпись состояния задания. */
fun statusLabel(d: DownloadEntity): String = when (d.status) {
    DownloadStatus.QUEUED -> when {
        d.lastStopReason == "update" -> "Ждёт после обновления"
        d.retries > 0 || d.resolveRetries > 0 -> "Повтор после сбоя"
        else -> "В очереди"
    }
    DownloadStatus.RESOLVING -> "Разбор ссылки"
    DownloadStatus.DOWNLOADING -> if (d.isSplit) {
        if (!d.videoDone) "Скачивается видео" else "Скачивается звук"
    } else "Скачивается"
    DownloadStatus.PAUSED -> "Приостановлено"
    DownloadStatus.PROCESSING -> if (d.isSplit) "Склейка дорожек" else "Завершение"
    DownloadStatus.COMPLETED -> "Готово"
    DownloadStatus.ERROR -> "Ошибка"
}

/**
 * Карточка очереди по эталону: обложка слева, данные по центру, две
 * раздельные кнопки справа. Кнопки в своей колонке и не пересекаются с
 * текстом при любой длине названия.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadCard(
    d: DownloadEntity,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = nox()
    val active = d.status == DownloadStatus.DOWNLOADING
    GlassSurface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        style = if (active) GlassStyles.Card.copy(edge = 1.25f, glow = 0.2f) else GlassStyles.Card,
    ) {
        Row(
            Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(d.thumbnailUrl, Modifier.size(78.dp), shape = RoundedCornerShape(16.dp))
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(d.displayTitle.ifBlank { "Подготовка…" }, color = Nox.TextPrimary, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                VSpace(3)
                val bytes = when {
                    d.totalBytes > 0 -> "${Format.bytes(d.downloadedBytes)} / ${Format.bytes(d.totalBytes)}"
                    d.downloadedBytes > 0 -> Format.bytes(d.downloadedBytes)
                    else -> null
                }
                val statusColor = when (d.status) {
                    DownloadStatus.ERROR -> Nox.Danger
                    DownloadStatus.COMPLETED -> Nox.Ok
                    DownloadStatus.PAUSED, DownloadStatus.QUEUED -> Nox.TextSecondary
                    else -> p.accentLight
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(statusLabel(d), color = statusColor, fontSize = 13.sp, maxLines = 1)
                    if (bytes != null) Muted("  •  $bytes", size = 13.sp, color = Nox.TextSecondary, maxLines = 1)
                }
                VSpace(7)
                val kind = when {
                    d.status == DownloadStatus.PAUSED || d.status == DownloadStatus.QUEUED || d.status == DownloadStatus.ERROR -> ProgressKind.STILL
                    d.totalBytes <= 0 && d.status.isActive -> ProgressKind.INDETERMINATE
                    d.status == DownloadStatus.PROCESSING && d.totalBytes <= 0 -> ProgressKind.INDETERMINATE
                    else -> ProgressKind.DETERMINATE
                }
                val frac = if (d.totalBytes > 0) d.downloadedBytes.toFloat() / d.totalBytes else 0f
                NoxProgress(frac, kind = kind, dim = d.status == DownloadStatus.PAUSED)
                VSpace(6)
                val line = when {
                    d.status == DownloadStatus.ERROR -> d.error.ifBlank { "Не удалось скачать" }
                    d.status == DownloadStatus.DOWNLOADING && d.totalBytes > 0 ->
                        listOfNotNull("${d.progressPercent}%",
                            if (d.speedBps > 0) Format.speed(d.speedBps) else null,
                            if (d.etaSec >= 0 && d.speedBps > 0) "осталось ${Format.etaClock(d.etaSec)}" else null).joinToString("  •  ")
                    d.status == DownloadStatus.DOWNLOADING -> if (d.speedBps > 0) Format.speed(d.speedBps) else "Соединение…"
                    d.totalBytes > 0 -> "${d.progressPercent}%"
                    else -> "${d.quality}${if (d.quality == "MAX") "" else "p"}"
                }
                Muted(line, size = 12.sp, color = if (d.status == DownloadStatus.ERROR) Nox.Danger.copy(alpha = 0.85f) else Nox.TextMuted, maxLines = 2)
            }
            HSpace(10)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val (icon, label) = when (d.status) {
                    DownloadStatus.PAUSED -> Icons.Rounded.PlayArrow to "Продолжить"
                    DownloadStatus.ERROR -> Icons.Rounded.Refresh to "Повторить"
                    else -> Icons.Rounded.Pause to "Пауза"
                }
                GlassIconButton(icon, label, onToggle, size = 44.dp, iconSize = 22.dp,
                    enabled = d.status != DownloadStatus.PROCESSING && d.status != DownloadStatus.COMPLETED)
                GlassIconButton(Icons.Rounded.Close, "Удалить загрузку", onCancel, size = 44.dp, iconSize = 22.dp)
            }
        }
    }
}

/** Пустое состояние очереди — по эталону Главной. */
@Composable
fun EmptyDownloads(text: String, hint: String, modifier: Modifier = Modifier) {
    val p = nox()
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), style = GlassStyles.Card) {
        Column(Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.35f), p.accentDeep.copy(alpha = 0.10f), Color.Transparent)))
                    .border(1.dp, p.accentLight.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Download, null, tint = p.accentLight, modifier = Modifier.size(38.dp))
            }
            VSpace(14)
            Text(text, color = Nox.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            VSpace(4)
            Muted(hint, size = 15.sp, color = Nox.TextSecondary, align = TextAlign.Center)
        }
    }
}

/**
 * Хранилище: три разные величины подписаны отдельно, чтобы общая полоса
 * устройства не выглядела как объём NOX.
 */
@Composable
fun StorageCard(space: Storage.Space?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = nox()
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), style = GlassStyles.Card,
        onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(p.accent.copy(alpha = 0.12f))
                    .border(1.dp, p.edge.copy(alpha = 0.25f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SdStorage, null, tint = p.accentLight, modifier = Modifier.size(28.dp))
                }
                HSpace(14)
                Column(Modifier.weight(1f)) {
                    Text("Офлайн-хранилище", color = Nox.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Muted(if (space == null) "Считаем…" else "NOX занимает ${Format.bytes(space.usedByNox)}",
                        size = 14.sp, color = Nox.TextSecondary)
                }
                if (space != null && space.freeBytes >= 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Format.bytes(space.freeBytes), color = Nox.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Muted("свободно", size = 13.sp, color = Nox.TextSecondary)
                    }
                }
            }
            if (space != null && space.totalBytes > 0) {
                VSpace(12)
                val total = space.totalBytes.toFloat()
                val nox = (space.usedByNox / total).coerceIn(0f, 1f)
                val other = ((space.totalBytes - space.freeBytes - space.usedByNox).coerceAtLeast(0) / total).coerceIn(0f, 1f - nox)
                StorageBar(nox, other)
                VSpace(8)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(p.accentLight, "NOX ${Format.bytes(space.usedByNox)}")
                    Legend(p.accentDeep.copy(alpha = 0.7f), "Прочее ${Format.bytes((space.totalBytes - space.freeBytes - space.usedByNox).coerceAtLeast(0))}")
                    Legend(Nox.TextFaint, "из ${Format.bytes(space.totalBytes)}")
                }
            }
        }
    }
}

@Composable
private fun StorageBar(nox: Float, other: Float) {
    val p = nox()
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(p.bgDeep, cornerRadius = r)
        val wOther = size.width * (nox + other)
        if (wOther > 0) drawRoundRect(p.accentDeep.copy(alpha = 0.55f), size = androidx.compose.ui.geometry.Size(wOther, size.height), cornerRadius = r)
        val wNox = (size.width * nox).coerceAtLeast(if (nox > 0f) size.height else 0f)
        if (wNox > 0) drawRoundRect(Brush.horizontalGradient(listOf(p.accent, p.accentLight)), size = androidx.compose.ui.geometry.Size(wNox, size.height), cornerRadius = r)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        HSpace(5)
        Muted(text, size = 12.sp, color = Nox.TextSecondary, maxLines = 1)
    }
}
