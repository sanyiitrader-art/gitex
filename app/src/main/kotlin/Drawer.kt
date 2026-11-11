package app.gitex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class XMode { Normal, Create, Rename, Select }

/** Explorer state lives outside the drawer, so expanded folders and search text survive closing it. */
class ExplorerState {
    val expanded = mutableStateListOf<String>()
    var highlighted by mutableStateOf<String?>(null)
    var mode by mutableStateOf(XMode.Normal)
    /** Create: the folder to create in. Rename: the item being renamed. */
    var target by mutableStateOf<File?>(null)
    var createDir by mutableStateOf(false)
    val selected = mutableStateListOf<String>()
    var input by mutableStateOf(TextFieldValue(""))
    var error by mutableStateOf<String?>(null)
    var shake by mutableIntStateOf(0)
    var query by mutableStateOf(TextFieldValue(""))
    var searching by mutableStateOf(false)

    fun cancel() {
        mode = XMode.Normal
        target = null
        selected.clear()
        input = TextFieldValue("")
        error = null
    }

    fun reset() {
        cancel()
        expanded.clear()
        highlighted = null
        query = TextFieldValue("")
        searching = false
    }

    /** Expand every folder above [file] and highlight it. */
    fun reveal(file: File, root: File) {
        val r = root.absolutePath
        var p = file.parentFile
        while (p != null && p.absolutePath.startsWith(r) && p.absolutePath != r) {
            if (p.absolutePath !in expanded) expanded.add(p.absolutePath)
            p = p.parentFile
        }
        highlighted = file.absolutePath
    }
}

private sealed class XRow {
    class Item(val file: File, val depth: Int, val isDir: Boolean, val expanded: Boolean) : XRow()
    class Input(val depth: Int, val isDir: Boolean) : XRow()
}

private fun flatten(root: File, expanded: Set<String>): List<XRow.Item> {
    val out = ArrayList<XRow.Item>()
    fun walk(dir: File, depth: Int) {
        for (f in Fs.listDir(dir)) {
            val isDir = f.isDirectory
            val exp = isDir && f.absolutePath in expanded
            out.add(XRow.Item(f, depth, isDir, exp))
            if (exp) walk(f, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

private fun withInput(rows: List<XRow.Item>, root: File?, st: ExplorerState): List<XRow> {
    if ((st.mode != XMode.Create && st.mode != XMode.Rename) || root == null) return rows
    val parent = (if (st.mode == XMode.Create) st.target else st.target?.parentFile) ?: return rows
    val isDir = if (st.mode == XMode.Create) st.createDir else st.target?.isDirectory == true
    if (parent.absolutePath == root.absolutePath) return listOf<XRow>(XRow.Input(0, isDir)) + rows
    val idx = rows.indexOfFirst { it.file.absolutePath == parent.absolutePath }
    if (idx < 0) return rows
    val out = ArrayList<XRow>(rows.size + 1)
    out.addAll(rows.subList(0, idx + 1))
    out.add(XRow.Input(rows[idx].depth + 1, isDir))
    out.addAll(rows.subList(idx + 1, rows.size))
    return out
}

/** Folders whose every visible file is selected (shown highlighted; the folder itself is not selected). */
private fun fullyChecked(rows: List<XRow.Item>, sel: Set<String>): Set<String> {
    if (sel.isEmpty()) return emptySet()
    val res = HashSet<String>()
    for (i in rows.indices) {
        val r = rows[i]
        if (!r.isDir || !r.expanded) continue
        var files = 0
        var chosen = 0
        var j = i + 1
        while (j < rows.size && rows[j].depth > r.depth) {
            if (!rows[j].isDir) { files++; if (rows[j].file.absolutePath in sel) chosen++ }
            j++
        }
        if (files > 0 && files == chosen) res.add(r.file.absolutePath)
    }
    return res
}

// ---------------------------------------------------------------------------
// Drawer shell: 95% width, scrim, outside-tap and drag-left to close
// ---------------------------------------------------------------------------

@Composable
fun GitexDrawer(open: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelW = maxWidth * 0.95f
        val panelPx = with(LocalDensity.current) { panelW.toPx() }
        val anim = remember(panelPx) { Animatable(-panelPx) }
        var drag by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(open, panelPx) { anim.animateTo(if (open) 0f else -panelPx, tween(220)) }

        val x = (anim.value + drag).coerceIn(-panelPx, 0f)
        if (!(open || x > -panelPx + 1f)) return@BoxWithConstraints
        val progress = 1f - (-x / panelPx)

        suspend fun settle(vx: Float) {
            if (drag == 0f) return
            if (drag < -panelPx * 0.28f || vx < -1200f) {
                anim.snapTo((anim.value + drag).coerceIn(-panelPx, 0f))
                drag = 0f
                onClose()
            } else {
                animate(drag, 0f, animationSpec = tween(160)) { v, _ -> drag = v }
            }
        }

        // The explorer scrolls sideways first; whatever it cannot use drags the drawer shut.
        val conn = remember(panelPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (drag < 0f && available.x > 0f) {
                        val c = minOf(available.x, -drag)
                        drag += c
                        return Offset(c, 0f)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.x < 0f) {
                        drag = (drag + available.x).coerceAtLeast(-panelPx)
                        return Offset(available.x, 0f)
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    settle(available.x)
                    return Velocity.Zero
                }
            }
        }

        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f * progress))
                .pointerInput(Unit) { detectTapGestures { onClose() } }
        )
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                .width(panelW)
                .fillMaxHeight()
                .background(Gx.bg)
                .border(1.dp, Gx.border)
                .nestedScroll(conn)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> drag = (drag + d).coerceIn(-panelPx, 0f) },
                    onDragStopped = { v -> settle(v) }
                )
        ) { content() }
    }
}

