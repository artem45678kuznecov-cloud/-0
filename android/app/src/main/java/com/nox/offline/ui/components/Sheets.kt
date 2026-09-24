package com.nox.offline.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.glass
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/** Пункт меню ⋯ в листе действий. */
data class SheetAction(
    val label: String,
    val icon: ImageVector?,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val hint: String? = null,
    val onClick: () -> Unit,
)

private sealed interface SheetSpec {
    class Custom(val title: String?, val content: @Composable ColumnScope.(close: () -> Unit) -> Unit) : SheetSpec
    class Confirm(val title: String, val message: String, val confirm: String, val danger: Boolean, val onConfirm: () -> Unit) : SheetSpec
}

/**
 * Все всплывающие окна NOX — листы, меню ⋯, подтверждения — рисуются
 * внутри основного окна, в том же стекле, что и экраны. Системных белых
 * диалогов и выпадающих меню Material в приложении нет.
 */
@Stable
class SheetController {
    private var spec by mutableStateOf<SheetSpec?>(null)
    private var dialog by mutableStateOf<SheetSpec.Confirm?>(null)

    val isOpen: Boolean get() = spec != null || dialog != null

    fun show(title: String? = null, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        spec = SheetSpec.Custom(title, content)
    }

    fun actions(title: String, subtitle: String? = null, items: List<SheetAction>) {
        show(title) { close ->
            if (subtitle != null) {
                Muted(subtitle, size = 13.sp, color = Nox.TextSecondary, maxLines = 2)
                VSpace(8)
            }
            for (a in items) ActionRow(a) { close(); a.onClick() }
        }
    }

    fun confirm(title: String, message: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit) {
        dialog = SheetSpec.Confirm(title, message, confirm, danger, onConfirm)
    }

    fun close() {
        if (dialog != null) dialog = null else spec = null
    }

    fun closeAll() {
        dialog = null; spec = null
    }

    @Composable
    internal fun Host() {
        val current = spec
        val confirm = dialog
        BackHandler(enabled = current != null || confirm != null) { close() }
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = current != null || confirm != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
                )
            }
            AnimatedVisibility(
                visible = current != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(240)) { it } + fadeIn(),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(),
            ) {
                val shown = remember { mutableStateOf(current) }
                if (current != null) shown.value = current
                val s = shown.value as? SheetSpec.Custom
                if (s != null) SheetBody(s.title) { s.content(this) { spec = null } }
            }
            AnimatedVisibility(
                visible = confirm != null,
                modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(initialScale = 0.92f) + fadeIn(),
                exit = scaleOut(targetScale = 0.95f) + fadeOut(),
            ) {
                val shown = remember { mutableStateOf(confirm) }
                if (confirm != null) shown.value = confirm
                shown.value?.let { ConfirmBody(it) }
            }
        }
    }

    @Composable
    private fun SheetBody(title: String?, content: @Composable ColumnScope.() -> Unit) {
        BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().imePadding()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.9f)
                    .padding(horizontal = 8.dp)
                    .glass(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 22.dp, bottomEnd = 22.dp), GlassStyles.Sheet)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 42.dp, height = 5.dp)
                    .background(Nox.TextMuted.copy(alpha = 0.6f), RoundedCornerShape(3.dp)))
                VSpace(10)
                if (title != null) {
                    Text(title, color = Nox.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    VSpace(10)
                }
                Column(Modifier.verticalScroll(rememberScrollState()), content = content)
                VSpace(8)
            }
        }
    }

    @Composable
    private fun ConfirmBody(c: SheetSpec.Confirm) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .glass(RoundedCornerShape(26.dp), GlassStyles.Sheet)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(22.dp)
        ) {
            Text(c.title, color = Nox.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            VSpace(8)
            Text(c.message, color = Nox.TextSecondary, fontSize = 15.sp, lineHeight = 21.sp)
            VSpace(20)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill("Отмена", onClick = { dialog = null }, modifier = Modifier.weight(1f), height = 48.dp)
                GlassPill(c.confirm, onClick = { dialog = null; c.onConfirm() }, modifier = Modifier.weight(1f),
                    height = 48.dp, danger = c.danger, accent = !c.danger)
            }
        }
    }
}

@Composable
fun ActionRow(a: SheetAction, onClick: () -> Unit) {
    val p = nox()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = a.enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when {
            !a.enabled -> Nox.TextFaint
            a.danger -> Nox.Danger
            else -> Nox.TextPrimary
        }
        if (a.icon != null) {
            Icon(a.icon, null, tint = if (a.danger) Nox.Danger else if (a.enabled) p.accentLight else Nox.TextFaint,
                modifier = Modifier.size(24.dp))
            HSpace(16)
        }
        Column(Modifier.weight(1f)) {
            Text(a.label, color = color, fontSize = 17.sp)
            if (a.hint != null) Muted(a.hint, size = 12.sp, maxLines = 2)
        }
    }
}

val LocalSheets = staticCompositionLocalOf<SheetController> { error("SheetController не предоставлен") }

@Composable
fun SheetHost(controller: SheetController) = controller.Host()
