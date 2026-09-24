package com.nox.offline.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.components.DownloadCard
import com.nox.offline.ui.components.EmptyDownloads
import com.nox.offline.ui.components.GlassButton
import com.nox.offline.ui.components.GlassIconButton
import com.nox.offline.ui.components.GlassPill
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.QualitySelector
import com.nox.offline.ui.components.ScreenTitle
import com.nox.offline.ui.components.SheetAction
import com.nox.offline.ui.components.StorageCard
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

@Composable
fun DownloadsScreen(vm: MainViewModel, actions: NoxActions, contentPadding: PaddingValues) {
    val downloads by vm.downloads.collectAsState()
    val space by vm.space.collectAsState()
    val message by vm.message.collectAsState()
    val sheets = LocalSheets.current
    val live = downloads.filter { it.status != DownloadStatus.COMPLETED }.sortedBy { it.createdAt }
    val done = downloads.filter { it.status == DownloadStatus.COMPLETED }.sortedByDescending { it.updatedAt }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("title") {
            ScreenTitle("Загрузчик", "Сохраняйте видео для тишины", Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                GlassIconButton(Icons.Rounded.Settings, "Настройки загрузок", actions.openDownloadSettings, size = 58.dp, iconSize = 28.dp)
            }
        }
        item("input") { UrlGroup(vm, actions, Modifier.padding(horizontal = 20.dp)) }
        item("quality") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Качество", color = Nox.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Muted("Выше качество — больше файл", size = 13.sp, color = Nox.TextSecondary)
                }
                VSpace(12)
                val q by vm.quality.collectAsState()
                QualitySelector(q, vm::setQuality)
            }
        }
        item("go") {
            val url by vm.urlInput.collectAsState()
            Column(Modifier.padding(horizontal = 20.dp)) {
                GlassButton("Скачать", onClick = vm::startDownload, icon = Icons.Rounded.Download,
                    enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth(), height = 66.dp, textSize = 23.sp)
                VSpace(10)
                Muted("Загрузка продолжается в фоне. Можно свернуть NOX или заблокировать экран.",
                    size = 14.sp, color = Nox.TextSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (message.isNotBlank()) {
                    VSpace(6)
                    Muted(message, size = 14.sp, color = nox().accentLight, align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { vm.clearMessage() })
                }
            }
        }
        item("queue-title") {
            Row(Modifier.padding(horizontal = 20.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Очередь загрузок", color = Nox.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Muted(if (live.isEmpty()) "" else "${live.size} элем.", size = 15.sp, color = Nox.TextSecondary)
                if (downloads.isNotEmpty()) {
                    HSpace(6)
                    GlassIconButton(Icons.Rounded.MoreHoriz, "Действия с очередью", size = 40.dp, iconSize = 22.dp, onClick = {
                        sheets.actions("Очередь", "Не больше трёх загрузок одновременно; остальные ждут.", listOf(
                            SheetAction("Приостановить все", Icons.Rounded.Download, onClick = { vm.pauseAll() }),
                            SheetAction("Продолжить все", Icons.Rounded.Download, onClick = { vm.resumeAll() }),
                            SheetAction("Убрать готовые из списка", Icons.Rounded.Info, enabled = done.isNotEmpty(),
                                hint = "Видео останутся в медиатеке", onClick = { vm.clearCompleted() }),
                            SheetAction("Удалить все незавершённые", null, danger = true, enabled = live.isNotEmpty(),
                                hint = "Скачанные части будут удалены") {
                                sheets.confirm("Удалить ${live.size} загрузок?",
                                    "Незавершённые задания и их скачанные части будут удалены. Готовые видео в медиатеке не пострадают.",
                                    "Удалить", danger = true) { vm.cancelAllUnfinished() }
                            },
                        ))
                    })
                }
            }
        }
        if (live.isEmpty()) {
            item("empty") {
                EmptyDownloads("Очередь пуста", "Вставьте ссылку выше и нажмите «Скачать»", Modifier.padding(horizontal = 20.dp))
            }
        }
        items(live, key = { it.id }) { d ->
            DownloadCard(d, onToggle = { vm.toggle(d) }, onCancel = { actions.confirmCancel(d) },
                onLongPress = { actions.downloadMenu(d) }, modifier = Modifier.padding(horizontal = 20.dp))
        }
        item("storage") { StorageCard(space, onClick = actions.openStorage, modifier = Modifier.padding(horizontal = 20.dp)) }
        if (done.isNotEmpty()) {
            item("done-title") {
                Text("Готово недавно", color = Nox.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(done.take(10), key = { "done-${it.id}" }) { d ->
                DownloadCard(d, onToggle = {}, onCancel = { vm.cancel(d.id) }, onLongPress = { actions.downloadMenu(d) },
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

/** Стеклянная группа ввода: иконка ссылки, поле, «Вставить», подсказка. */
@Composable
private fun UrlGroup(vm: MainViewModel, actions: NoxActions, modifier: Modifier) {
    val url by vm.urlInput.collectAsState()
    val clipboard = LocalClipboardManager.current
    val p = nox()
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), style = GlassStyles.Card.copy(glow = 0.2f),
        contentPadding = PaddingValues(14.dp)) {
        Column {
            GlassSurface(Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(22.dp), style = GlassStyles.Field) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, null, tint = p.accentLight, modifier = Modifier.size(26.dp))
                    HSpace(12)
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (url.isEmpty()) Text("Вставьте ссылку на видео…", color = Nox.TextMuted, fontSize = 17.sp, maxLines = 1)
                        BasicTextField(
                            value = url,
                            onValueChange = vm::setUrl,
                            textStyle = TextStyle(color = Nox.TextPrimary, fontSize = 17.sp),
                            cursorBrush = SolidColor(p.accentLight),
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { vm.startDownload() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HSpace(8)
                    // Буфер обмена читается только по нажатию — никогда сам.
                    GlassPill("Вставить", icon = Icons.Rounded.ContentPaste, accent = true, height = 46.dp, onClick = {
                        val text = clipboard.getText()?.text.orEmpty()
                        if (text.isNotBlank()) vm.setUrl(text.trim())
                    })
                }
            }
            VSpace(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, tint = Nox.TextMuted, modifier = Modifier.size(18.dp))
                HSpace(8)
                Muted("VK Video и другие сайты, которые понимает yt-dlp", size = 13.sp, color = Nox.TextSecondary,
                    modifier = Modifier.weight(1f), maxLines = 2)
                Row(
                    Modifier.clickable(role = Role.Button) { actions.openBatch(url) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = p.accentLight, modifier = Modifier.size(20.dp))
                    HSpace(4)
                    Text("Несколько", color = p.accentLight, fontSize = 14.sp)
                }
            }
        }
    }
}
