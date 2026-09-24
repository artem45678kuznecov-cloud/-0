package com.nox.offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.updates.ApkVerifier
import com.nox.offline.updates.UpdateManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Проверка скачанного обновления настоящим PackageManager.
 *
 * Нужны заранее положенные в Android/data/<пакет>/files/update-test/ файлы
 * (их кладёт сценарий проверки через adb; в CI их нет — тест пропускается):
 *  - good.apk     — та же сборка с большим versionCode и тем же ключом;
 *  - foreign.apk  — тот же APK, переподписанный чужим ключом;
 *  - other.apk    — APK другого пакета.
 */
@RunWith(AndroidJUnit4::class)
class ApkVerifierDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.getExternalFilesDir(null), "update-test")
    private val verifier = ApkVerifier(ctx)
    private val current = BuildConfig.VERSION_CODE

    @Before fun files() {
        assumeTrue("update-test APKs not provided", File(dir, "good.apk").exists())
    }

    private fun manifest(f: File, versionCode: Int, sha: String = ApkVerifier.sha256(f), size: Long = f.length()) = UpdateManifest(
        applicationId = ctx.packageName, versionName = "test", versionCode = versionCode, minSdk = 26,
        abis = listOf("arm64-v8a", "x86_64"), channel = "stable", apkName = f.name,
        apkUrl = "https://example.invalid/${f.name}", apkSize = size, apkSha256 = sha,
        signingCertSha256 = "", notes = "", publishedAt = "",
    )

    private val good get() = File(dir, "good.apk")
    private val goodCode get() = ctx.packageManager.getPackageArchiveInfo(good.path, 0)!!.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it).toInt() }

    @Test fun sameKeyNewerVersionIsAccepted() {
        val v = verifier.verify(good, manifest(good, goodCode), current)
        assertTrue(v.reason, v.ok)
    }

    @Test fun corruptedFileIsRejectedByChecksum() {
        val bad = File(dir, "corrupt.apk")
        good.copyTo(bad, overwrite = true)
        java.io.RandomAccessFile(bad, "rw").use { it.seek(bad.length() / 2); val b = it.read(); it.seek(bad.length() / 2); it.write(b xor 0xFF) }
        val v = verifier.verify(bad, manifest(good, goodCode), current)
        assertFalse(v.ok); assertTrue(v.reason, v.reason.contains("контрольная сумма"))
    }

    @Test fun wrongSizeIsRejected() {
        val v = verifier.verify(good, manifest(good, goodCode, size = good.length() + 1), current)
        assertFalse(v.ok); assertTrue(v.reason, v.reason.contains("размер"))
    }

    @Test fun foreignSignatureIsRejected() {
        val f = File(dir, "foreign.apk")
        assumeTrue(f.exists())
        val v = verifier.verify(f, manifest(f, goodCode), current)
        assertFalse(v.ok); assertTrue(v.reason, v.reason.contains("подпись"))
    }

    @Test fun otherPackageIsRejected() {
        val f = File(dir, "other.apk")
        assumeTrue(f.exists())
        val code = ctx.packageManager.getPackageArchiveInfo(f.path, 0)!!.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it).toInt() }
        val v = verifier.verify(f, manifest(f, code), current)
        assertFalse(v.ok); assertTrue(v.reason, v.reason.contains("другого приложения"))
    }

    @Test fun notNewerIsRejected() {
        val v = verifier.verify(good, manifest(good, goodCode), goodCode)
        assertFalse(v.ok); assertTrue(v.reason, v.reason.contains("не новее"))
    }

    @Test fun manifestVersionMismatchIsRejected() {
        val v = verifier.verify(good, manifest(good, goodCode + 1), current)
        assertFalse(v.ok); assertTrue(v.reason, v.reason.contains("не совпал с манифестом"))
    }

    @Test fun garbageIsNotAnApk() {
        val g = File(dir, "garbage.apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val v = verifier.verify(g, manifest(g, goodCode), current)
        assertFalse(v.ok); assertTrue(v.reason, v.reason.contains("не корректный APK"))
    }
}
