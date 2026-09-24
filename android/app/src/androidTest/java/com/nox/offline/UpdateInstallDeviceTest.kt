package com.nox.offline

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.data.db.DownloadStatus
import com.nox.offline.updates.UpdateInstaller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Установка обновления тем же путём, что и из баннера «Обновить»:
 * контрольная точка загрузок → сессия PackageInstaller → системное окно.
 *
 * Окно подтверждения нажимает человек или сценарий через adb, поэтому
 * тест запускается только с аргументом -e noxInstallE2E cancel|confirm
 * и файлом update-test/good.apk. В CI он пропускается.
 */
@RunWith(AndroidJUnit4::class)
class UpdateInstallDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val mode = InstrumentationRegistry.getArguments().getString("noxInstallE2E") ?: ""
    private val app get() = NoxApp.get(ctx)

    private fun prepared(): File {
        val src = File(ctx.getExternalFilesDir(null), "update-test/good.apk")
        assumeTrue(src.exists())
        val dst = File(ctx.cacheDir, "updates/NOX-5.apk").apply { parentFile!!.mkdirs() }
        src.copyTo(dst, overwrite = true)
        return dst
    }

    @Test fun cancelledInstallKeepsNoxWorking() {
        assumeTrue(mode == "cancel")
        val apk = prepared()
        ActivityScenario.launch(MainActivity::class.java).use {
            val pausedBefore = runBlocking { app.db.downloads().getAll().count { it.status == DownloadStatus.PAUSED } }
            runBlocking { app.coordinator.checkpointForUpdate() }
            assertTrue(app.coordinator.updateHold.value)
            UpdateInstaller(ctx).install(apk, 5)
            // Сценарий нажимает «Отмена» в системном окне.
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (app.coordinator.updateHold.value && System.currentTimeMillis() < deadline) Thread.sleep(1000)
            assertFalse("hold must be released after cancel", app.coordinator.updateHold.value)
            val pausedAfter = runBlocking { app.db.downloads().getAll().count { it.status == DownloadStatus.PAUSED } }
            assertEquals("user pauses stay paused", pausedBefore, pausedAfter)
            assertEquals(BuildConfig.VERSION_CODE.toLong(),
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)))
        }
    }

    @Test fun confirmedInstallReplacesPackage() {
        assumeTrue(mode == "confirm")
        val apk = prepared()
        ActivityScenario.launch(MainActivity::class.java)
        runBlocking { app.coordinator.checkpointForUpdate() }
        app.settings.updateUpdates { it.copy(pendingInstallVersionCode = 5) }
        UpdateInstaller(ctx).install(apk, 5)
        // Сценарий нажимает «Обновить»; замена пакета завершает этот процесс.
        Thread.sleep(15 * 60_000)
    }
}
