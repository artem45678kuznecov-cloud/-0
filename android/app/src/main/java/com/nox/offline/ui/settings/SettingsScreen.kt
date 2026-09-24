package com.nox.offline.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.BuildConfig
import com.nox.offline.NoxApp
import com.nox.offline.core.Format
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.components.GhostButton
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxCard
import com.nox.offline.ui.components.SectionTitle
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.theme.Nox
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel, onRequestNotifications: () -> Unit) {
    val space by vm.space.collectAsState()
    val allowed by vm.notificationsAllowed.collectAsState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf("") }
    val storageRoot = NoxApp.get(androidx.compose.ui.platform.LocalContext.current).storage.root.absolutePath

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text("Настройки", color = Nox.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Muted("NOX Android ${BuildConfig.VERSION_NAME}", size = 13, color = Nox.TextSecondary)
            }
        }
        item {
            NoxCard {
                SectionTitle("Хранилище")
                VSpace(6)
                Muted(storageRoot, size = 12, color = Nox.TextSecondary)
                VSpace(6)
                if (space != null) {
                    Muted("Занято NOX ${Format.bytes(space!!.usedByNox)} • свободно ${Format.bytes(space!!.freeBytes)}", size = 12)
                }
                VSpace(6)
                Muted("Готовые видео — в Media/, незавершённые .part — в Downloads/, обложки — в Covers/. Разрешение на все файлы не нужно.", size = 11)
            }
        }
        item {
            NoxCard {
                SectionTitle("Фоновая загрузка")
                VSpace(6)
                val mode = if (Build.VERSION.SDK_INT >= 34) "User-Initiated Data Transfer Job (Android 14+)"
                else "Foreground service (Android ${Build.VERSION.RELEASE})"
                Muted(mode, size = 12, color = Nox.TextSecondary)
                VSpace(6)
                Muted("Уведомления: ${if (allowed) "разрешены" else "выключены — прогресс в шторке виден не будет"}", size = 12,
                    color = if (allowed) Nox.TextMuted else Nox.Danger)
                if (!allowed) {
                    VSpace(8)
                    GhostButton("Разрешить уведомления", onClick = onRequestNotifications)
                }
            }
        }
        item {
            NoxCard {
                SectionTitle("Диагностика")
                VSpace(6)
                Muted("Версия, устройство, состояния заданий и последние события. Без cookie и без подписанных адресов.", size = 12)
                VSpace(10)
                GhostButton("Скопировать диагностику", onClick = {
                    scope.launch {
                        val text = vm.diagnostics()
                        clipboard.setText(AnnotatedString(text))
                        copied = "Скопировано: ${text.lines().size} строк"
                    }
                })
                if (copied.isNotBlank()) {
                    VSpace(6)
                    Muted(copied, size = 12, color = Nox.AccentLight)
                }
            }
        }
        item {
            NoxCard {
                SectionTitle("О NOX")
                VSpace(6)
                Muted("Офлайн-медиатека. Разбор ссылок — yt-dlp, передача — OkHttp, плеер — Media3. Не больше трёх загрузок одновременно.", size = 12)
            }
        }
    }
}
