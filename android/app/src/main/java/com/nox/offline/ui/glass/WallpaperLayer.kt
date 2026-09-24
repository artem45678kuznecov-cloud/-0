package com.nox.offline.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nox.offline.ui.theme.nox
import kotlin.math.roundToInt

/**
 * Фон экрана. Пока картинка считается (доли секунды при запуске или
 * смене обоев), рисуется тот же ночной градиент — без белых вспышек.
 */
@Composable
fun WallpaperLayer(frame: WallpaperFrame?, modifier: Modifier = Modifier) {
    val p = nox()
    Canvas(modifier.fillMaxSize()) {
        if (frame == null) {
            drawRect(Brush.verticalGradient(listOf(p.bgTop, p.bg, p.bgDeep)))
        } else {
            drawImage(
                image = frame.display,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(frame.display.width, frame.display.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}
