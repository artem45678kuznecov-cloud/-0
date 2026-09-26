package com.nox.offline.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.data.db.CollectionEntity
import com.nox.offline.data.db.CollectionType
import com.nox.offline.ui.LocalPickers
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Page
import com.nox.offline.ui.components.SheetAction
import com.nox.offline.ui.components.SheetController
import com.nox.offline.ui.kit.AmberButton
import com.nox.offline.ui.kit.AmberSwitch
import com.nox.offline.ui.kit.ChoiceRow
import com.nox.offline.ui.kit.KitField
import com.nox.offline.ui.kit.LavenderText
import com.nox.offline.ui.library.CollectionCard
import com.nox.offline.ui.library.CollectionFilter
import com.nox.offline.ui.library.LibraryViewModel
import com.nox.offline.ui.theme.Nox

/** Меню ⋮ коллекции: открыть, изменить, обложка, закрепить, порядок, удалить. */
class CollectionActions(private val lib: LibraryViewModel, private val nav: Nav, private val sheets: SheetController,
                        private val pickImage: ((android.net.Uri) -> Unit) -> Unit) {
    fun menu(c: CollectionCard) = menu(c.summary.entity, c.summary.local)

    fun menu(c: CollectionEntity, local: Int) {
        sheets.actions(c.title, "${local} видео · ${typeLabel(c.type)}", listOfNotNull(
            SheetAction("Открыть", Icons.AutoMirrored.Rounded.ArrowForward) { nav.open(Page.Collection(c.id)) },
            if (!c.isSystem) SheetAction("Изменить", Icons.Rounded.Edit, hint = "Название, описание, метки, вид") {
                editCollectionSheet(sheets, lib, c)
            } else null,
            SheetAction("Обложка из своего изображения", Icons.Rounded.Image) { pickImage { uri -> lib.setCoverImage(c.id, uri) } },
            if (!c.isSystem && c.type != CollectionType.CATEGORY)
                SheetAction(if (c.pinned) "Открепить" else "Закрепить", Icons.Rounded.PushPin,
                    hint = "Закреплённые — отдельный ряд на главной") { lib.setPinned(c.id, !c.pinned) } else null,
            SheetAction("Выше", Icons.Rounded.ArrowUpward) { lib.move(c.id, -1) },
            SheetAction("Ниже", Icons.Rounded.ArrowDownward) { lib.move(c.id, 1) },
            if (!c.isSystem) SheetAction("Удалить коллекцию", Icons.Rounded.Delete, danger = true, hint = "Видео останутся в медиатеке") {
                sheets.confirm("Удалить коллекцию?", "«${c.title}» исчезнет из списка. Сами видео и их позиции останутся в медиатеке.",
                    "Удалить", danger = true) { lib.delete(c.id) }
            } else null,
        ))
    }
}

@Composable
fun collectionActions(lib: LibraryViewModel, nav: Nav, sheets: SheetController): CollectionActions {
    val pickers = LocalPickers.current
    return remember(lib, nav, sheets, pickers) { CollectionActions(lib, nav, sheets) { cb -> pickers.pickImage(cb) } }
}

fun typeLabel(type: String): String = when (type) {
    CollectionType.SERIES -> "сериал"
    CollectionType.CATEGORY -> "категория"
    CollectionType.FAVORITES -> "избранное"
    CollectionType.WATCH_LATER -> "посмотреть позже"
    else -> "альбом"
}

private val typeChoices = listOf(CollectionType.ALBUM to "Альбом", CollectionType.SERIES to "Сериал", CollectionType.CATEGORY to "Категория")

/** Создание коллекции. */
fun createCollectionSheet(sheets: SheetController, lib: LibraryViewModel, initialType: String = CollectionType.ALBUM,
                          onCreated: (Long) -> Unit) {
    sheets.show("Новая коллекция") { close ->
        CollectionForm(null, initialType) { title, type, desc, tags ->
            lib.create(title, type, desc, tags) { id -> onCreated(id) }
            close()
        }
    }
}

