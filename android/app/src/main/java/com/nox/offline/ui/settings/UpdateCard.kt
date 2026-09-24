package com.nox.offline.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.BuildConfig
import com.nox.offline.core.Format
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.components.GlassButton
import com.nox.offline.ui.components.GlassIconButton
import com.nox.offline.ui.components.GlassPill
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxProgress
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox
import com.nox.offline.updates.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Содержимое блока «Обновления» в настройках — по состоянию подсистемы. */
@Composable
fun UpdateSection(state: UpdateState, vm: MainViewModel, actions: NoxActions) {
    val p = nox()
    Text("Установлена версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Nox.TextPrimary, fontSize = 16.sp)
    VSpace(8)
    when (state) {
        UpdateState.Idle -> {
            Muted("Обновления берутся из GitHub Releases этого проекта. Установка — только с вашего подтверждения.", size = 13.sp, color = Nox.TextSecondary)
            VSpace(10)
            GlassPill("Проверить обновления", icon = Icons.Rounded.SystemUpdate, onClick = vm::checkUpdates, accent = true)
        }
        UpdateState.Checking -> {
            Muted("Проверяем…", size = 14.sp, color = Nox.TextSecondary)
            VSpace(8)
            NoxProgress(0f, kind = com.nox.offline.ui.components.ProgressKind.INDETERMINATE)
        }
        is UpdateState.UpToDate -> {
            Muted("У вас последняя версия • проверено ${SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(state.checkedAt))}",
                size = 14.sp, color = Nox.TextSecondary)
            VSpace(10)
            GlassPill("Проверить ещё раз", onClick = vm::checkUpdates)
        }
        is UpdateState.Available -> {
            Text("Доступна версия ${state.manifest.versionName}", color = p.accentLight, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (state.manifest.notes.isNotBlank()) {
                VSpace(4); Muted(state.manifest.notes, size = 13.sp, color = Nox.TextSecondary, maxLines = 6)
            }
            VSpace(4)
            Muted("Размер ${Format.bytes(state.manifest.apkSize)}. Медиатека, очередь и настройки сохранятся.", size = 13.sp)
            VSpace(12)
            GlassButton("Обновить", onClick = { vm.downloadUpdate(state.manifest) }, icon = Icons.Rounded.SystemUpdate,
                modifier = Modifier.fillMaxWidth(), height = 52.dp, textSize = 18.sp)
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill("Позже", onClick = vm::updateLater, modifier = Modifier.weight(1f))
                GlassPill("Пропустить эту версию", onClick = { vm.skipUpdate(state.manifest) }, modifier = Modifier.weight(1.4f))
            }
        }
        is UpdateState.Downloading -> {
            Text("Скачивается ${state.manifest.versionName}", color = Nox.TextPrimary, fontSize = 16.sp)
            VSpace(8)
            NoxProgress(if (state.total > 0) state.done.toFloat() / state.total else 0f,
                kind = if (state.total > 0) com.nox.offline.ui.components.ProgressKind.DETERMINATE else com.nox.offline.ui.components.ProgressKind.INDETERMINATE)
            VSpace(6)
            Muted("${Format.bytes(state.done)} из ${Format.bytes(state.total)} • загрузки видео не останавливаются", size = 13.sp)
            VSpace(10)
            GlassPill("Отменить", onClick = vm::cancelUpdateDownload)
        }
        is UpdateState.NeedsPermission -> {
            Text("Нужно разрешение на установку", color = Nox.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            VSpace(4)
            Muted("Android спрашивает, можно ли NOX устанавливать приложения. Это нужно только чтобы поставить обновление NOX. " +
                "Включите «Разрешить из этого источника» и вернитесь — установка продолжится.", size = 13.sp, color = Nox.TextSecondary)
            VSpace(10)
            GlassPill("Открыть настройки Android", accent = true, onClick = actions.openInstallPermission)
        }
        is UpdateState.Installing -> {
            Text("Ждём подтверждения установки", color = Nox.TextPrimary, fontSize = 16.sp)
            VSpace(4)
            Muted("Подтвердите установку в окне Android. Загрузки видео приостановлены на время установки и продолжатся сами. " +
                "Если окно закрыли — нажмите «Обновить» ещё раз.", size = 13.sp, color = Nox.TextSecondary)
        }
        is UpdateState.Failed -> {
            Text(state.message, color = Nox.Danger, fontSize = 15.sp)
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.retryable) {
                    GlassPill("Повторить", accent = true, onClick = {
                        if (state.manifest != null) vm.downloadUpdate(state.manifest) else vm.checkUpdates()
                    })
                }
                GlassPill("Закрыть", onClick = vm::updateLater)
            }
        }
        is UpdateState.JustUpdated -> {
            Text("NOX обновлён до ${state.versionName}", color = Nox.Ok, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            VSpace(4)
            Muted("Медиатека, очередь и позиции просмотра на месте.", size = 13.sp, color = Nox.TextSecondary)
            VSpace(8)
            GlassPill("Понятно", onClick = vm::updateLater)
        }
    }
}

/**
 * Ненавязчивая плашка вверху экрана: появляется, только когда есть что
 * сказать — «Доступна версия …» или «NOX обновлён».
 */
@Composable
fun UpdateBanner(state: UpdateState, vm: MainViewModel, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val (text, action) = when (state) {
        is UpdateState.Available -> "Доступна версия ${state.manifest.versionName}" to "Обновить"
        is UpdateState.JustUpdated -> "NOX обновлён до ${state.versionName}" to null
        is UpdateState.Downloading -> "Скачивается обновление ${Format.percent(state.done, state.total)}%" to null
        else -> return
    }
    GlassSurface(modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(26.dp), style = GlassStyles.Sheet.copy(glow = 0.4f), onClick = onOpenSettings) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = nox().accentLight)
            HSpace(10)
            Text(text, color = Nox.TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (action != null) {
                GlassPill(action, accent = true, height = 38.dp, textSize = 14.sp, onClick = {
                    if (state is UpdateState.Available) vm.downloadUpdate(state.manifest)
                    onOpenSettings()
                })
            }
            GlassIconButton(Icons.Rounded.Close, "Скрыть", vm::updateLater, size = 38.dp, iconSize = 18.dp)
        }
    }
}
