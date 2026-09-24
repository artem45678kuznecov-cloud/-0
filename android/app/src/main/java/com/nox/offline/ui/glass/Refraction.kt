package com.nox.offline.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Лёгкое преломление у кромки стекла (AGSL, API 33+).
 *
 * Ближе к краю формы выборка сдвигается к центру — так ведёт себя край
 * толстой линзы: содержимое под кромкой чуть «затягивается» внутрь.
 * В середине поверхности смещения нет вовсе, поэтому текст под стеклом
 * не искажается. Сначала размытие, потом преломление.
 */
object Refraction {
    private const val AGSL = """
        uniform shader content;
        uniform float4 rect;      // left, top, width, height внутри слоя
        uniform float radius;     // скругление формы
        uniform float strength;   // сдвиг у самой кромки, px

        half4 main(float2 p) {
            float2 h = rect.zw * 0.5;
            float2 c = rect.xy + h;
            float r = min(radius, min(h.x, h.y));
            float2 q = abs(p - c) - (h - float2(r));
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            float band = max(r * 0.9, 6.0);
            float t = clamp(1.0 + d / band, 0.0, 1.0);
            float k = t * t * t;
            float2 dir = p - c;
            float len = max(length(dir), 1.0);
            float2 sp = p - (dir / len) * strength * k;
            return content.eval(sp);
        }
    """

    fun effect(size: Size, pad: Float, corner: Float, strength: Float, blur: Float): RenderEffect {
        if (Build.VERSION.SDK_INT < 33) return BlurEffect(blur, blur, TileMode.Clamp)
        return runCatching { create(size, pad, corner, strength, blur) }
            .getOrElse { BlurEffect(blur, blur, TileMode.Clamp) }
    }

    @RequiresApi(33)
    private fun create(size: Size, pad: Float, corner: Float, strength: Float, blur: Float): RenderEffect {
        val shader = RuntimeShader(AGSL)
        shader.setFloatUniform("rect", pad, pad, size.width, size.height)
        shader.setFloatUniform("radius", corner)
        shader.setFloatUniform("strength", strength)
        val refract = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        val blurFx = android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)
        return android.graphics.RenderEffect.createChainEffect(refract, blurFx).asComposeRenderEffect()
    }
}
