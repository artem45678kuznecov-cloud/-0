package com.nox.offline.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

/**
 * Переименование. Для готового видео можно заодно переименовать файл;
 * для идущей загрузки меняется только название — файл получит его при
 * завершении.
 */
@Composable
fun ColumnScope.RenameSheet(initial: String, allowFile: Boolean, onSave: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var renameFile by remember { mutableStateOf(false) }
    GlassSurface(Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp), style = GlassStyles.Field) {
        Box(Modifier.padding(16.dp)) {
            BasicTextField(text, { text = it.take(200) }, textStyle = TextStyle(color = Nox.TextPrimary, fontSize = 17.sp),
                cursorBrush = SolidColor(nox().accentLight),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth())
        }
    }
    VSpace(8)
    if (allowFile) {
        SettingSwitch("Переименовать и файл", "Позиция просмотра сохранится. Если видео открыто в плеере, файл не трогаем.",
            renameFile, { renameFile = it })
    } else {
        Muted("Пока идёт загрузка, меняется только название. Файл получит его при завершении.", size = 13.sp, color = Nox.TextSecondary)
    }
    VSpace(12)
    GlassButton("Сохранить", onClick = { if (text.isNotBlank()) onSave(text.trim(), renameFile) },
        enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth(), height = 52.dp, textSize = 18.sp)
}
