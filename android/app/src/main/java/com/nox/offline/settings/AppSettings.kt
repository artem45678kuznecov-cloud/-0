package com.nox.offline.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Режим «жидкого стекла». */
enum class GlassMode(val key: String, val label: String) {
    AUTO("auto", "Авто"),
    FULL("full", "Полное стекло"),
    ECONOMY("economy", "Экономичный");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** Встроенные фоны и собственное изображение. */
enum class WallpaperKind(val key: String, val label: String) {
    DEFAULT("default", "NOX"),
    AURORA("aurora", "Аврора"),
    MIDNIGHT("midnight", "Полночь"),
    NEBULA("nebula", "Туманность"),
    DEEP("deep", "Глубина"),
    CUSTOM("custom", "Своё фото");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
        val builtIn = listOf(DEFAULT, AURORA, MIDNIGHT, NEBULA, DEEP)
    }
}

/**
 * Всё оформление одним неизменяемым значением: интерфейс перерисовывается
 * при его смене, а загрузчик и плеер о нём не знают вовсе.
 */
data class Appearance(
    val preset: String = "classic",
    val customHue: Float = 245f,
    val glassMode: GlassMode = GlassMode.AUTO,
    val glassOpacity: Float = 0.55f,
    val glowStrength: Float = 0.6f,
    val effectIntensity: Float = 0.7f,
    val reduceMotion: Boolean = false,
    val wallpaper: WallpaperKind = WallpaperKind.DEFAULT,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
    val dim: Float = 0.45f,
    val blur: Float = 0.0f,
    /** Меняется при замене своего фото: сбрасывает кэш картинки. */
    val wallpaperVersion: Long = 0,
) {
    companion object {
        // Границы, за которыми текст на стекле перестаёт читаться.
        const val MIN_GLASS_OPACITY = 0.30f
        const val MAX_GLASS_OPACITY = 0.85f
        const val MIN_DIM = 0.20f
        const val MAX_DIM = 0.85f
    }

    /** Значения, приведённые к безопасным для читаемости пределам. */
    fun sanitized(): Appearance = copy(
        customHue = customHue.coerceIn(0f, 360f),
        glassOpacity = glassOpacity.coerceIn(MIN_GLASS_OPACITY, MAX_GLASS_OPACITY),
        glowStrength = glowStrength.coerceIn(0f, 1f),
        effectIntensity = effectIntensity.coerceIn(0f, 1f),
        focusX = focusX.coerceIn(0f, 1f),
        focusY = focusY.coerceIn(0f, 1f),
        zoom = zoom.coerceIn(1f, 3f),
        dim = dim.coerceIn(MIN_DIM, MAX_DIM),
        blur = blur.coerceIn(0f, 1f),
    )
}

data class DownloadPrefs(
    val splitTracks: Boolean = false,
    /** content:// дерева SAF для готовых видео или '' — внутри NOX. */
    val destinationTree: String = "",
    val destinationLabel: String = "",
    val defaultQuality: String = "480",
)

data class PlayerPrefs(
    /** Уходить в «картинку в картинке» при выходе из плеера во время воспроизведения. */
    val pipOnLeave: Boolean = true,
)

