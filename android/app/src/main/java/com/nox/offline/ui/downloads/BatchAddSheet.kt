package com.nox.offline.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.nox.offline.ui.components.QualitySelector
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox
import kotlinx.coroutines.delay

/**
 * Несколько ссылок за раз. Каждая проверяется отдельно, и неудачная не
 * отменяет остальные. Буфер обмена читается только по кнопке.
 */
@Composable
fun ColumnScope.BatchAddSheet(vm: MainViewModel, initial: String, close: () -> Unit) {
    val p = nox()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(initial) }
    var preview by remember { mutableStateOf<List<BatchLine>>(emptyList()) }
    var result by remember { mutableStateOf("") }
    val q by vm.quality.collectAsState()

    LaunchedEffect(text) {
        delay(250)
        preview = vm.previewBatch(text)
    }

    Muted("По одной ссылке на строку. Можно вставить текст целиком — ссылки найдутся сами.", size = 14.sp, color = Nox.TextSecondary)
    VSpace(10)
    GlassSurface(Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = RoundedCornerShape(18.dp), style = GlassStyles.Field) {
        Box(Modifier.padding(14.dp)) {
            if (text.isEmpty()) Text("https://vkvideo.ru/video-…\nhttps://…", color = Nox.TextMuted, fontSize = 15.sp)
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
        Text("Найдено ссылок: ${preview.count { SafeUrl.looksLikeUrl(it.url) }}, добавится: $addable",
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
        QualitySelector(q, vm::setQuality)
        VSpace(14)
        GlassButton("Добавить все ($addable)", enabled = addable > 0, modifier = Modifier.fillMaxWidth(), height = 56.dp, textSize = 18.sp,
            onClick = { vm.addBatch(preview, q) { result = it; close() } })
    }
    if (result.isNotBlank()) Muted(result, color = p.accentLight)
}
