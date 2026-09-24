package com.nox.offline.updates

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Проверка скачанного APK до установки. Любое «нет» — отказ с понятной
 * причиной; файл при этом удаляется, установщик не открывается.
 */
class ApkVerifier(private val context: Context) {
    data class Verdict(val ok: Boolean, val reason: String = "")

    fun verify(file: File, m: UpdateManifest, currentVersionCode: Int): Verdict {
        if (!file.exists()) return Verdict(false, "файл не найден")
        if (m.apkSize > 0 && file.length() != m.apkSize) return Verdict(false, "размер не совпал: ${file.length()} из ${m.apkSize}")
        val sha = sha256(file)
        if (sha != m.apkSha256) return Verdict(false, "контрольная сумма не совпала — файл повреждён")

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else signaturesFlag()
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return Verdict(false, "это не корректный APK")
        if (info.packageName != context.packageName) return Verdict(false, "APK другого приложения (${info.packageName})")
        val code = versionCodeOf(info)
        if (code <= currentVersionCode) return Verdict(false, "версия не новее установленной")
        if (code != m.versionCode.toLong()) return Verdict(false, "versionCode APK ($code) не совпал с манифестом (${m.versionCode})")
        val minSdk = info.applicationInfo?.minSdkVersion ?: 0
        if (minSdk > Build.VERSION.SDK_INT) return Verdict(false, "нужен Android API $minSdk")
        val apkAbis = abisOf(file)
        if (apkAbis.isNotEmpty() && apkAbis.none { it in Build.SUPPORTED_ABIS }) {
            return Verdict(false, "APK не содержит кода для этого процессора")
        }

        val installed = installedSigners()
        val (archiveSigners, history) = archiveSigners(info)
        if (!SignatureCheck.isTrusted(installed, archiveSigners, history)) {
            return Verdict(false, "подпись APK не совпадает с установленным NOX — обновление отклонено")
        }
        return Verdict(true)
    }

    fun installedSigners(): Set<String> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return emptySet()
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else arrayOf(si.signingCertificateHistory.last())
            sigs.map { digest(it) }.toSet()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, signaturesFlag()).signatures?.map { digest(it) }?.toSet() ?: emptySet()
        }
    }

    private fun archiveSigners(info: PackageInfo): Pair<Set<String>, List<String>> =
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet<String>() to emptyList()
            if (si.hasMultipleSigners()) {
                si.apkContentsSigners.map { digest(it) }.toSet() to emptyList()
            } else {
                val hist = si.signingCertificateHistory.map { digest(it) }
                setOf(hist.last()) to hist
            }
        } else {
            @Suppress("DEPRECATION")
            (info.signatures?.map { digest(it) }?.toSet() ?: emptySet()) to emptyList()
        }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun signaturesFlag() = PackageManager.GET_SIGNATURES

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun abisOf(file: File): Set<String> = try {
        ZipFile(file).use { z ->
            z.entries().asSequence().mapNotNull { e ->
                val n = e.name
                if (n.startsWith("lib/") && n.count { it == '/' } >= 2) n.substring(4, n.indexOf('/', 4)) else null
            }.toSet()
        }
    } catch (_: Exception) {
        emptySet()
    }

    companion object {
        fun digest(sig: Signature): String = hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()))

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return hex(md.digest())
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
