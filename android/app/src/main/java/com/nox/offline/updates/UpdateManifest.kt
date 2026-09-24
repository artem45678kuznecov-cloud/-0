package com.nox.offline.updates

import org.json.JSONObject

/**
 * Машиночитаемое описание релиза — файл nox-update.json рядом с APK в
 * GitHub Release. Приложение читает только его: API GitHub и токены не
 * нужны, лимит запросов к API не расходуется.
 */
data class UpdateManifest(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val abis: List<String>,
    val channel: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
    val signingCertSha256: String,
    val notes: String,
    val publishedAt: String,
) {
    companion object {
        const val FORMAT = "nox-update"
        const val FORMAT_VERSION = 1

        fun parse(raw: String): UpdateManifest {
            val o = JSONObject(raw)
            require(o.optString("format") == FORMAT) { "это не манифест обновления NOX" }
            require(o.optInt("formatVersion", 0) in 1..FORMAT_VERSION) { "формат манифеста новее, чем знает это приложение" }
            val apk = o.getJSONObject("apk")
            val abis = o.optJSONArray("abis")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val m = UpdateManifest(
                applicationId = o.getString("applicationId"),
                versionName = o.getString("versionName"),
                versionCode = o.getInt("versionCode"),
                minSdk = o.optInt("minSdk", 26),
                abis = abis,
                channel = o.optString("channel", "stable"),
                apkName = apk.getString("name"),
                apkUrl = apk.getString("url"),
                apkSize = apk.optLong("size", 0),
                apkSha256 = apk.getString("sha256").lowercase(),
                signingCertSha256 = o.optString("signingCertSha256").lowercase(),
                notes = o.optString("releaseNotes"),
                publishedAt = o.optString("publishedAt"),
            )
            require(m.versionCode > 0) { "неверный versionCode" }
            require(m.apkUrl.startsWith("https://")) { "адрес APK должен быть https" }
            require(Regex("[0-9a-f]{64}").matches(m.apkSha256)) { "в манифесте нет SHA-256 файла" }
            return m
        }
    }
}

/** Что делать с найденным манифестом. Чистая логика — проверяется JVM-тестом. */
sealed interface UpdateDecision {
    object UpToDate : UpdateDecision
    data class Available(val manifest: UpdateManifest) : UpdateDecision
    data class Skipped(val manifest: UpdateManifest) : UpdateDecision
    data class Incompatible(val manifest: UpdateManifest, val reason: String) : UpdateDecision
}

object UpdatePolicy {
    /** Сравнение только по versionCode: «0.10» и «0.9» как строки не сравниваются. */
    fun decide(
        m: UpdateManifest,
        packageName: String,
        currentVersionCode: Int,
        skippedVersionCode: Int,
        sdk: Int,
        supportedAbis: List<String>,
        manual: Boolean,
    ): UpdateDecision {
        if (m.applicationId != packageName) return UpdateDecision.Incompatible(m, "манифест для другого приложения")
        if (m.versionCode <= currentVersionCode) return UpdateDecision.UpToDate
        if (m.minSdk > sdk) return UpdateDecision.Incompatible(m, "нужен Android API ${m.minSdk}, у вас $sdk")
        if (m.abis.isNotEmpty() && m.abis.none { it in supportedAbis }) {
            return UpdateDecision.Incompatible(m, "сборка для ${m.abis.joinToString()}, устройство — ${supportedAbis.joinToString()}")
        }
        if (!manual && m.versionCode == skippedVersionCode) return UpdateDecision.Skipped(m)
        return UpdateDecision.Available(m)
    }

    /** Автопроверка не чаще раза в 12 часов. */
    const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000

    fun autoCheckDue(lastCheckAt: Long, now: Long): Boolean = now - lastCheckAt >= AUTO_INTERVAL_MS || now < lastCheckAt
}

/**
 * Решение о подписи. Установленное приложение доверяет новому APK, если
 * его текущий подписант совпадает, либо если в истории подписи нового APK
 * (APK Signature Scheme v3, ротация ключа) есть наш нынешний сертификат.
 * Совпадение SHA-256 файла подпись не заменяет.
 */
object SignatureCheck {
    fun isTrusted(installedSigners: Set<String>, archiveSigners: Set<String>, archiveHistory: List<String>): Boolean {
        if (installedSigners.isEmpty() || archiveSigners.isEmpty()) return false
        val inst = installedSigners.map { it.lowercase() }.toSet()
        val arch = archiveSigners.map { it.lowercase() }.toSet()
        if (inst == arch) return true
        // Ротация ключа: старый (наш) сертификат обязан быть в истории нового.
        val history = archiveHistory.map { it.lowercase() }
        return inst.size == 1 && history.contains(inst.first())
    }
}
