package com.nox.offline.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.nox.offline.NoxApp
import com.nox.offline.core.Format
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.media.FileCheck
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.MainViewModel.CheckPhase
import com.nox.offline.ui.components.SheetController
import com.nox.offline.ui.kit.AmberButton
import com.nox.offline.ui.kit.InlineNote
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.kit.ProgressLine
import com.nox.offline.ui.kit.TileButton
import com.nox.offline.ui.theme.Nox
import kotlinx.coroutines.launch

/**
 * «Проверить файл»: проверка на телефоне, только чтение. Итог опирается на
 * прочитанное; «Восстановить» предлагается, только если проверка нашла то,
 * что этот способ чинит, и всегда в новый файл.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun fileCheckSheet(sheets: SheetController, vm: MainViewModel, m: MediaEntity, context: Context) {
    vm.startFileCheck(m)
    sheets.show("Проверка файла") { _ ->
        val st by vm.fileCheck.collectAsState()
        val s = st ?: return@show
        Text(m.title, color = Nox.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        when (s.phase) {
            CheckPhase.CHECKING, CheckPhase.REPAIRING -> {
                Text(if (s.phase == CheckPhase.CHECKING) "Проверяем структуру и читаем файл так, как его читает плеер…"
                else "Пересобираем индекс в новый файл. Кадры копируются как есть…", color = LavenderText, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ProgressLine(s.progress, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                TileButton("Отменить", { vm.cancelFileWork() }, Modifier.fillMaxWidth(), height = 38.dp)
                InlineNote("Оригинал только читается и не меняется.")
            }
            CheckPhase.DONE -> s.result?.let { r -> Result(r, s, vm, m, sheets, context) }
            CheckPhase.REPAIRED -> {
                Text(s.message, color = Nox.TextPrimary, fontSize = 13.5.sp)
                s.repairDetails.take(6).forEach { Text("• $it", color = LavenderText, fontSize = 11.5.sp) }
                Spacer(Modifier.height(10.dp))
                AmberButton("Смотреть", { NoxApp.get(context).playback.open(m.id) }, icon = Icons.Rounded.PlayArrow, height = 42.dp,
                    textSize = 14.sp)
                if (s.originalName.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TileButton("Удалить исходный файл", {
                        sheets.confirm("Удалить исходный файл?",
                            "«${s.originalName}» будет удалён. Сделайте это, только когда убедитесь, что восстановленный файл нормально " +
                                "воспроизводится и перематывается.", "Удалить", danger = true) { vm.deleteRepairOriginal(m) }
                    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, height = 38.dp)
                }
            }
            CheckPhase.FAILED -> {
                Text(s.message, color = Nox.TextPrimary, fontSize = 13.5.sp)
                Spacer(Modifier.height(10.dp))
                TileButton("Проверить снова", { vm.startFileCheck(m) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, height = 38.dp)
                s.result?.let { CopyReport(it, context) }
            }
        }
    }
}

@androidx.compose.runtime.Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun Result(r: FileCheck, s: MainViewModel.FileCheckUi, vm: MainViewModel, m: MediaEntity, sheets: SheetController, context: Context) {
    val color = when (r.verdict) {
        FileCheck.Verdict.READABLE -> Nox.TextPrimary
        FileCheck.Verdict.UNKNOWN -> LavenderText
        else -> Nox.Danger
    }
    Text(r.verdict.title, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text("${r.container}, ${Format.bytes(r.fileSize)}", color = LavenderText, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    Text(r.summary, color = Nox.TextPrimary, fontSize = 13.5.sp)
    var more by remember { mutableStateOf(false) }
    val lines = r.tracks.map { "дорожка: $it" } + r.details
    lines.take(if (more) lines.size else 4).forEach { Text("• $it", color = LavenderText, fontSize = 11.5.sp) }
    if (lines.size > 4) Text(if (more) "Скрыть подробности" else "Подробности (${lines.size})", color = Nox.TextPrimary, fontSize = 12.sp,
        modifier = Modifier.clickable { more = !more }.padding(vertical = 4.dp))
    r.limits.forEach { Text("Не проверялось: $it", color = LavenderText, fontSize = 11.sp) }
    Spacer(Modifier.height(10.dp))
    if (r.repair == FileCheck.Repair.MP4_REBUILD_INDEX) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        AmberButton("Восстановить (новый файл)", {
            scope.launch {
                val need = vm.repairSize(m)
                sheets.confirm("Восстановить в новый файл?",
                    "NOX пересоберёт индекс кадров без перекодирования: изображение, звук и разрешение не меняются. " +
                        "Будет записан новый файл «… (восстановлено).mp4»${if (need > 0) " размером ${Format.bytes(need)}" else ""} " +
                        "рядом с оригиналом — нужно столько же свободного места. Запись медиатеки переключится на него только после проверки. " +
                        "Оригинал останется на месте: удалить его можно потом отдельно.", "Восстановить") { vm.startRepair(m) }
            }
        }, icon = Icons.Rounded.Build, height = 42.dp, textSize = 14.sp)
        Spacer(Modifier.height(8.dp))
    }
    TileButton("Открыть в другом плеере", {
        runCatching { context.startActivity(vm.otherPlayerIntent(m).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { com.nox.offline.core.AppEvents.notice("На телефоне нет другого видеоплеера") }
    }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew, height = 38.dp)
    InlineNote("Это способ сравнить: если там файл играет, дело в чтении файла плеером NOX. Файл не копируется.")
    CopyReport(r, context)
    if (s.message.isNotEmpty()) InlineNote(s.message)
}

@androidx.compose.runtime.Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun CopyReport(r: FileCheck, context: Context) {
    Spacer(Modifier.height(6.dp))
    TileButton("Скопировать отчёт", {
        val text = r.report() + NoxApp.get(context).playback.lastErrorReport.let { if (it.isBlank()) "" else "\n$it" }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NOX: проверка файла", text))
        com.nox.offline.core.AppEvents.notice("Отчёт скопирован")
    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCopy, height = 38.dp)
}
