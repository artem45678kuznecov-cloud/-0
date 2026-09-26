package com.nox.offline.player

import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/** Вид своих субтитров: размер и подложка — одинаково в портрете, на весь экран и в PiP. */
@UnstableApi
object SubtitleStyle {
    fun apply(view: SubtitleView, scale: Float, background: Int) {
        val bg = when (background) {
            0 -> Color.TRANSPARENT
            2 -> 0xE6000000.toInt()
            else -> 0x99000000.toInt()
        }
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(CaptionStyleCompat(Color.WHITE, bg, Color.TRANSPARENT,
            if (background == 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
            Color.BLACK, null))
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale.coerceIn(0.7f, 2f))
    }
}
