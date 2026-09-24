package com.nox.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.nox.offline.core.NoxLog
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.NoxRoot
import com.nox.offline.ui.RootRequests
import com.nox.offline.ui.Tab

/**
 * Единственная Activity интерфейса. Вся вёрстка — в [NoxRoot]; здесь
 * только связь с системой: интенты, разрешение на уведомления и то, что
 * нужно сделать при возвращении пользователя в приложение.
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_DOWNLOADS = 1
        const val TAB_PLAYER = 2
        const val TAB_SETTINGS = 3
    }

    private val vm: MainViewModel by viewModels()
    private val requests = RootRequests()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.notificationsAllowed.value = granted
            NoxLog.event("notification-permission", "granted" to granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Всегда тёмные системные панели со светлыми значками.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        handleIntent(intent)
        setContent { NoxRoot(vm, requests, onRequestNotifications = ::askNotificationsIfNeeded) }
        if (savedInstanceState == null) askNotificationsIfNeeded()
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
        // процесс погиб, только что поставлено обновление). Приложение
        // видно — самое время его поднять.
        vm.ensureRunnerIfNeeded()
        vm.updatesOnResume()
        vm.autoCheckUpdates()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(EXTRA_TAB)) {
            requests.tab = Tab.entries.getOrNull(intent.getIntExtra(EXTRA_TAB, 0)) ?: Tab.HOME
        }
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { requests.sharedText = it }
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
