package com.nox.offline.updates

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Задание на скачивание обновления — на диске, а не в памяти: оно переживает
 * сворачивание, закрытие и перезапуск процесса. Лежит рядом с файлами APK в
 * filesDir (кэш система вправе очистить). Принятые байты — это фактическая
 * длина .part: она сверяется при каждом продолжении.
 */
class UpdateJobStore(private val dir: File) {
    data class Job(
        val manifest: UpdateManifest,
        val createdAt: Long,
        /** Остановил сам пользователь: при следующем запуске NOX не продолжать без его нажатия. */
        val stoppedByUser: Boolean = false,
        val lastError: String = "",
        val attempts: Int = 0,
        val networkBytes: Long = 0,
        val transferMs: Long = 0,
        val pauseMs: Long = 0,
        val verifyMs: Long = -1,
        /** Последние события передачи (остановки системой, отказы, повторы) — коротко, для диагностики. */
        val events: List<String> = emptyList(),
    )

    private val file get() = File(dir, "job.json")

    fun part(m: UpdateManifest) = File(dir, "NOX-${m.versionCode}.apk.part")
    fun apk(m: UpdateManifest) = File(dir, "NOX-${m.versionCode}.apk")

    fun load(): Job? = runCatching {
        val o = JSONObject(file.readText())
        Job(
            manifest = UpdateManifest.parse(o.getString("manifest")),
            createdAt = o.optLong("createdAt"),
            stoppedByUser = o.optBoolean("stoppedByUser"),
            lastError = o.optString("lastError"),
            attempts = o.optInt("attempts"),
            networkBytes = o.optLong("networkBytes"),
            transferMs = o.optLong("transferMs"),
            pauseMs = o.optLong("pauseMs"),
            verifyMs = o.optLong("verifyMs", -1),
            events = o.optJSONArray("events")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }.getOrNull()

    fun save(job: Job) {
        dir.mkdirs()
        val o = JSONObject()
            .put("manifest", job.manifest.toJson())
            .put("createdAt", job.createdAt)
            .put("stoppedByUser", job.stoppedByUser)
            .put("lastError", job.lastError)
            .put("attempts", job.attempts)
            .put("networkBytes", job.networkBytes)
            .put("transferMs", job.transferMs)
            .put("pauseMs", job.pauseMs)
            .put("verifyMs", job.verifyMs)
            .put("events", JSONArray(job.events.takeLast(MAX_EVENTS)))
        // Сначала во временный файл, потом переименование: оборванная запись не портит задание.
        val tmp = File(dir, "job.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX_EVENTS = 20
    }
}
