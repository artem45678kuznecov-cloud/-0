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
    /** 0.4.0: янтарная лиса из утверждённых макетов. */
    FOX("fox", "Лиса NOX"),
    AURORA("aurora", "Аврора"),
    MIDNIGHT("midnight", "Полночь"),
    NEBULA("nebula", "Туманность"),
    DEEP("deep", "Глубина"),
    CUSTOM("custom", "Своё фото");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
        val builtIn = listOf(FOX, DEFAULT, AURORA, MIDNIGHT, NEBULA, DEEP)
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
    /** 0.2.x: разрешение раздельных дорожек. С 0.3.0 объединение идёт само, когда его требует выбранный вариант. */
    val splitTracks: Boolean = false,
    /** content:// дерева SAF для готовых видео или '' — внутри NOX. */
    val destinationTree: String = "",
    val destinationLabel: String = "",
    /** 0.2.x: 360 / 480 / 720 / MAX. Читается только для переноса в [preferredHeight]. */
    val defaultQuality: String = "480",
    /**
     * Какое качество выделять заранее в списке вариантов: высота кадра
     * (480, 720, 1080, 1440, 2160) или 0 — наилучшее. Это только
     * предварительный выбор: пользователь видит все варианты и выбирает сам.
     */
    val preferredHeight: Int = 1080,
    /** Выделять заранее готовый файл со звуком, если он есть той же ступени. */
    val preferSingleFile: Boolean = false,
    // ---- 0.4.0 ----
    /** Передавать только по Wi-Fi (или другой сети без лимита трафика). */
    val wifiOnly: Boolean = false,
    /** Сколько загрузок идёт одновременно: 1, 2 или 3. */
    val concurrency: Int = 3,
    /** Общий предел скорости всех загрузок, КБ/с; 0 — без ограничения. */
    val speedLimitKbps: Int = 0,
) {
    companion object {
        val CONCURRENCY_CHOICES = listOf(1, 2, 3)
        /** Ступени предела скорости, КБ/с (0 — без ограничения). */
        val SPEED_CHOICES = listOf(0, 512, 1024, 2048, 5120, 10240)
    }
}

/** Что делать, когда серия или видео закончились. */
enum class AutoNext(val key: String, val label: String) {
    ASK("ask", "Предложить с отсчётом"),
    AUTO("auto", "Сразу следующее"),
    OFF("off", "Не переходить");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: ASK
    }
}

data class PlayerPrefs(
    /** Уходить в «картинку в картинке» при выходе из плеера во время воспроизведения. */
    val pipOnLeave: Boolean = true,
    // ---- 0.4.0 ----
    val autoNext: AutoNext = AutoNext.ASK,
    /** Секунды отсчёта перед следующей серией. */
    val autoNextSeconds: Int = 8,
    /** Размер субтитров, доля от обычного: 0.7…2.0. */
    val subtitleScale: Float = 1f,
    /** Подложка под субтитрами: 0 — нет, 1 — полупрозрачная, 2 — плотная. */
    val subtitleBackground: Int = 1,
    /** Скорость по умолчанию для новых видео не меняется: хранится позиция, не скорость. */
    val lastSpeed: Float = 1f,
)