fun editCollectionSheet(sheets: SheetController, lib: LibraryViewModel, c: CollectionEntity) {
    sheets.show("Изменить коллекцию") { close ->
        CollectionForm(c, c.type) { title, type, desc, tags ->
            lib.save(c.copy(title = title, type = type, description = desc, tags = tags))
            close()
        }
    }
}

@Composable
private fun ColumnScope.CollectionForm(c: CollectionEntity?, initialType: String, onSave: (String, String, String, String) -> Unit) {
    var title by remember { mutableStateOf(c?.title.orEmpty()) }
    var desc by remember { mutableStateOf(c?.description.orEmpty()) }
    var tags by remember { mutableStateOf(c?.tags.orEmpty()) }
    var type by remember { mutableStateOf(initialType.takeIf { t -> typeChoices.any { it.first == t } } ?: CollectionType.ALBUM) }
    Text("Вид", color = LavenderText, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    ChoiceRow(typeChoices, type, { type = it })
    Text(when (type) {
        CollectionType.SERIES -> "Сезоны и серии, ручной порядок, «Продолжить» с нужной серии."
        CollectionType.CATEGORY -> "Ваша метка: видео в ней не копируются, одно видео может быть в разных метках."
        else -> "Подборка видео в вашем порядке."
    }, color = LavenderText, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
    KitField(title, { title = it }, "Название")
    Spacer(Modifier.height(8.dp))
    KitField(desc, { desc = it }, "Описание (необязательно)", singleLine = false, minHeight = 70.dp)
    Spacer(Modifier.height(8.dp))
    KitField(tags, { tags = it }, "Метки через запятую: Приключения, Фэнтези")
    Spacer(Modifier.height(14.dp))
    AmberButton(if (c == null) "Создать" else "Сохранить", { onSave(title.trim(), type, desc.trim(), tags.trim()) },
        enabled = title.isNotBlank(), height = 50.dp, textSize = 16.sp)
}

/** Фильтры главной: вид, только закреплённые, сортировка; вход во «Все видео» и создание. */
fun showCollectionFilter(sheets: SheetController, lib: LibraryViewModel, nav: Nav) {
    sheets.show("Фильтры") { close ->
        var f by remember { mutableStateOf(lib.filter.value) }
        Text("Показывать", color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        ChoiceRow(listOf(CollectionFilter.ALL to "Все", CollectionType.SERIES to "Сериалы", CollectionType.ALBUM to "Альбомы",
            CollectionType.CATEGORY to "Категории"), f.type, { f = f.copy(type = it) }, textSize = 12.5.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Только закреплённые", color = Nox.TextPrimary, fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp))
            AmberSwitch(f.pinnedOnly, { f = f.copy(pinnedOnly = it) }, label = "Только закреплённые")
        }
        Spacer(Modifier.height(12.dp))
        Text("Порядок", color = LavenderText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        ChoiceRow(CollectionFilter.Sort.entries.take(2).map { it to it.label }, f.sort, { f = f.copy(sort = it) })
        Spacer(Modifier.height(6.dp))
        ChoiceRow(CollectionFilter.Sort.entries.drop(2).map { it to it.label }, f.sort, { f = f.copy(sort = it) })
        Spacer(Modifier.height(16.dp))
        AmberButton("Применить", { lib.filter.value = f; close() }, height = 48.dp, textSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.nox.offline.ui.kit.TileButton("Сбросить", { lib.filter.value = CollectionFilter(); close() }, Modifier.weight(1f))
            com.nox.offline.ui.kit.TileButton("Все видео", { close(); nav.open(Page.AllVideos) }, Modifier.weight(1f),
                icon = Icons.Rounded.VideoLibrary)
        }
        Spacer(Modifier.height(8.dp))
        com.nox.offline.ui.kit.TileButton("Создать коллекцию", {
            close(); createCollectionSheet(sheets, lib) { id -> nav.open(Page.Collection(id)) }
        }, Modifier.fillMaxWidth())
    }
}
