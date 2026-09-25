package com.nox.offline.ui.downloads

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.Format
import com.nox.offline.downloader.catalog.CodecNames
import com.nox.offline.downloader.catalog.FormatCatalog
import com.nox.offline.downloader.catalog.SizeKind
import com.nox.offline.downloader.catalog.Support
import com.nox.offline.downloader.catalog.Variant
import com.nox.offline.downloader.catalog.VideoDetails
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.GlassPill
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/** «473 МБ», «≈ 480 МБ» или «Размер неизвестен» — без выдуманной точности. */
fun sizeText(v: Variant): String = when (v.sizeKind) {
    SizeKind.EXACT -> Format.bytes(v.sizeBytes)
    SizeKind.APPROX -> "≈ ${Format.bytes(v.sizeBytes)}"
    SizeKind.UNKNOWN -> "Размер неизвестен"
}

/** Техническая строка варианта: «2560×1440 · VP9 + Opus · WebM». */
fun techLine(v: Variant): String = listOfNotNull(
    v.resolutionLabel.ifBlank { null },
    v.codecLabel.ifBlank { null },
    CodecNames.container(v.outputContainer),
).joinToString(" · ")

/** Карточка найденного видео: обложка, название, автор, длительность, источник. */
@Composable
fun VideoDetailsCard(details: VideoDetails, modifier: Modifier = Modifier) {
    val p = nox()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Cover(details.thumbnail.ifBlank { null }, Modifier.size(width = 120.dp, height = 68.dp), shape = RoundedCornerShape(14.dp))
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(details.title, color = Nox.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
            VSpace(3)
            val meta = listOfNotNull(
                details.uploader.ifBlank { null },
                if (details.durationSec > 0) Format.clock(details.durationSec) else null,
            ).joinToString(" · ")
            if (meta.isNotBlank()) Muted(meta, size = 13.sp, color = Nox.TextSecondary, maxLines = 1)
            Muted(details.sourceLabel, size = 12.sp, color = p.accentLight, maxLines = 1)
        }
    }
}

/** Выбор языка звука, если у видео несколько дорожек на разных языках. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageChips(catalog: FormatCatalog, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (catalog.languages.size <= 1) return
    Column(modifier) {
        Muted("Язык звука", size = 13.sp, color = Nox.TextSecondary)
        VSpace(6)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (l in catalog.languages) {
                GlassPill(if (l.original) "${l.label} · оригинал" else l.label, accent = l.code == catalog.language,
                    height = 38.dp, textSize = 14.sp, onClick = { onSelect(l.code) })
            }
        }
    }
}

/**
 * Настоящие варианты качества этого видео. Основной список — по одному на
 * ступень / частоту кадров / HDR; остальные кодеки и контейнеры той же
 * ступени — в «Подробнее». Недоступные показаны с причиной и не выбираются.
 */
@Composable
fun VariantList(
    catalog: FormatCatalog,
    selectedKey: String?,
    expanded: String?,
    onSelect: (String) -> Unit,
    onToggleDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (v in catalog.main) {
            val alternatives = catalog.alternatives(v)
            val groupSelected = selectedKey != null && (selectedKey == v.key || alternatives.any { it.key == selectedKey })
            val shown = if (groupSelected && selectedKey != v.key) catalog.find(selectedKey!!) ?: v else v
            VariantRow(
                v = shown,
                selected = selectedKey == shown.key,
                onClick = { onSelect(shown.key) },
                detailsCount = alternatives.size,
                detailsOpen = expanded == v.groupKey,
                onToggleDetails = { onToggleDetails(v.groupKey) },
            )
            if (expanded == v.groupKey) {
                for (alt in (listOf(v) + alternatives).filter { it.key != shown.key }) {
                    VariantRow(alt, selectedKey == alt.key, { onSelect(alt.key) }, compact = true,
                        modifier = Modifier.padding(start = 18.dp))
                }
            }
        }
    }
}