/** Автоматическая копия записей и настроек (не видео) в выбранную папку. */
data class BackupPrefs(
    val autoEnabled: Boolean = false,
    /** content:// дерева SAF, куда писать копии; '' — не выбрано. */
    val tree: String = "",
    val treeLabel: String = "",
    /** Период в днях: 1 или 7. */
    val periodDays: Int = 7,
    /** Сколько последних автокопий хранить. */
    val keep: Int = 5,
    val lastAt: Long = 0,
    /** '' — последняя попытка удалась; иначе понятная причина. */
    val lastError: String = "",
) {
    companion object {
        val PERIOD_CHOICES = listOf(1, 7)
        val KEEP_CHOICES = listOf(3, 5, 10)
    }
}

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

    private val _backup = MutableStateFlow(readBackup())
    val backup: StateFlow<BackupPrefs> = _backup

    init {
        settleAppearanceDefaults()
    }

    /**
     * 0.4.0: новый контрольный вид — «Лиса NOX» и «Янтарь» — только для новой
     * установки. У того, кто обновляется, прежние обои и цвет записываются
     * явно и не меняются (раньше значения по умолчанию могли не храниться).
     */
    private fun settleAppearanceDefaults() {
        if (prefs.contains(APPEARANCE_V4)) return
        val fresh = prefs.all.isEmpty()
        val e = prefs.edit().putBoolean(APPEARANCE_V4, true)
        if (fresh) {
            e.putString("wallpaper", WallpaperKind.FOX.key).putString("preset", "amber")
        } else {
            if (!prefs.contains("wallpaper")) e.putString("wallpaper", WallpaperKind.DEFAULT.key)
            if (!prefs.contains("preset")) e.putString("preset", "classic")
        }
        e.commit()
        _appearance.value = readAppearance()
    }

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
        preferredHeight = if (prefs.contains("preferredHeight")) prefs.getInt("preferredHeight", 1080)
        else legacyHeight(prefs.getString("defaultQuality", null)),
        preferSingleFile = prefs.getBoolean("preferSingleFile", false),
        wifiOnly = prefs.getBoolean("wifiOnly", false),
        concurrency = prefs.getInt("concurrency", 3).coerceIn(1, 3),
        speedLimitKbps = prefs.getInt("speedLimitKbps", 0).coerceAtLeast(0),
    )

    /** Перенос выбора 0.2.x: прежняя кнопка качества становится предпочтением. */
    private fun legacyHeight(q: String?): Int = when (q) {
        null -> 1080
        "MAX" -> 0
        else -> q.toIntOrNull() ?: 1080
    }

    fun updateDownloads(transform: (DownloadPrefs) -> DownloadPrefs) {
        val next = transform(_downloads.value)
        prefs.edit()
            .putBoolean("splitTracks", next.splitTracks)
            .putString("destinationTree", next.destinationTree)
            .putString("destinationLabel", next.destinationLabel)
            .putString("defaultQuality", next.defaultQuality)
            .putInt("preferredHeight", next.preferredHeight)
            .putBoolean("preferSingleFile", next.preferSingleFile)
            .putBoolean("wifiOnly", next.wifiOnly)
            .putInt("concurrency", next.concurrency.coerceIn(1, 3))
            .putInt("speedLimitKbps", next.speedLimitKbps.coerceAtLeast(0))
            .apply()
        _downloads.value = next.copy(concurrency = next.concurrency.coerceIn(1, 3))
    }

    // ---------------- плеер ----------------

    private fun readPlayer() = PlayerPrefs(
        pipOnLeave = prefs.getBoolean("pipOnLeave", true),
        autoNext = AutoNext.of(prefs.getString("autoNext", null)),
        autoNextSeconds = prefs.getInt("autoNextSeconds", 8).coerceIn(3, 30),
        subtitleScale = prefs.getFloat("subtitleScale", 1f).coerceIn(0.7f, 2f),
        subtitleBackground = prefs.getInt("subtitleBackground", 1).coerceIn(0, 2),
        lastSpeed = prefs.getFloat("lastSpeed", 1f).coerceIn(0.25f, 3f),
    )

    fun updatePlayer(transform: (PlayerPrefs) -> PlayerPrefs) {
        val next = transform(_player.value)
        prefs.edit()
            .putBoolean("pipOnLeave", next.pipOnLeave)
            .putString("autoNext", next.autoNext.key)
            .putInt("autoNextSeconds", next.autoNextSeconds.coerceIn(3, 30))
            .putFloat("subtitleScale", next.subtitleScale.coerceIn(0.7f, 2f))
            .putInt("subtitleBackground", next.subtitleBackground.coerceIn(0, 2))
            .putFloat("lastSpeed", next.lastSpeed.coerceIn(0.25f, 3f))
            .apply()
        _player.value = readPlayer()
    }

    // ---------------- автокопия ----------------

    private fun readBackup() = BackupPrefs(
        autoEnabled = prefs.getBoolean("autoBackup", false),
        tree = prefs.getString("autoBackupTree", "") ?: "",
        treeLabel = prefs.getString("autoBackupLabel", "") ?: "",
        periodDays = prefs.getInt("autoBackupDays", 7).let { if (it in BackupPrefs.PERIOD_CHOICES) it else 7 },
        keep = prefs.getInt("autoBackupKeep", 5).coerceIn(1, 20),
        lastAt = prefs.getLong("autoBackupLastAt", 0),
        lastError = prefs.getString("autoBackupError", "") ?: "",
    )

    fun updateBackup(transform: (BackupPrefs) -> BackupPrefs) {
        val next = transform(_backup.value)
        prefs.edit()
            .putBoolean("autoBackup", next.autoEnabled)
            .putString("autoBackupTree", next.tree)
            .putString("autoBackupLabel", next.treeLabel)
            .putInt("autoBackupDays", next.periodDays)
            .putInt("autoBackupKeep", next.keep)
            .putLong("autoBackupLastAt", next.lastAt)
            .putString("autoBackupError", next.lastError)
            .commit()
        _backup.value = readBackup()
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
            // Состояние обновлений, права на папки и служебные отметки не переносим.
            if (!isBackupKey(k)) continue
            val current = known[k]
            when {
                v is Boolean && (current == null || current is Boolean) -> e.putBoolean(k, v)
                v is Number && current is Float -> e.putFloat(k, v.toFloat())
                v is Number && current is Long -> e.putLong(k, v.toLong())
                v is Number && current is Int -> e.putInt(k, v.toInt())
                v is Number && current == null && k in FLOAT_KEYS -> e.putFloat(k, v.toFloat())
                v is Number && current == null && k in INT_KEYS -> e.putInt(k, v.toInt())
                v is String && (current == null || current is String) -> e.putString(k, v)
            }
        }
        e.apply()
        _appearance.value = readAppearance()
        _downloads.value = readDownloads()
        _player.value = readPlayer()
    }

    companion object {
        private const val APPEARANCE_V4 = "appearanceV4"

        /**
         * Попадает ли ключ настроек в резервную копию. Не попадают: состояние
         * обновлений, доступ к папкам (права SAF на другом устройстве не
         * действуют), служебные отметки автокопии.
         */
        fun isBackupKey(k: String): Boolean =
            !k.startsWith("upd") && k != "destinationTree" && k != "destinationLabel" &&
                !k.startsWith("autoBackup") && k != APPEARANCE_V4

        private val FLOAT_KEYS = setOf("customHue", "glassOpacity", "glowStrength", "effectIntensity",
            "focusX", "focusY", "zoom", "dim", "wpBlur", "subtitleScale", "lastSpeed")
        private val INT_KEYS = setOf("preferredHeight", "concurrency", "speedLimitKbps", "autoNextSeconds", "subtitleBackground")
    }
}
