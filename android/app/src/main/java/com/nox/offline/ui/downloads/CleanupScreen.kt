package com.nox.offline.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.core.Format
import com.nox.offline.library.LibraryRules
import com.nox.offline.storage.PlaybackRegistry
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.Nav
import com.nox.offline.ui.components.Cover
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.kit.AmberButton
import com.nox.offline.ui.kit.AmberSwitch
import com.nox.offline.ui.kit.EmptyBlock
import com.nox.offline.ui.kit.InlineNote
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ProgressLine
import com.nox.offline.ui.kit.ScreenHeading
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.kit.Tile
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/**
 * Память и очистка: предложение удалить просмотренное с итоговым
 * объёмом. Ничего не удаляется само — только выбранное и после
 * подтверждения. Защищённые, открытые в плеере и незавершённые загрузки
 * (.part) сюда не попадают.
 */
@Composable
fun CleanupScreen(vm: MainViewModel, nav: Nav, padding: PaddingValues) {
    val all by vm.allItems.collectAsState()
    val space by vm.space.collectAsState()
    val sheets = LocalSheets.current
    val playing = PlaybackRegistry.playingMediaId
    val candidates = remember(all, playing) {
        LibraryRules.cleanup(all.map { it.media }, all.mapNotNull { it.playback }.associateBy { it.mediaId }, playing)
    }
    var chosen by remember(candidates) { mutableStateOf(candidates.map { it.media.id }.toSet()) }
    val total = candidates.filter { it.media.id in chosen }.sumOf { it.sizeBytes }
    val protectedList = all.filter { it.media.protectedFromCleanup }

    LazyColumn(contentPadding = padding) {
        item {
            Spacer(Modifier.height(8.dp))
            ScreenHeading("Память", "Освободите место от просмотренного", onBack = { nav.back() })
            SectionGap()
            Section("Память устройства", icon = Icons.Rounded.Storage, contentPadding = PaddingValues(14.dp)) {
                val sp = space
                if (sp != null && sp.totalBytes > 0) {
                    Text("${Format.bytes(sp.freeBytes)} свободно из ${Format.bytes(sp.totalBytes)}", color = Nox.TextPrimary, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    ProgressLine((sp.totalBytes - sp.freeBytes).toFloat() / sp.totalBytes)
                    Text("NOX занимает ${Format.bytes(sp.usedByNox)} (видео, незавершённые части, обложки).", color = LavenderText,
                        fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            SectionGap()
        }
        item {
            Section("Просмотренные", icon = Icons.Rounded.CleaningServices,
                trailing = if (candidates.isNotEmpty()) "≈ ${Format.bytes(total)}" else null) {
                if (candidates.isEmpty()) {
                    EmptyBlock(Icons.Rounded.CleaningServices, "Нечего предложить",
                        "Здесь появятся видео, досмотренные до конца. Защищённые и открытые в плеере не предлагаются.")
                } else {
                    InlineNote("Отмечены все досмотренные. Снимите отметку с того, что хотите оставить.")
                    for (c in candidates) {
                        val on = c.media.id in chosen
                        Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp), selected = on,
                            onClick = { chosen = if (on) chosen - c.media.id else chosen + c.media.id }) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (on) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null,
                                    tint = if (on) nox().accent else LavenderText)
                                Spacer(Modifier.width(8.dp))
                                Cover(c.media.coverPath, Modifier.width(84.dp).aspectRatio(16f / 9f), RoundedCornerShape(7.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.media.title, color = Nox.TextPrimary, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(Format.bytes(c.sizeBytes), color = LavenderText, fontSize = 12.sp)
                                }
                                Icon(Icons.Rounded.Shield, "Защитить от очистки", tint = LavenderText,
                                    modifier = Modifier.size(38.dp).clip(CircleShape).clickable { vm.setProtected(c.media, true) }.padding(7.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    AmberButton("Удалить выбранные", {
                        val list = candidates.filter { it.media.id in chosen }.map { it.media }
                        sheets.confirm("Удалить ${list.size} видео?", "Будет освобождено ≈ ${Format.bytes(total)}. Файлы удалятся с устройства; " +
                            "коллекции сохранят ссылки на ролики с известным источником.", "Удалить", danger = true) { vm.cleanup(list) }
                    }, trailing = "≈ ${Format.bytes(total)}", enabled = chosen.isNotEmpty(), height = 50.dp, textSize = 16.sp)
                }
            }
            SectionGap()
        }
        item {
            Section("Защищены от очистки", icon = Icons.Rounded.Shield, trailing = "${protectedList.size}") {
                if (protectedList.isEmpty()) InlineNote("Нажмите на щит у видео, чтобы оно никогда не предлагалось к удалению.")
                for (it in protectedList) {
                    Tile(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(it.media.title, color = Nox.TextPrimary, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            AmberSwitch(true, { on -> vm.setProtected(it.media, on) }, label = "Защита «${it.media.title}»")
                        }
                    }
                }
            }
        }
    }
}
