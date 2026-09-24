package com.nox.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nox.offline.core.NoxLog
import com.nox.offline.core.SafeUrl
import com.nox.offline.data.db.MediaEntity
import com.nox.offline.player.PlayerActivity
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.downloads.DownloadsScreen
import com.nox.offline.ui.home.HomeScreen
import com.nox.offline.ui.player.PlayerTabScreen
import com.nox.offline.ui.settings.SettingsScreen
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.NoxTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_DOWNLOADS = 1
        const val TAB_PLAYER = 2
        const val TAB_SETTINGS = 3
    }

    private val vm: MainViewModel by viewModels()
    private var requestedTab: Int = -1

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.notificationsAllowed.value = granted
            NoxLog.event("notification-permission", "granted" to granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            NoxTheme { Root() }
        }
        askNotificationsIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.notificationsAllowed.value = notificationsGranted()
        vm.refreshSpace()
        // Очередь могла остаться без носителя (система остановила job,
        // процесс погиб). Приложение видно — самое время его поднять.
        vm.ensureRunnerIfNeeded()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(EXTRA_TAB)) requestedTab = intent.getIntExtra(EXTRA_TAB, -1)
        if (intent.action == Intent.ACTION_SEND) {
            val url = SafeUrl.extract(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (url != null) {
                vm.setUrl(url)
                requestedTab = TAB_HOME
            }
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openPlayer(m: MediaEntity) {
        startActivity(PlayerActivity.intent(this, m))
    }

    @Composable
    private fun Root() {
        var tab by rememberSaveable { mutableIntStateOf(if (requestedTab >= 0) requestedTab else TAB_HOME) }
        if (requestedTab >= 0) { tab = requestedTab; requestedTab = -1 }
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Nox.BgTop.copy(alpha = 0.96f), tonalElevation = 0.dp) {
                    val items = listOf(
                        Triple("Главная", Icons.Filled.Home, TAB_HOME),
                        Triple("Загрузки", Icons.Filled.Download, TAB_DOWNLOADS),
                        Triple("Плеер", Icons.Filled.PlayCircle, TAB_PLAYER),
                        Triple("Настройки", Icons.Filled.Settings, TAB_SETTINGS),
                    )
                    for ((label, icon, index) in items) {
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Nox.TextPrimary, selectedTextColor = Nox.AccentLight,
                                unselectedIconColor = Nox.TextMuted, unselectedTextColor = Nox.TextMuted,
                                indicatorColor = Nox.Accent.copy(alpha = 0.35f),
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Nox.BgTop, Nox.Bg, Nox.BgDeep)))
                    .padding(padding),
            ) {
                when (tab) {
                    TAB_HOME -> HomeScreen(vm, onOpenMedia = ::openPlayer)
                    TAB_DOWNLOADS -> DownloadsScreen(vm)
                    TAB_PLAYER -> PlayerTabScreen(vm, onOpen = ::openPlayer)
                    else -> SettingsScreen(vm, onRequestNotifications = ::askNotificationsIfNeeded)
                }
            }
        }
    }
}