@Composable
fun VariantRow(
    v: Variant,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    detailsCount: Int = 0,
    detailsOpen: Boolean = false,
    onToggleDetails: () -> Unit = {},
) {
    val p = nox()
    val enabled = v.support.ok
    GlassSurface(
        modifier = modifier.fillMaxWidth().heightIn(min = if (compact) 56.dp else 68.dp)
            .semantics { this.selected = selected; role = Role.RadioButton },
        shape = RoundedCornerShape(20.dp),
        style = if (selected) GlassStyles.Selected else GlassStyles.Chip,
        onClick = if (enabled) onClick else null,
        enabled = enabled,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                tint = when {
                    !enabled -> Nox.TextMuted.copy(alpha = 0.5f)
                    selected -> p.accentLight
                    else -> Nox.TextSecondary
                },
                modifier = Modifier.size(22.dp),
            )
            HSpace(12)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.title, color = if (enabled) Nox.TextPrimary else Nox.TextMuted,
                        fontSize = if (compact) 16.sp else 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (v.fps > 0) {
                        HSpace(8)
                        Muted("${v.fps} к/с", size = 12.sp, color = Nox.TextSecondary, maxLines = 1)
                    }
                    if (v.hdr) {
                        HSpace(8)
                        Badge(v.dynamicRange.uppercase())
                    }
                    if (v.silent) {
                        HSpace(8)
                        Badge("без звука")
                    }
                }
                Muted(techLine(v), size = 12.sp, color = Nox.TextSecondary, maxLines = 2)
                if (enabled) {
                    Muted(sizeText(v) + if (v.needsMerge) " · видео и звук объединятся" else "",
                        size = 12.sp, color = Nox.TextSecondary, maxLines = 2)
                    if (v.playbackNote.isNotBlank()) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = p.accentLight, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                            HSpace(4)
                            Muted(v.playbackNote, size = 12.sp, color = p.accentLight, maxLines = 3)
                        }
                    }
                } else {
                    Muted((v.support as Support.No).reason, size = 12.sp, color = Nox.TextMuted, maxLines = 3)
                }
            }
            if (detailsCount > 0) {
                HSpace(6)
                // «Подробнее»: другие кодеки и контейнеры той же ступени.
                GlassPill(if (detailsOpen) "Скрыть" else "Ещё $detailsCount",
                    icon = if (detailsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    height = 34.dp, textSize = 12.sp, onClick = onToggleDetails)
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    val p = nox()
    Box(Modifier.clip(CircleShape).border(1.dp, p.accentLight.copy(alpha = 0.7f), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp)) {
        Text(text, color = p.accentLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Пример варианта для предпросмотра оформления (не данные видео). */
object PreviewSamples {
    val variant: Variant by lazy {
        val video = com.nox.offline.downloader.catalog.Track(
            id = "preview", kind = com.nox.offline.downloader.catalog.TrackKind.VIDEO, ext = "webm", container = "webm",
            vcodec = "vp9", width = 2560, height = 1440, fps = 60.0, dynamicRange = "SDR",
            filesize = 480L * 1024 * 1024, filesizeExact = true)
        val audio = com.nox.offline.downloader.catalog.Track(
            id = "preview-a", kind = com.nox.offline.downloader.catalog.TrackKind.AUDIO, ext = "webm", container = "webm",
            acodec = "opus", filesize = 10L * 1024 * 1024, filesizeExact = true)
        Variant(key = "preview", video = video, audio = audio, tier = "1440p", tierHeight = 1440, width = 2560,
            height = 1440, fps = 60, dynamicRange = "SDR", outputContainer = "webm", outputExt = "webm",
            sizeBytes = 490L * 1024 * 1024, sizeKind = SizeKind.EXACT, support = Support.Ok,
            playback = com.nox.offline.downloader.catalog.Playback.LIKELY, playbackNote = "", needsMerge = true, silent = false)
    }
}