// ---------------------------------------------------------------------------
// Drawer content
// ---------------------------------------------------------------------------

@Composable
private fun TextBtn(text: String, color: Color = Gx.accent, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.pressable(enabled = enabled, scale = 0.94f, onTap = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        GText(
            text, style = Ty.label.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = if (enabled) color else Gx.textDim, maxLines = 1
        )
    }
}

private val INDENT = 20.dp

@Composable
private fun InputRow(st: ExplorerState, isDir: Boolean, depth: Int, cardW: Dp, onSave: () -> Unit) {
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(60)
        try { fr.requestFocus() } catch (_: Exception) { }
        kb?.show()
    }
    Column(Modifier.padding(start = INDENT * depth, bottom = 6.dp).width(cardW)) {
        st.error?.let { GText(it, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(bottom = 4.dp)) }
        GInput(
            st.input, { st.input = it; st.error = null }, Modifier.fillMaxWidth(),
            placeholder = if (isDir) "Folder name" else "File name",
            error = st.error != null, shakeKey = st.shake, focusRequester = fr,
            imeAction = ImeAction.Done, onDone = onSave,
            leading = { if (isDir) FolderIcon("", size = 22.dp) else FileIcon(st.input.text, size = 22.dp) }
        )
    }
}

@Composable
fun ExplorerDrawerContent(
    ws: Workspace,
    st: ExplorerState,
    onClose: () -> Unit,
    onOpenFile: (String) -> Unit,
    onNavigate: (Route) -> Unit,
    onPickFolder: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hide = rememberHideKeyboard()
    val root = ws.projectRoot

    var showSearch by remember { mutableStateOf(true) }
    var createPos by remember { mutableStateOf(Offset.Zero) }
    var createMenu by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<Pair<Offset, XRow.Item>?>(null) }
    var delTarget by remember { mutableStateOf<File?>(null) }
    var delSelected by remember { mutableStateOf(false) }
    val searchFr = remember { FocusRequester() }

    OnDeadTap {
        if (st.searching) st.searching = false
        else if (st.mode != XMode.Normal) st.cancel()
        else st.highlighted = null
    }
    LaunchedEffect(st.searching) {
        if (st.searching) {
            delay(60)
            try { searchFr.requestFocus() } catch (_: Exception) { }
        }
    }

    val expKey = st.expanded.toList()
    val rows by produceState(emptyList<XRow.Item>(), root, ws.treeVersion, expKey) {
        value = if (root == null) emptyList() else withContext(Dispatchers.IO) { flatten(root, expKey.toSet()) }
    }
    val display = remember(rows, st.mode, st.target, st.createDir, root) { withInput(rows, root, st) }
    val maxDepth = remember(display) { display.maxOfOrNull { if (it is XRow.Item) it.depth else (it as XRow.Input).depth } ?: 0 }
    val full by remember(rows) { derivedStateOf { fullyChecked(rows, st.selected.toSet()) } }

    fun toggleExpand(item: XRow.Item) {
        val p = item.file.absolutePath
        if (p in st.expanded) {
            st.expanded.remove(p)
            val pre = p + File.separator
            st.selected.removeAll { it.startsWith(pre) }
        } else st.expanded.add(p)
    }

    fun tap(item: XRow.Item) {
        val p = item.file.absolutePath
        if (st.mode == XMode.Create || st.mode == XMode.Rename) { st.cancel(); hide() }
        if (st.mode == XMode.Select) {
            if (item.isDir) toggleExpand(item)
            else if (p in st.selected) st.selected.remove(p) else st.selected.add(p)
            return
        }
        st.highlighted = p
        if (item.isDir) toggleExpand(item) else onOpenFile(p)
    }

    fun startCreate(dir: Boolean) {
        val r = root ?: return
        val h = st.highlighted?.let { File(it) }
        val parent = when {
            h == null -> r
            h.isDirectory -> h
            else -> h.parentFile ?: r
        }
        if (parent.absolutePath != r.absolutePath && parent.absolutePath !in st.expanded) st.expanded.add(parent.absolutePath)
        st.mode = XMode.Create; st.target = parent; st.createDir = dir
        st.input = TextFieldValue(""); st.error = null
    }

    fun startRename(f: File) {
        st.mode = XMode.Rename; st.target = f
        st.input = TextFieldValue(f.name, TextRange(0, f.name.length)); st.error = null
    }

    fun save() {
        val text = st.input.text
        val target = st.target ?: return
        fun fail(msg: String) { st.error = msg; st.shake++; vibrate(ctx, 500) }
        scope.launch {
            if (st.mode == XMode.Create) {
                Fs.nameError(text, target)?.let { return@launch fail(it) }
                val r = withContext(Dispatchers.IO) {
                    if (st.createDir) Fs.createDir(target, text) else Fs.createFile(target, text)
                }
                r.onSuccess { f ->
                    st.cancel(); st.highlighted = f.absolutePath; hide(); ws.bump()
                }.onFailure { fail(Fs.friendly(it)) }
            } else if (st.mode == XMode.Rename) {
                val parent = target.parentFile ?: return@launch
                if (text == target.name) { st.cancel(); hide(); return@launch }
                Fs.nameError(text, parent, target.name)?.let { return@launch fail(it) }
                val r = withContext(Dispatchers.IO) { Fs.rename(target, text) }
                r.onSuccess { nf ->
                    ws.renamed(target, nf)
                    val old = target.absolutePath
                    val nw = nf.absolutePath
                    for (i in st.expanded.indices) {
                        val e = st.expanded[i]
                        if (e == old) st.expanded[i] = nw
                        else if (e.startsWith(old + File.separator)) st.expanded[i] = nw + e.substring(old.length)
                    }
                    st.cancel(); st.highlighted = nw; hide()
                }.onFailure { fail(Fs.friendly(it)) }
            }
        }
    }

    fun deleteFiles(list: List<File>) {
        scope.launch {
            var ok = 0
            for (f in list) {
                val r = withContext(Dispatchers.IO) { Trash.moveToTrash(f) }
                r.onSuccess { ws.deleted(f); if (st.highlighted == f.absolutePath) st.highlighted = null; ok++ }
                    .onFailure { Banners.show(Fs.friendly(it), BannerKind.Error) }
            }
            if (ok > 0) Banners.show(if (ok == 1) "Moved to Recycle Bin" else "$ok items moved to Recycle Bin")
            ws.bump()
            st.cancel()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- top bar ----
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height56().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GText(
                    root?.name ?: "No folder", style = Ty.heading.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1
                )
                when (st.mode) {
                    XMode.Normal -> Box(Modifier.onGloballyPositioned { createPos = it.positionInRoot() }) {
                        TextBtn("Create", enabled = root != null) { createMenu = true }
                    }
                    XMode.Create, XMode.Rename -> {
                        TextBtn("Cancel", Gx.textDim) { st.cancel(); hide() }
                        TextBtn("Save") { save() }
                    }
                    XMode.Select -> {
                        TextBtn("Cancel", Gx.textDim) { st.cancel() }
                        TextBtn("Delete[${st.selected.size}]", Gx.danger, enabled = st.selected.isNotEmpty()) { delSelected = true }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
        }

        // ---- search field (hides while scrolling up) ----
        AnimatedVisibility(
            visible = showSearch || st.searching,
            enter = expandVertically(tween(160)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                if (st.searching) {
                    GInput(
                        st.query, { st.query = it }, Modifier.fillMaxWidth(), placeholder = "Search files",
                        focusRequester = searchFr, imeAction = ImeAction.Search,
                        leading = { GIcon(Ico.Search, size = 20.dp, tint = Gx.textDim) }
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .pressable(scale = 0.98f, onTap = { if (root != null) st.searching = true })
                            .clip(Shapes.small).background(Gx.surfaceHigh).border(1.dp, Gx.border, Shapes.small)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GIcon(Ico.Search, size = 20.dp, tint = Gx.textDim)
                        Spacer(Modifier.width(8.dp))
                        GText(
                            st.query.text.ifEmpty { "Search files" },
                            color = if (st.query.text.isEmpty()) Gx.textDim else Gx.text, maxLines = 1
                        )
                    }
                }
            }
        }

        // ---- explorer ----
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cardW = maxWidth - 24.dp
            val contentW = cardW + INDENT * maxDepth + 12.dp
            val searchConn = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (available.y < -6f) showSearch = false else if (available.y > 6f) showSearch = true
                        return Offset.Zero
                    }
                }
            }
            if (root == null) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    GText("No project folder yet", color = Gx.textDim)
                    Spacer(Modifier.height16())
                    GButton("Select Folder", onClick = { onClose(); onPickFolder() })
                }
            } else {
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).nestedScroll(searchConn)) {
                    LazyColumn(
                        Modifier.width(contentW).fillMaxHeight(),
                        contentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 6.dp, bottom = 12.dp)
                    ) {
                        if (rows.isEmpty() && st.mode == XMode.Normal) {
                            item { GText("This folder is empty", color = Gx.textDim, modifier = Modifier.padding(8.dp)) }
                        }
                        items(
                            display,
                            key = { if (it is XRow.Item) it.file.absolutePath else "__input" }
                        ) { r ->
                            when (r) {
                                is XRow.Input -> InputRow(st, r.isDir, r.depth, cardW, ::save)
                                is XRow.Item -> {
                                    val p = r.file.absolutePath
                                    val sel = when (st.mode) {
                                        XMode.Select -> if (r.isDir) p in full else p in st.selected
                                        else -> st.highlighted == p
                                    }
                                    Box(Modifier.padding(start = INDENT * r.depth, bottom = 6.dp).width(cardW)) {
                                        GCard(
                                            Modifier.fillMaxWidth(),
                                            selected = sel,
                                            onClick = { tap(r) },
                                            onLongPress = { pos -> if (st.mode != XMode.Select) itemMenu = pos to r },
                                            padding = PaddingValues(horizontal = 12.dp, vertical = 11.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (r.isDir) {
                                                    GIcon(if (r.expanded) Ico.ArrowDown else Ico.ChevronRight, size = 20.dp, tint = Gx.textDim)
                                                    Spacer(Modifier.width(8.dp))
                                                    FolderIcon(r.file.name, size = 24.dp)
                                                } else {
                                                    Spacer(Modifier.width(28.dp))
                                                    FileIcon(r.file.name, size = 24.dp)
                                                }
                                                Spacer(Modifier.width(12.dp))
                                                GText(r.file.name, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- floating search results ----
            if (st.searching && root != null) {
                val files by produceState(emptyList<File>(), root, ws.treeVersion) { value = ws.projectFiles() }
                val q = st.query.text.trim()
                val results by produceState(emptyList<File>(), q, files) {
                    value = if (q.isEmpty()) emptyList()
                    else withContext(Dispatchers.Default) { files.filter { it.name.contains(q, ignoreCase = true) }.take(200) }
                }
                Box(
                    Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth().heightIn(max = maxHeight / 2)
                        .interactive().glow(Shapes.card, Color.Black.copy(alpha = 0.6f), 12.dp)
                        .clip(Shapes.card).background(Gx.surface).border(1.dp, Gx.border, Shapes.card)
                ) {
                    when {
                        q.isEmpty() -> EmptyNote("type to search")
                        results.isEmpty() -> EmptyNote("nothing found")
                        else -> LazyColumn(
                            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(results, key = { it.absolutePath }) { f ->
                                GCard(
                                    Modifier.fillMaxWidth(),
                                    onClick = {
                                        st.searching = false
                                        hide()
                                        st.reveal(f, root)
                                        onOpenFile(f.absolutePath)
                                    },
                                    padding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FileIcon(f.name, size = 24.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            GText(f.name, maxLines = 1)
                                            GText(ws.relativeDir(f), style = Ty.tiny, color = Gx.textDim, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- bottom navigation ----
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NavCard(Ico.Wrench, "Actions") { onNavigate(Route.Actions) }
            NavCard(Ico.Gear, "Settings") { onNavigate(Route.Settings) }
        }
    }

    if (createMenu) {
        ContextMenu(
            Offset(createPos.x - 40f, createPos.y + 90f),
            listOf(MenuItem("File") { startCreate(false) }, MenuItem("Folder") { startCreate(true) }),
            onDismiss = { createMenu = false }
        )
    }
    itemMenu?.let { (pos, item) ->
        val menuItems = buildList {
            add(MenuItem("Rename") { startRename(item.file) })
            if (!item.isDir) add(MenuItem("Select") {
                st.cancel(); st.mode = XMode.Select; st.selected.add(item.file.absolutePath)
            })
            add(MenuItem("Delete", danger = true) { delTarget = item.file })
        }
        ContextMenu(pos, menuItems, onDismiss = { itemMenu = null })
    }
    delTarget?.let { f ->
        GDialog(
            onDismiss = { delTarget = null },
            title = if (f.isDirectory) "Delete folder?" else "Delete file?",
            actions = listOf(DialogBtn("Delete", { delTarget = null; deleteFiles(listOf(f)) }, danger = true))
        ) {
            GText(
                if (f.isDirectory)
                    "\"${f.name}\" and everything inside it will be deleted from its current location. The entire folder will be moved into the Recycle Bin."
                else "\"${f.name}\" will be moved to the Recycle Bin.",
                color = Gx.textDim
            )
        }
    }
    if (delSelected) {
        val list = st.selected.map { File(it) }
        GDialog(
            onDismiss = { delSelected = false },
            title = "Delete ${list.size} file${if (list.size == 1) "" else "s"}?",
            actions = listOf(DialogBtn("Delete", { delSelected = false; deleteFiles(list) }, danger = true))
        ) { GText("The selected files will be moved to the Recycle Bin.", color = Gx.textDim) }
    }
}

private fun Modifier.height56(): Modifier = this.then(Modifier.heightIn(min = 56.dp))
private fun Modifier.height16(): Modifier = this.then(Modifier.heightIn(min = 16.dp))

@Composable
private fun NavCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    GCard(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GIcon(icon, tint = Gx.accent)
            Spacer(Modifier.width(16.dp))
            GText(label, style = Ty.heading.copy(fontWeight = FontWeight.Medium))
        }
    }
}