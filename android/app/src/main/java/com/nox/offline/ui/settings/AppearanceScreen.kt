package com.nox.offline.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.settings.GlassMode
import com.nox.offline.settings.WallpaperKind
import com.nox.offline.ui.MainViewModel
import com.nox.offline.ui.NoxActions
import com.nox.offline.ui.components.GlassButton
import com.nox.offline.ui.components.GlassCard
import com.nox.offline.ui.components.GlassIconButton
import com.nox.offline.ui.components.GlassPill
import com.nox.offline.ui.components.HSpace
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.components.Muted
import com.nox.offline.ui.components.NoxProgress
import com.nox.offline.ui.components.QualitySelector
import com.nox.offline.ui.components.SettingSlider
import com.nox.offline.ui.components.SettingSwitch
import com.nox.offline.ui.components.VSpace
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.NoxPalettes
import com.nox.offline.ui.theme.nox
import com.nox.offline.downloader.Quality
import kotlin.math.roundToInt

/**
 * «Оформление». Всё меняется сразу на живом интерфейсе: сам экран и
 * карточка предпросмотра наверху — настоящие компоненты NOX, а не
 * картинка. Смена темы не трогает загрузчик и плеер.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(vm: MainViewModel, actions: NoxActions, contentPadding: PaddingValues) {
    val a by vm.appearance.collectAsState()
    val p = nox()
    val sheets = LocalSheets.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("top") {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", actions.back, size = 48.dp)
                HSpace(14)
                Text("Оформление", color = Nox.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        item("preview") { Preview(Modifier.padding(horizontal = 20.dp)) }

        item("theme") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Title("Цвет")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (preset in NoxPalettes.presets) {
                        Swatch(preset.accent, preset.label, a.preset == preset.id) { vm.updateAppearance { it.copy(preset = preset.id) } }
                    }
                    Swatch(NoxPalettes.hsv(a.customHue, 0.55f, 0.96f), "Свой цвет", a.preset == "custom") {
                        vm.updateAppearance { it.copy(preset = "custom") }
                    }
                }
                VSpace(6)
                Muted(NoxPalettes.presets.firstOrNull { it.id == a.preset }?.label ?: "Свой цвет", size = 14.sp, color = Nox.TextSecondary)
                if (a.preset == "custom") {
                    VSpace(6)
                    HueBar()
                    SettingSlider("Оттенок", a.customHue, { h -> vm.updateAppearance(preview = true) { it.copy(customHue = h) } },
                        range = 0f..360f, valueLabel = "${a.customHue.roundToInt()}°")
                }
            }
        }

        item("wallpaper") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Title("Фон")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (k in WallpaperKind.builtIn) {
                        GlassPill(k.label, accent = a.wallpaper == k, height = 40.dp, textSize = 14.sp,
                            onClick = { vm.updateAppearance { it.copy(wallpaper = k) } })
                    }
                    GlassPill("Своё фото", icon = Icons.Rounded.AddPhotoAlternate, accent = a.wallpaper == WallpaperKind.CUSTOM,
                        height = 40.dp, textSize = 14.sp, onClick = actions.pickWallpaper)
                }
                if (a.wallpaper == WallpaperKind.CUSTOM) {
                    VSpace(10)
                    Muted("Кадрирование: сдвиньте и увеличьте фото. Копия хранится внутри NOX — исходное фото можно удалить.",
                        size = 13.sp, color = Nox.TextSecondary)
                    SettingSlider("По горизонтали", a.focusX, { v -> vm.updateAppearance(true) { it.copy(focusX = v) } })
                    SettingSlider("По вертикали", a.focusY, { v -> vm.updateAppearance(true) { it.copy(focusY = v) } })
                    SettingSlider("Масштаб", a.zoom, { v -> vm.updateAppearance(true) { it.copy(zoom = v) } }, range = 1f..3f,
                        valueLabel = "×${"%.1f".format(a.zoom)}")
                }
                SettingSlider("Затемнение", a.dim, { v -> vm.updateAppearance(true) { it.copy(dim = v) } },
                    range = com.nox.offline.settings.Appearance.MIN_DIM..com.nox.offline.settings.Appearance.MAX_DIM,
                    valueLabel = "${(a.dim * 100).roundToInt()}%")
                SettingSlider("Размытие фона", a.blur, { v -> vm.updateAppearance(true) { it.copy(blur = v) } },
                    valueLabel = "${(a.blur * 100).roundToInt()}%")
                Muted("Слишком светлый фон NOX затемняет сам — текст остаётся читаемым на любой картинке.", size = 12.sp)
                if (a.wallpaper == WallpaperKind.CUSTOM) {
                    VSpace(6)
                    GlassPill("Убрать своё фото", onClick = vm::clearCustomWallpaper, height = 40.dp, textSize = 14.sp)
                }
            }
        }

        item("glass") {
            GlassCard(Modifier.padding(horizontal = 20.dp)) {
                Title("Жидкое стекло")
                // Названия режимов целиком: при нехватке ширины — перенос на вторую строку.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in GlassMode.entries) {
                        GlassPill(m.label, accent = a.glassMode == m, height = 42.dp, textSize = 14.sp,
                            onClick = { vm.updateAppearance { it.copy(glassMode = m) } })
                    }
                }
                VSpace(6)
                Muted(when (a.glassMode) {
                    GlassMode.AUTO -> "Полное стекло там, где устройство его тянет (Android 12+), иначе экономичное."
                    GlassMode.FULL -> "Живое размытие содержимого под панелью и листами, свечение, преломление на Android 13+. На Android 11 и ниже — как экономичное."
                    GlassMode.ECONOMY -> "Стекло берёт заранее размытую копию фона: тот же вид без покадровой нагрузки."
                }, size = 13.sp, color = Nox.TextSecondary)
                SettingSlider("Непрозрачность стекла", a.glassOpacity, { v -> vm.updateAppearance { it.copy(glassOpacity = v) } },
                    range = com.nox.offline.settings.Appearance.MIN_GLASS_OPACITY..com.nox.offline.settings.Appearance.MAX_GLASS_OPACITY,
                    valueLabel = "${(a.glassOpacity * 100).roundToInt()}%")
                SettingSlider("Сила свечения", a.glowStrength, { v -> vm.updateAppearance { it.copy(glowStrength = v) } },
                    valueLabel = "${(a.glowStrength * 100).roundToInt()}%")
                SettingSlider("Интенсивность эффектов", a.effectIntensity, { v -> vm.updateAppearance(true) { it.copy(effectIntensity = v) } },
                    valueLabel = "${(a.effectIntensity * 100).roundToInt()}%")
                SettingSwitch("Уменьшить движение", "Без перетекания капсулы и бегущих бликов", a.reduceMotion,
                    { on -> vm.updateAppearance { it.copy(reduceMotion = on) } })
            }
        }
        item("reset") {
            Box(Modifier.padding(horizontal = 20.dp)) {
                GlassPill("Сбросить оформление", icon = Icons.Rounded.RestartAlt, onClick = {
                    sheets.confirm("Вернуть стандартное оформление?", "Тема, фон и стекло станут как в классическом NOX. Своё фото останется в памяти до замены.",
                        "Сбросить") { vm.resetAppearance() }
                })
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = Nox.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    VSpace(10)
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(NoxPalettes.mix(color, Color.White, 0.35f), color, NoxPalettes.mix(color, Color.Black, 0.45f))))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun HueBar() {
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(
        Brush.horizontalGradient((0..6).map { NoxPalettes.hsv(it * 60f, 0.55f, 0.96f) })))
}

/** Живой предпросмотр: настоящие компоненты на текущем фоне и стекле. */
@Composable
private fun Preview(modifier: Modifier) {
    val p = nox()
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), style = GlassStyles.Card.copy(glow = 0.3f),
        contentPadding = PaddingValues(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(92.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(p.accentDeep, p.glowB, p.bgDeep))))
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text("Предпросмотр", color = Nox.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Muted("Так будут выглядеть карточки и кнопки", size = 13.sp, color = p.accentLight)
                    VSpace(6)
                    NoxProgress(0.42f)
                }
            }
            VSpace(14)
            QualitySelector(Quality.Q480, {})
            VSpace(12)
            GlassButton("Скачать", {}, icon = Icons.Rounded.Download, modifier = Modifier.fillMaxWidth(), height = 52.dp, textSize = 18.sp)
        }
    }
}
