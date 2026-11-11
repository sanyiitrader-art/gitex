package app.gitex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RecycleBinScreen(ws: Workspace, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TrashItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var conflict by remember { mutableStateOf<Pair<TrashItem, File>?>(null) }

    fun reload() {
        scope.launch {
            val l = withContext(Dispatchers.IO) { Trash.list() }
            items = l
            selected.retainAll(l.map { it.id }.toSet())
            loaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun restore(item: TrashItem, keepBoth: Boolean = false) {
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { Trash.restore(item, keepBoth) }) {
                is Trash.Restore.Ok -> {
                    ws.bump()
                    Banners.show("Restored ${r.file.name}", BannerKind.Success)
                    reload()
                }
                is Trash.Restore.Conflict -> conflict = item to r.existing
                is Trash.Restore.Err -> Banners.show(r.msg, BannerKind.Error)
            }
        }
    }

    fun deleteForever(list: List<TrashItem>) {
        scope.launch {
            withContext(Dispatchers.IO) { for (i in list) Trash.deleteForever(i) }
            reload()
        }
    }

    val selecting = selected.isNotEmpty()
    val allSelected = items.isNotEmpty() && selected.size == items.size

    Column(Modifier.fillMaxSize().background(Gx.bg)) {
        ScreenBar(
            "Recycle Bin", onBack,
            right = {
                IconBtn(
                    if (allSelected) Ico.CheckOn else Ico.CheckOff,
                    onClick = {
                        if (allSelected) selected.clear()
                        else { selected.clear(); selected.addAll(items.map { it.id }) }
                    },
                    enabled = items.isNotEmpty(),
                    tint = if (allSelected) Gx.accent else Gx.text
                )
                IconBtn(
                    Ico.Delete,
                    onClick = {
                        val l = items.filter { it.id in selected }
                        selected.clear()
                        deleteForever(l)
                    },
                    enabled = selecting,
                    tint = Gx.danger
                )
            }
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !loaded -> GSpinner(Modifier.align(Alignment.Center))
                items.isEmpty() -> EmptyNote("The Recycle Bin is empty")
                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        GCard(
                            Modifier.fillMaxWidth(),
                            selected = item.id in selected,
                            onClick = {
                                if (selecting) {
                                    if (item.id in selected) selected.remove(item.id) else selected.add(item.id)
                                } else restore(item)
                            },
                            onLongPress = { if (item.id !in selected) selected.add(item.id) },
                            padding = PaddingValues(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.isDir) FolderIcon(item.name, size = 26.dp) else FileIcon(item.name, size = 26.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    GText(item.name, style = Ty.body, maxLines = 1)
                                    GText(
                                        File(item.originalPath).parent ?: "",
                                        style = Ty.tiny, color = Gx.textDim, maxLines = 1
                                    )
                                }
                                IconBtn(Ico.Delete, onClick = { deleteForever(listOf(item)) }, tint = Gx.textDim)
                            }
                        }
                    }
                }
            }
        }
    }

    conflict?.let { (item, existing) ->
        GDialog(
            onDismiss = { conflict = null },
            title = "Can't restore \"${item.name}\"",
            actions = listOf(DialogBtn("Keep both", { conflict = null; restore(item, keepBoth = true) }, primary = true))
        ) {
            GText(
                "\"${existing.name}\" already exists in ${existing.parent}. Nothing was overwritten. " +
                    "Keep both restores this one with \"(restored)\" added to its name.",
                color = Gx.textDim
            )
        }
    }
}