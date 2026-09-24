package com.nox.offline.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateLogicTest {
    private val sha = "a".repeat(64)

    private fun manifestJson(
        pkg: String = "com.nox.offline",
        code: Int = 3,
        minSdk: Int = 26,
        abis: String = "[\"arm64-v8a\",\"x86_64\"]",
        url: String = "https://github.com/artem45678kuznecov-cloud/-0/releases/download/android-v0.3.0/NOX-Android-v0.3.0.apk",
        apkSha: String = sha,
        format: String = "nox-update",
    ) = """
        {"format":"$format","formatVersion":1,"applicationId":"$pkg","versionName":"0.3.0","versionCode":$code,
         "minSdk":$minSdk,"abis":$abis,"channel":"stable",
         "apk":{"name":"NOX-Android-v0.3.0.apk","url":"$url","size":1234,"sha256":"$apkSha"},
         "signingCertSha256":"E3F2","releaseNotes":"что нового","publishedAt":"2026-09-24T00:00:00Z"}
    """.trimIndent()

    private fun decide(m: UpdateManifest, current: Int = 2, skipped: Int = 0, sdk: Int = 34,
                       abis: List<String> = listOf("arm64-v8a"), manual: Boolean = false) =
        UpdatePolicy.decide(m, "com.nox.offline", current, skipped, sdk, abis, manual)

    @Test fun parsesValidManifest() {
        val m = UpdateManifest.parse(manifestJson())
        assertEquals(3, m.versionCode)
        assertEquals("0.3.0", m.versionName)
        assertEquals(listOf("arm64-v8a", "x86_64"), m.abis)
        assertEquals(1234L, m.apkSize)
        assertEquals("e3f2", m.signingCertSha256)
        assertEquals("что нового", m.notes)
    }

    @Test fun rejectsBrokenManifests() {
        for (bad in listOf(
            manifestJson(format = "other"),
            manifestJson(url = "http://example.com/a.apk"),
            manifestJson(apkSha = "123"),
            manifestJson(code = 0),
            "{not json",
        )) {
            try { UpdateManifest.parse(bad); fail("manifest must be rejected: $bad") } catch (_: Exception) {}
        }
    }

    @Test fun newerVersionIsAvailable() {
        assertTrue(decide(UpdateManifest.parse(manifestJson(code = 3))) is UpdateDecision.Available)
    }

    @Test fun sameOrOlderVersionIsUpToDate() {
        assertTrue(decide(UpdateManifest.parse(manifestJson(code = 2))) is UpdateDecision.UpToDate)
        assertTrue(decide(UpdateManifest.parse(manifestJson(code = 1))) is UpdateDecision.UpToDate)
    }

    @Test fun foreignPackageIsIncompatible() {
        val d = decide(UpdateManifest.parse(manifestJson(pkg = "com.other.app")))
        assertTrue(d is UpdateDecision.Incompatible)
    }

    @Test fun tooNewAndroidOrWrongAbiIsIncompatible() {
        assertTrue(decide(UpdateManifest.parse(manifestJson(minSdk = 35)), sdk = 30) is UpdateDecision.Incompatible)
        assertTrue(decide(UpdateManifest.parse(manifestJson(abis = "[\"x86_64\"]")), abis = listOf("arm64-v8a", "armeabi-v7a")) is UpdateDecision.Incompatible)
        assertTrue(decide(UpdateManifest.parse(manifestJson(abis = "[]"))) is UpdateDecision.Available)
    }

    @Test fun skippedVersionIsQuietOnlyForAutoCheck() {
        val m = UpdateManifest.parse(manifestJson(code = 3))
        assertTrue(decide(m, skipped = 3) is UpdateDecision.Skipped)
        assertTrue(decide(m, skipped = 3, manual = true) is UpdateDecision.Available)
        // Пропуск 3 не глушит следующую версию.
        assertTrue(decide(UpdateManifest.parse(manifestJson(code = 4)), skipped = 3) is UpdateDecision.Available)
    }

    @Test fun autoCheckIsRateLimited() {
        val h = UpdatePolicy.AUTO_INTERVAL_MS
        assertTrue(UpdatePolicy.autoCheckDue(0, h))
        assertFalse(UpdatePolicy.autoCheckDue(1_000, 1_000 + h - 1))
        assertTrue(UpdatePolicy.autoCheckDue(1_000, 1_000 + h))
        // Часы переведены назад — не ждать сутками.
        assertTrue(UpdatePolicy.autoCheckDue(10_000, 5_000))
    }

    @Test fun signatureMustMatchOrBeInRotationHistory() {
        val ours = setOf("AA11")
        assertTrue(SignatureCheck.isTrusted(ours, setOf("aa11"), emptyList()))
        assertFalse("foreign key", SignatureCheck.isTrusted(ours, setOf("bb22"), emptyList()))
        assertTrue("rotated key keeps our cert in history", SignatureCheck.isTrusted(ours, setOf("bb22"), listOf("aa11", "bb22")))
        assertFalse("unsigned archive", SignatureCheck.isTrusted(ours, emptySet(), emptyList()))
        assertFalse("unknown installed signer", SignatureCheck.isTrusted(emptySet(), setOf("aa11"), emptyList()))
    }
}
