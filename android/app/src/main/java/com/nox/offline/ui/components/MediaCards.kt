package com.nox.offline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nox.offline.core.Format
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.data.db.PlaybackEntity
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox
import java.io.File

/** Видео медиатеки вместе с его позицией просмотра. */
@Immutable
data class LibraryItem(val media: MediaEntity, val playback: PlaybackEntity?) {
    val id: Long get() = media.id
    val qualityLabel: String get() = if (media.height > 0) "${media.height}p" else media.quality
    val durationLabel: String get() = Format.clock(media.durationSec)

    /** «12 мин • 480p • 67 МБ» — только то, что действительно известно. */
    val meta: String
        get() = listOfNotNull(
            Format.shortDuration(media.durationSec).ifBlank { null },
            qualityLabel.ifBlank { null }.takeIf { !media.imported || media.height > 0 },
            if (media.sizeBytes > 0) Format.bytes(media.sizeBytes) else null,
        ).joinToString("  •  ")
}

/** Обложка: локальный файл или адрес; без неё — ровная глубина с ▶. */
@Composable
fun Cover(
    model: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    showPlaceholderIcon: Boolean = true,
) {
    val p = nox()
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(p.bgTop, p.glowA.copy(alpha = 0.6f), p.bgDeep))),
        contentAlignment = Alignment.Center) {
        val m = when (model) {
            is String -> if (model.isBlank()) null else if (model.startsWith("http")) model else File(model).takeIf { it.exists() }
            else -> model
        }
        if (m != null) {
            AsyncImage(model = m, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (showPlaceholderIcon) {
            Icon(Icons.Rounded.PlayArrow, null, tint = p.accentLight.copy(alpha = 0.55f), modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Круглая стеклянная кнопка ▶ поверх обложки. */
@Composable
fun GlassPlayButton(size: Dp, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    GlassSurface(
        modifier = modifier.size(size),
        shape = CircleShape,
        style = GlassStyles.Selected.copy(accentFill = 0.42f, glow = 0.7f, tintAlpha = 0.45f),
        onClick = onClick,
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, "Смотреть", tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
private fun MenuDots(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MoreVert, "Меню", tint = nox().accentLight)
    }
}

/**
 * «Продолжить просмотр»: реальная обложка во всю карточку, затемнение
 * под текстом, фактический прогресс и стеклянная кнопка.
 */
@Composable
fun ContinueWatchingCard(item: LibraryItem, onContinue: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val p = nox()
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(196.dp),
        shape = RoundedCornerShape(26.dp),
        style = GlassStyles.Card.copy(glow = 0.25f),
        onClick = onContinue,
    ) {
        Cover(item.media.coverPath, Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.68f).fillMaxHeight(),
            shape = RoundedCornerShape(0.dp))
        // Затемнение слева, чтобы текст читался на любой обложке.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to p.bgDeep.copy(alpha = 0.96f), 0.42f to p.bgDeep.copy(alpha = 0.82f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))))
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.25f))
                    .border(1.dp, p.accentLight.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = p.accentLight, modifier = Modifier.size(18.dp))
                }
                HSpace(10)
                Text("Продолжить просмотр", color = Nox.TextSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                MenuDots(onMenu)
            }
            VSpace(6)
            Text(item.media.title, color = Nox.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 27.sp, modifier = Modifier.fillMaxWidth(0.62f))
            Box(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    NoxProgress(item.playback?.fraction ?: 0f, Modifier.fillMaxWidth(0.9f), height = 5.dp)
                    VSpace(8)
                    Muted(item.meta, size = 14.sp, color = Nox.TextSecondary, maxLines = 1)
                }
                HSpace(10)
                GlassPill("Продолжить", icon = Icons.Rounded.PlayArrow, onClick = onContinue, accent = true,
                    height = 46.dp, textSize = 16.sp, tint = Color.White)
            }
        }
    }
}

/** Плитка медиатеки на Главной. */
@Composable
fun MediaTile(item: LibraryItem, onOpen: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val p = nox()
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.28f)
                .glassFrame(onOpen)
        ) {
            Cover(item.media.coverPath, Modifier.fillMaxSize().padding(2.dp), shape = RoundedCornerShape(16.dp))
            Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(26.dp).clip(CircleShape)
                .background(p.accent.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, "Офлайн", tint = Color.White, modifier = Modifier.size(17.dp))
            }
            DurationBadge(item.durationLabel, Modifier.align(Alignment.BottomEnd).padding(7.dp))
        }
        VSpace(8)
        Text(item.media.title, color = Nox.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(item.meta, size = 12.sp, color = Nox.TextSecondary, maxLines = 1)
        VSpace(6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassPill("Офлайн", icon = Icons.Rounded.Check, height = 32.dp, textSize = 13.sp,
                tint = p.accentLight, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.weight(1f))
            MenuDots(onMenu, Modifier.size(34.dp))
        }
    }
}

@Composable
private fun Modifier.glassFrame(onClick: () -> Unit): Modifier =
    this.clip(RoundedCornerShape(18.dp))
        .border(1.dp, nox().edge.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
        .clickable(role = Role.Button, onClick = onClick)

/** Крупная первая карточка вкладки «Плеер». */
@Composable
fun PlayerHeroCard(item: LibraryItem, onPlay: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), style = GlassStyles.Card.copy(glow = 0.3f),
        onClick = onPlay, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Cover(item.media.coverPath, Modifier.fillMaxSize(), shape = RoundedCornerShape(18.dp))
                GlassPlayButton(68.dp, onPlay, Modifier.align(Alignment.Center))
                DurationBadge(item.durationLabel, Modifier.align(Alignment.BottomEnd).padding(10.dp))
                val f = item.playback?.fraction ?: 0f
                if (f > 0.01f && item.playback?.completed != true) {
                    NoxProgress(f, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), height = 3.dp)
                }
            }
            Row(Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.media.title, color = Nox.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    VSpace(4)
                    Muted(item.meta, size = 15.sp, color = Nox.TextSecondary, maxLines = 1)
                    if (item.media.uploader.isNotBlank()) Muted(item.media.uploader, size = 14.sp, maxLines = 1)
                }
                MenuDots(onMenu)
            }
        }
    }
}

/** Компактная горизонтальная карточка: плеер, «Все», результаты поиска. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerRowCard(
    item: LibraryItem,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        style = if (selected) GlassStyles.Selected else GlassStyles.Card,
        onClick = onPlay, onLongClick = onLongPress, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(148.dp).aspectRatio(16f / 10f)) {
                Cover(item.media.coverPath, Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp))
                GlassPlayButton(42.dp, null, Modifier.align(Alignment.Center))
                DurationBadge(item.durationLabel, Modifier.align(Alignment.BottomEnd).padding(6.dp))
                val f = item.playback?.fraction ?: 0f
                if (f > 0.01f && item.playback?.completed != true) {
                    NoxProgress(f, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), height = 3.dp)
                }
            }
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(item.media.title, color = Nox.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                VSpace(4)
                Muted(item.meta, size = 13.sp, color = Nox.TextSecondary, maxLines = 1)
                if (item.media.uploader.isNotBlank()) Muted(item.media.uploader, size = 13.sp, maxLines = 1)
            }
            MenuDots(onMenu)
        }
    }
}