data class UpdatePrefs(
    val autoCheck: Boolean = true,
    val lastCheckAt: Long = 0,
    val skippedVersionCode: Int = 0,
    /** versionCode, установку которого запустили; 0 — ничего не ждём. */
    val pendingInstallVersionCode: Int = 0,
    /** Последний versionCode, под которым работало приложение. */
    val lastRunVersionCode: Int = 0,
)

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("nox_settings", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(readAppearance())
    val appearance: StateFlow<Appearance> = _appearance

    private val _downloads = MutableStateFlow(readDownloads())
    val downloads: StateFlow<DownloadPrefs> = _downloads

    private val _player = MutableStateFlow(readPlayer())
    val player: StateFlow<PlayerPrefs> = _player

    private val _updates = MutableStateFlow(readUpdates())
    val updates: StateFlow<UpdatePrefs> = _updates

    // ---------------- оформление ----------------

    private fun readAppearance() = Appearance(
        preset = prefs.getString("preset", "classic") ?: "classic",
        customHue = prefs.getFloat("customHue", 245f),
        glassMode = GlassMode.of(prefs.getString("glassMode", null)),
        glassOpacity = prefs.getFloat("glassOpacity", 0.55f),
        glowStrength = prefs.getFloat("glowStrength", 0.6f),
        effectIntensity = prefs.getFloat("effectIntensity", 0.7f),
        reduceMotion = prefs.getBoolean("reduceMotion", false),
        wallpaper = WallpaperKind.of(prefs.getString("wallpaper", null)),
        focusX = prefs.getFloat("focusX", 0.5f),
        focusY = prefs.getFloat("focusY", 0.5f),
        zoom = prefs.getFloat("zoom", 1f),
        dim = prefs.getFloat("dim", 0.45f),
        blur = prefs.getFloat("wpBlur", 0f),
        wallpaperVersion = prefs.getLong("wallpaperVersion", 0),
    ).sanitized()

    fun updateAppearance(transform: (Appearance) -> Appearance) {
        val next = transform(_appearance.value).sanitized()
        prefs.edit()
            .putString("preset", next.preset)
            .putFloat("customHue", next.customHue)
            .putString("glassMode", next.glassMode.key)
            .putFloat("glassOpacity", next.glassOpacity)
            .putFloat("glowStrength", next.glowStrength)
            .putFloat("effectIntensity", next.effectIntensity)
            .putBoolean("reduceMotion", next.reduceMotion)
            .putString("wallpaper", next.wallpaper.key)
            .putFloat("focusX", next.focusX)
            .putFloat("focusY", next.focusY)
            .putFloat("zoom", next.zoom)
            .putFloat("dim", next.dim)
            .putFloat("wpBlur", next.blur)
            .putLong("wallpaperVersion", next.wallpaperVersion)
            .apply()
        _appearance.value = next
    }

    fun resetAppearance() = updateAppearance { Appearance(wallpaperVersion = it.wallpaperVersion) }

    // ---------------- загрузки ----------------

    private fun readDownloads() = DownloadPrefs(
        splitTracks = prefs.getBoolean("splitTracks", false),
        destinationTree = prefs.getString("destinationTree", "") ?: "",
        destinationLabel = prefs.getString("destinationLabel", "") ?: "",
        defaultQuality = prefs.getString("defaultQuality", "480") ?: "480",
    )

    fun updateDownloads(transform: (DownloadPrefs) -> DownloadPrefs) {
        val next = transform(_downloads.value)
        prefs.edit()
            .putBoolean("splitTracks", next.splitTracks)
            .putString("destinationTree", next.destinationTree)
            .putString("destinationLabel", next.destinationLabel)
            .putString("defaultQuality", next.defaultQuality)
            .apply()
        _downloads.value = next
    }

    // ---------------- плеер ----------------

    private fun readPlayer() = PlayerPrefs(pipOnLeave = prefs.getBoolean("pipOnLeave", true))

    fun updatePlayer(transform: (PlayerPrefs) -> PlayerPrefs) {
        val next = transform(_player.value)
        prefs.edit().putBoolean("pipOnLeave", next.pipOnLeave).apply()
        _player.value = next
    }

    // ---------------- обновления ----------------

    private fun readUpdates() = UpdatePrefs(
        autoCheck = prefs.getBoolean("updAuto", true),
        lastCheckAt = prefs.getLong("updLastCheck", 0),
        skippedVersionCode = prefs.getInt("updSkipped", 0),
        pendingInstallVersionCode = prefs.getInt("updPending", 0),
        lastRunVersionCode = prefs.getInt("updLastRun", 0),
    )

    fun updateUpdates(transform: (UpdatePrefs) -> UpdatePrefs) {
        val next = transform(_updates.value)
        // commit(), а не apply(): перед установкой APK процесс будет убит,
        // и отметка о ждущем обновлении обязана лечь на диск сразу.
        prefs.edit()
            .putBoolean("updAuto", next.autoCheck)
            .putLong("updLastCheck", next.lastCheckAt)
            .putInt("updSkipped", next.skippedVersionCode)
            .putInt("updPending", next.pendingInstallVersionCode)
            .putInt("updLastRun", next.lastRunVersionCode)
            .commit()
        _updates.value = next
    }

    /** Все настройки как простая карта — для резервной копии. */
    fun exportAll(): Map<String, Any?> = prefs.all.toSortedMap()

    /** Восстановить из резервной копии только известные ключи и типы. */
    fun importAll(values: Map<String, Any?>) {
        val known = prefs.all
        val e = prefs.edit()
        for ((k, v) in values) {
            if (k.startsWith("upd")) continue         // состояние обновлений не переносим
            if (k == "destinationTree" || k == "destinationLabel") continue // доступ к папке не переносится
            val current = known[k]
            when {
                v is Boolean && (current == null || current is Boolean) -> e.putBoolean(k, v)
                v is Number && current is Float -> e.putFloat(k, v.toFloat())
                v is Number && current is Long -> e.putLong(k, v.toLong())
                v is Number && current is Int -> e.putInt(k, v.toInt())
                v is Number && current == null && k in FLOAT_KEYS -> e.putFloat(k, v.toFloat())
                v is String && (current == null || current is String) -> e.putString(k, v)
            }
        }
        e.apply()
        _appearance.value = readAppearance()
        _downloads.value = readDownloads()
        _player.value = readPlayer()
    }

    companion object {
        private val FLOAT_KEYS = setOf("customHue", "glassOpacity", "glowStrength", "effectIntensity",
            "focusX", "focusY", "zoom", "dim", "wpBlur")
    }
}
