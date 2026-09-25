package com.nox.offline.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.SafeUrl
import com.nox.offline.ui.BatchLine
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.components.GlassButton
import com.nox.offline.ui.components.GlassPill
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox
import kotlinx.coroutines.delay

/**
 * Несколько ссылок за раз. У каждой ссылки свой разбор, своя карточка и
 * свой выбор качества: недоступное у одного видео не навязывается другим.
 * Неудачная ссылка не отменяет остальные. Буфер обмена читается только по кнопке.
 */
@Composable
fun ColumnScope.BatchAddSheet(vm: MainViewModel, initial: String, close: () -> Unit) {
    val p = nox()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(initial) }
    var preview by remember { mutableStateOf<List<BatchLine>>(emptyList()) }
    var result by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(-1) }
    val entries by vm.batch.entries.collectAsState()

    LaunchedEffect(text) {
        delay(250)
        preview = vm.previewBatch(text)
    }
    DisposableEffect(Unit) { onDispose { vm.batch.cancel() } }

    if (entries.isEmpty()) {
        Muted("По одной ссылке на строку. Можно вставить текст целиком — ссылки найдутся сами.", size = 14.sp, color = Nox.TextSecondary)
        VSpace(10)
        GlassSurface(Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = RoundedCornerShape(18.dp), style = GlassStyles.Field) {
            Box(Modifier.padding(14.dp)) {
                if (text.isEmpty()) Text("https://www.youtube.com/watch?v=…\nhttps://vkvideo.ru/video-…", color = Nox.TextMuted, fontSize = 15.sp)
                BasicTextField(text, { text = it }, textStyle = TextStyle(color = Nox.TextPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(p.accentLight), modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp))
            }
        }
        VSpace(8)
        GlassPill("Вставить из буфера", icon = Icons.Rounded.ContentPaste, height = 40.dp, textSize = 14.sp, onClick = {
            val t = clipboard.getText()?.text.orEmpty()
            if (t.isNotBlank()) text = if (text.isBlank()) t else text.trimEnd() + "\n" + t
        })
        VSpace(14)
        if (preview.isNotEmpty()) {
            val addable = preview.count { it.addable }
            Text("Найдено ссылок: ${preview.count { SafeUrl.looksLikeUrl(it.url) }}, к разбору: $addable",
                color = Nox.TextPrimary, fontSize = 15.sp)
            VSpace(6)
            for (line in preview.take(40)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (line.addable) Icons.Rounded.CheckCircle else Icons.Rounded.RemoveCircleOutline, null,
                        tint = if (line.addable) p.accentLight else Nox.TextMuted, modifier = Modifier.size(18.dp))
                    HSpace(8)
                    Column(Modifier.weight(1f)) {
                        Text(line.url, color = if (line.addable) Nox.TextPrimary else Nox.TextMuted, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (line.note != null) Muted(line.note, size = 12.sp)
                    }
                }
            }
            if (preview.size > 40) Muted("и ещё ${preview.size - 40}", size = 12.sp)
            VSpace(14)
            GlassButton("Найти видео ($addable)", enabled = addable > 0, modifier = Modifier.fillMaxWidth(), height = 56.dp,
                textSize = 18.sp, icon = Icons.Rounded.Search, onClick = { vm.findBatch(preview) })
        }
    } else {
        val ready = entries.count { (it.state as? FinderState.Ready)?.selected != null }
        val searching = entries.count { it.state is FinderState.Searching }
        Muted(if (searching > 0) "Получаем информацию… осталось $searching" else "Проверьте качество у каждого видео",
            size = 14.sp, color = Nox.TextSecondary)
        VSpace(10)
        entries.forEachIndexed { index, e ->
            BatchEntry(vm, index, e, open == index, onToggle = { open = if (open == index) -1 else index })
            VSpace(8)
        }
        VSpace(6)
        GlassButton("Добавить выбранные ($ready)", enabled = ready > 0, modifier = Modifier.fillMaxWidth(), height = 56.dp,
            textSize = 18.sp, onClick = { vm.addBatchSelected { result = it; close() } })
        VSpace(8)
        GlassPill("Назад к ссылкам", height = 38.dp, textSize = 13.sp, onClick = { vm.batch.cancel(); open = -1 })
    }
    if (result.isNotBlank()) Muted(result, color = p.accentLight)
}

@Composable
private fun BatchEntry(vm: MainViewModel, index: Int, e: BatchFinder.Entry, isOpen: Boolean, onToggle: () -> Unit) {
    val p = nox()
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), style = GlassStyles.Chip,
        contentPadding = PaddingValues(12.dp)) {
        Column {
            when (val s = e.state) {
                FinderState.Idle, is FinderState.Searching -> {
                    Text(SafeUrl.host(e.url), color = Nox.TextPrimary, fontSize = 14.sp, maxLines = 1)
                    Muted("Получаем информацию…", size = 12.sp, color = Nox.TextSecondary)
                }
                is FinderState.Failed -> {
                    Text(e.url, color = Nox.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(s.error.message, size = 12.sp, color = Nox.Danger.copy(alpha = 0.9f))
                }
                is FinderState.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.catalog.details.title, color = Nox.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sel = s.selected
                            Muted(if (sel != null) "${sel.title} · ${sizeText(sel)} · ${techLine(sel)}" else "Не добавлять",
                                size = 12.sp, color = if (sel != null) p.accentLight else Nox.TextMuted, maxLines = 2)
                        }
                        HSpace(8)
                        GlassPill(if (isOpen) "Готово" else "Изменить", height = 34.dp, textSize = 12.sp, onClick = onToggle)
                    }
                    if (isOpen) {
                        VSpace(10)
                        LanguageChips(s.catalog, { vm.batch.setLanguage(index, it) })
                        if (s.catalog.languages.size > 1) VSpace(8)
                        VariantList(s.catalog, s.selectedKey, s.expanded, { vm.batch.select(index, it) },
                            { vm.batch.toggleDetails(index, it) })
                        VSpace(8)
                        GlassPill("Не добавлять это видео", height = 34.dp, textSize = 12.sp, onClick = { vm.batch.skip(index) })
                    }
                }
            }
        }
    }
}
