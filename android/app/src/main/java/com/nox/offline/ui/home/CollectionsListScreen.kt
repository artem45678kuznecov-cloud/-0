package com.nox.offline.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nox.offline.data.db.CollectionType
import com.nox.offline.ui.Nav
import com.nox.offline.ui.Page
import com.nox.offline.ui.components.LocalSheets
import com.nox.offline.ui.kit.EmptyBlock
import com.nox.offline.ui.kit.RoundButton
import com.nox.offline.ui.kit.ScreenHeading
import com.nox.offline.ui.kit.Section
import com.nox.offline.ui.kit.SectionGap
import com.nox.offline.ui.library.CollectionCard
import com.nox.offline.ui.library.LibraryViewModel

/** «Все» у раздела главной: все коллекции, категории, закреплённые или альбомы (и сериалы) с созданием новой. */
@Composable
fun CollectionsListScreen(kind: String, lib: LibraryViewModel, nav: Nav, padding: PaddingValues) {
    val data by lib.home.collectAsState()
    val sheets = LocalSheets.current
    val actions = collectionActions(lib, nav, sheets)
    val (title, list: List<CollectionCard>) = when (kind) {
        CollectionType.CATEGORY -> "Категории" to data?.categories.orEmpty()
        "pinned" -> "Закреплённые" to data?.pinned.orEmpty()
        "all" -> "Все коллекции" to data?.let { d -> (d.pinned + d.albums + d.categories).distinctBy { it.id } }.orEmpty()
        else -> "Альбомы и сериалы" to data?.albums.orEmpty()
    }
    val createType = if (kind == CollectionType.CATEGORY) CollectionType.CATEGORY else CollectionType.ALBUM
    LazyColumn(contentPadding = padding) {
        item {
            Spacer(Modifier.height(8.dp))
            ScreenHeading(title, "Всего: ${list.size}", onBack = { nav.back() }, trailing = {
                RoundButton(Icons.Rounded.Add, "Создать", { createCollectionSheet(sheets, lib, createType) { id -> nav.open(Page.Collection(id)) } },
                    size = 44.dp, accent = true)
            })
            SectionGap()
            Section(null, contentPadding = PaddingValues(8.dp)) {
                // Пока данные читаются из базы (data == null), «пусто» не показываем.
                if (data != null && list.isEmpty()) {
                    EmptyBlock(Icons.Rounded.CollectionsBookmark, "Пока пусто",
                        if (kind == "pinned") "Закрепите коллекцию через ⋮ — она появится здесь." else "Создайте первую — это займёт секунду.",
                        action = if (kind == "pinned") null else "Создать",
                        onAction = { createCollectionSheet(sheets, lib, createType) { id -> nav.open(Page.Collection(id)) } })
                }
                for (row in list.chunked(2)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in row) CollectionTile(c, Modifier.weight(1f), onOpen = { nav.open(Page.Collection(c.id)) },
                            onMenu = { actions.menu(c) })
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
