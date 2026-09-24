package com.nox.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nox.offline.settings.WallpaperKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Свой фон из фото: декодирование с уменьшением, поворот по EXIF, локальная
 * копия и переключение темы. На вход — content:// (как от Photo Picker),
 * только через FileProvider самого NOX: окно выбора фото в тесте не нужно.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperImportDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = NoxApp.get(ctx)
    private val src = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "NOX/test-wallpaper.jpg")

    @After fun cleanup() {
        src.delete()
        app.wallpaper.clearCustom()
    }

    @Test fun largeRotatedPhotoBecomesDownscaledUprightWallpaper() = runBlocking {
        // 4000×3000 «альбомный» кадр, EXIF говорит «повернуть на 90°».
        src.parentFile!!.mkdirs()
        val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.rgb(20, 30, 90))
            drawRect(0f, 0f, 400f, 3000f, Paint().apply { color = Color.RED })
        }
        src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        ExifInterface(src.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", src)

        val result = app.wallpaper.importCustom(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)

        val out = app.wallpaper.customFile
        assertTrue(out.exists() && out.length() > 0)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, o)
        assertTrue("long side ${maxOf(o.outWidth, o.outHeight)}", maxOf(o.outWidth, o.outHeight) <= 2048)
        assertTrue("rotated to portrait: ${o.outWidth}x${o.outHeight}", o.outHeight > o.outWidth)
        assertEquals(WallpaperKind.CUSTOM, app.settings.appearance.value.wallpaper)
    }

    @Test fun notAnImageIsRejectedWithoutChangingTheme() = runBlocking {
        src.parentFile!!.mkdirs()
        src.writeText("это не картинка")
        val before = app.settings.appearance.value.wallpaper
        val result = app.wallpaper.importCustom(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", src))
        assertTrue(result.isFailure)
        assertEquals(before, app.settings.appearance.value.wallpaper)
    }
}
