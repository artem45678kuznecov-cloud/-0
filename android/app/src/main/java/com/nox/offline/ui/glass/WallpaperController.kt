package com.nox.offline.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nox.offline.core.NoxLog
import com.nox.offline.settings.AppSettings
import com.nox.offline.settings.Appearance
import com.nox.offline.settings.WallpaperKind
import com.nox.offline.ui.theme.NoxPalettes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Готовый кадр фона: сама картинка и размытая копия для стекла. */
@Immutable
class WallpaperFrame(
    val display: ImageBitmap,
    val sample: ImageBitmap,
    val width: Int,
    val height: Int,
    val luminance: Float,
    val key: String,
)

/**
 * Держит текущий кадр фона и пересчитывает его, когда меняется тема,
 * обои или размер окна. Живёт на уровне процесса: смена темы не трогает
 * загрузчик и плеер, а поворот экрана не пересоздаёт картинку дважды.
 */
class WallpaperController(private val context: Context, private val settings: AppSettings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _frame = MutableStateFlow<WallpaperFrame?>(null)
    val frame: StateFlow<WallpaperFrame?> = _frame
    private var job: Job? = null
    private var size = 0 to 0

    private val dir: File get() = File(context.filesDir, "wallpaper").apply { mkdirs() }
    val customFile: File get() = File(dir, "custom.jpg")

    fun onWindowSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (size == width to height && _frame.value != null) return
        size = width to height
        request(settings.appearance.value)
    }

    fun request(a: Appearance, preview: Boolean = false) {
        val (w, h) = size
        if (w <= 0) return
        val key = keyOf(a, w, h)
        if (_frame.value?.key == key) return
        job?.cancel()
        job = scope.launch {
            // Короткая пауза: ползунок в «Оформлении» двигают непрерывно,
            // считать каждый промежуточный шаг незачем.
            if (preview) delay(90)
            val palette = NoxPalettes.of(a.preset, a.customHue)
            val started = System.currentTimeMillis()
            val r = try {
                WallpaperRenderer.render(a, palette, w, h, customFile.takeIf { it.exists() }, ::decodeFox)
            } catch (t: Throwable) {
                NoxLog.event("wallpaper-error", "error" to "${t.javaClass.simpleName}: ${t.message?.take(80)}")
                return@launch
            }
            _frame.value = WallpaperFrame(r.display.asImageBitmap(), r.sample.asImageBitmap(), w, h, r.luminance, key)
            NoxLog.event("wallpaper-ready", "kind" to a.wallpaper.key, "ms" to (System.currentTimeMillis() - started),
                "lum" to "%.2f".format(r.luminance), "extraDim" to "%.2f".format(r.extraDim))
        }
    }

    /** Встроенные обои «Лиса NOX» из ресурсов, без масштабирования по плотности. */
    private fun decodeFox(): android.graphics.Bitmap? = runCatching {
        android.graphics.BitmapFactory.decodeResource(context.resources, com.nox.offline.R.drawable.wallpaper_fox,
            android.graphics.BitmapFactory.Options().apply { inScaled = false })
    }.getOrNull()

    private fun keyOf(a: Appearance, w: Int, h: Int): String =
        listOf(a.preset, a.customHue, a.wallpaper.key, a.focusX, a.focusY, a.zoom, a.dim, a.blur,
            a.effectIntensity, a.wallpaperVersion, w, h).joinToString("|")

    /**
     * Своё изображение: декодируется вне главного потока с уменьшением,
     * поворачивается по EXIF и сохраняется локальной копией. После этого
     * исходное фото можно удалить — фон не пропадёт.
     */
    suspend fun importCustom(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // Проход «только размеры» всегда возвращает null — это не ошибка;
            // ошибкой считается лишь невозможность открыть сам поток.
            val boundsStream = resolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Не удалось открыть изображение"))
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext Result.failure(IllegalStateException("Это не изображение"))
            }
            val longSide = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext Result.failure(IllegalStateException("Не удалось декодировать"))
            val rotation = resolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val scale = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
            if (rotation != 0f || scale < 1f) {
                val m = Matrix().apply {
                    if (scale < 1f) postScale(scale, scale)
                    if (rotation != 0f) postRotate(rotation)
                }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }
            val tmp = File(dir, "custom.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            if (!tmp.renameTo(customFile)) {
                tmp.copyTo(customFile, overwrite = true); tmp.delete()
            }
            settings.updateAppearance {
                it.copy(wallpaper = WallpaperKind.CUSTOM, wallpaperVersion = System.currentTimeMillis(),
                    focusX = 0.5f, focusY = 0.5f, zoom = 1f)
            }
            NoxLog.event("wallpaper-import", "w" to customFile.length())
            Result.success(Unit)
        } catch (t: Throwable) {
            NoxLog.event("wallpaper-import-error", "error" to t.javaClass.simpleName, "msg" to t.message?.take(160), "uri" to "${uri.authority}${uri.path}",
                "at" to t.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
            Result.failure(t)
        }
    }

    fun clearCustom() {
        customFile.delete()
        settings.updateAppearance { it.copy(wallpaper = WallpaperKind.DEFAULT, wallpaperVersion = System.currentTimeMillis()) }
    }

    companion object {
        const val MAX_SIDE = 2048
    }
}
