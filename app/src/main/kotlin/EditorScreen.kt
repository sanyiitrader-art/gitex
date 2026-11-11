package app.gitex

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import java.io.File

/** Opens [path] in the editor. Returns true when it became the current file. */
suspend fun openPath(ctx: Context, ws: Workspace, path: String): Boolean = when (val r = ws.open(path)) {
    OpenResult.Ok -> true
    is OpenResult.External -> { openExternally(ctx, r.file); false }
    is OpenResult.Failed -> { Banners.show(r.msg, BannerKind.Error); false }
}

private class MItem(
    val label: String,
    val toggle: Boolean? = null,
    val enabled: Boolean = true,
    val keepOpen: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun MainMenu(sections: List<Pair<String, List<MItem>>>, onDismiss: () -> Unit) {
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, with(LocalDensity.current) { 46.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        CompositionLocalProvider(LocalInteractive provides null) {
            Column(
                Modifier.width(250.dp).heightIn(max = maxH)
                    .glow(Shapes.card, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f), 14.dp)
                    .clip(Shapes.card).background(Gx.surfaceHigh, Shapes.card).border(1.dp, Gx.border, Shapes.card)
                    .verticalScroll(rememberScrollState()).padding(vertical = 6.dp)
            ) {
                for ((title, items) in sections) {
                    GText(
                        title.uppercase(), style = Ty.tiny.copy(fontWeight = FontWeight.SemiBold), color = Gx.accent,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                    )
                    for (it in items) {
                        Row(
                            Modifier.fillMaxWidth()
                                .pressable(enabled = it.enabled, scale = 0.97f, onTap = { if (!it.keepOpen) onDismiss(); it.onClick() })
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GText(
                                it.label, modifier = Modifier.weight(1f), maxLines = 1,
                                color = if (it.enabled) Gx.text else Gx.textDim.copy(alpha = 0.5f)
                            )
                            if (it.toggle != null) {
                                GIcon(if (it.toggle) Ico.CheckOn else Ico.CheckOff, size = 22.dp, tint = if (it.toggle) Gx.accent else Gx.textDim)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    ws: Workspace,
    handle: EditorHandle,
    st: ExplorerState,
    onNavigate: (Route) -> Unit,
    onPickFolder: () -> Unit,
    onPickFile: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hide = rememberHideKeyboard()

    var drawerOpen by remember { mutableStateOf(false) }
    var recentsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var topH by remember { mutableIntStateOf(0) }
    var panelRect by remember { mutableStateOf<Rect?>(null) }
    val mdOn = remember { mutableStateMapOf<String, Boolean>() }

    var dReplace by remember { mutableStateOf(false) }
    var dGoto by remember { mutableStateOf(false) }
    var dRepo by remember { mutableIntStateOf(0) }            // 0 none, 1 edit, 2 push, 3 commit
    var dCloseFile by remember { mutableStateOf<String?>(null) }
    var dFolderGuard by remember { mutableStateOf(false) }
    var dGit by remember { mutableStateOf<Boolean?>(null) }   // null none, false push, true commit
    var dConflict by remember { mutableStateOf<String?>(null) }

    val cur = ws.current
    val buf = ws.buffer(cur)
    val isMd = cur != null && (cur.endsWith(".md", true) || cur.endsWith(".markdown", true))
    val mdMode = isMd && mdOn[cur] == true
    val textFile = buf != null && buf.kind == FileKind.TEXT
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    fun closeDrawer() { st.cancel(); st.searching = false; drawerOpen = false }

    BackHandler(drawerOpen) { closeDrawer() }
    BackHandler(recentsOpen && !drawerOpen) { recentsOpen = false }

    // ---- saving ----
    fun report(r: SaveResult) {
        when (r) {
            SaveResult.Saved -> Banners.show("Saved", BannerKind.Success)
            is SaveResult.Conflict -> dConflict = r.path
            is SaveResult.Failed -> Banners.show("Couldn't save: ${r.msg}", BannerKind.Error)
            SaveResult.NoChange -> {}
        }
    }

    /** Saves everything; calls [then] only if all saves went through. */
    fun saveAllThen(then: () -> Unit) {
        scope.launch {
            val r = ws.saveAll()
            when {
                r.conflicts.isNotEmpty() -> dConflict = r.conflicts.first()
                r.failed.isNotEmpty() -> Banners.show("Couldn't save ${r.failed.size} file(s)", BannerKind.Error)
                else -> then()
            }
        }
    }

    fun requestClose(path: String) {
        if (ws.dirty[path] == true) dCloseFile = path else scope.launch { ws.close(path) }
    }

    fun guardedOpenFolder() {
        if (ws.hasDirty()) dFolderGuard = true else onPickFolder()
    }

    // ---- git entry points (Phase 2 performs the actual commit/push) ----
    fun runGit(commit: Boolean) {
        Banners.show(if (commit) "Commit arrives in the next build" else "Push arrives in the next build")
    }

    fun startGit(commit: Boolean) {
        if (!Prefs.githubConnected) { connecting = true; return }
        val root = ws.projectRoot ?: run { Banners.show("Open a project folder first", BannerKind.Error); return }
        if (Prefs.repoLink(root.absolutePath) == null) { dRepo = if (commit) 3 else 2; return }
        if (ws.hasDirty()) { dGit = commit; return }
        runGit(commit)
    }

    // ---- layout ----
    Box(Modifier.fillMaxSize().background(Gx.bg)) {
        // main column (editor area), below the top bar
        Column(
            Modifier.fillMaxSize().padding(top = with(LocalDensity.current) { topH.toDp() })
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        ) {
            // sheetbar
            Row(
                Modifier.fillMaxWidth().height(42.dp).background(Gx.surface).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (cur == null) {
                    GText("No file open", style = Ty.small, color = Gx.textDim, modifier = Modifier.weight(1f))
                } else {
                    GText(File(cur).name, style = Ty.label, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (ws.dirty[cur] == true) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Gx.accent))
                    }
                    if (buf?.externalChange == true) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.pressable(onTap = { dConflict = cur })) {
                            GText("changed on disk", style = Ty.tiny, color = Gx.danger, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (isMd) {
                        IconBtn(
                            Ico.Eye,
                            onClick = { mdOn[cur] = !(mdOn[cur] ?: false) },
                            size = 36.dp, iconSize = 20.dp,
                            tint = if (mdMode) Gx.accent else Gx.textDim,
                            bg = if (mdMode) Gx.selected else null
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    buf == null || cur == null -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        GButton("Select Folder", onClick = { guardedOpenFolder() }, modifier = Modifier.fillMaxWidth(), glow = true)
                        Spacer(Modifier.height(16.dp))
                        GButton("Open file", onClick = onPickFile, modifier = Modifier.fillMaxWidth(), primary = false, glow = true)
                    }
                    buf.kind == FileKind.IMAGE -> ImageViewer(cur)
                    mdMode -> {
                        val text = remember(cur, mdMode, ws.contentVersion) { buf.content?.toString() ?: "" }
                        MarkdownPreview(text)
                    }
                    else -> CodeEditorView(buf, ws, handle, Modifier.fillMaxSize())
                }
            }
            if (imeVisible && textFile && !mdMode) SpecialKeysBar(handle)
        }

        // recent files panel (under the top bar, above the editor)
        Box(Modifier.padding(top = with(LocalDensity.current) { topH.toDp() }).zIndex(1f)) {
            AnimatedVisibility(
                visible = recentsOpen,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(150))
            ) {
                Column(
                    Modifier.fillMaxWidth().onGloballyPositioned { panelRect = it.boundsInRoot() }
                        .background(Gx.surface)
                ) {
                    LazyRow(
                        Modifier.fillMaxWidth().height(56.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(Prefs.recents.toList(), key = { it }) { path ->
                            val name = File(path).name
                            val on = path == cur
                            GCard(
                                Modifier.width(150.dp).height(40.dp),
                                selected = on,
                                onClick = { scope.launch { openPath(ctx, ws, path) } },
                                shape = Shapes.small,
                                padding = PaddingValues(start = 10.dp, end = 2.dp)
                            ) {
                                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                    FileIcon(name, size = 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                    GText(name, style = Ty.small, modifier = Modifier.weight(1f), maxLines = 1)
                                    if (ws.dirty[path] == true) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(Gx.accent))
                                    }
                                    IconBtn(Ico.Close, { requestClose(path) }, size = 32.dp, iconSize = 15.dp, tint = Gx.textDim)
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
                }
            }
        }
        OnDownOutside(recentsOpen, { panelRect }) { recentsOpen = false }

        // top bar (drawn last so the arrow button stays above the panel it pushes down)
        val arrowY by animateDpAsState(if (recentsOpen) 64.dp else 0.dp, tween(200), label = "arrowY")
        Box(Modifier.zIndex(2f).onSizeChanged { topH = it.height }) {
            GTopBar(
                left = { IconBtn(Ico.Menu, { hide(); drawerOpen = true }) },
                center = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GButton(
                            if (Prefs.githubConnected) "Push" else "Connect to github",
                            onClick = { if (Prefs.githubConnected) startGit(false) else connecting = true },
                            height = 40.dp, glow = false
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.offset(y = arrowY).size(40.dp)
                                .pressable(scale = 0.9f, onTap = { recentsOpen = !recentsOpen })
                                .clip(Shapes.small).background(Gx.surfaceHigh).border(1.dp, Gx.border, Shapes.small),
                            contentAlignment = Alignment.Center
                        ) { GIcon(if (recentsOpen) Ico.ArrowUp else Ico.ArrowDown, size = 22.dp) }
                    }
                },
                right = {
                    Box {
                        IconBtn(Ico.MoreVert, { menuOpen = true })
                        if (menuOpen) {
                            val hasText = textFile
                            MainMenu(
                                listOf(
                                    "Files" to listOf(
                                        MItem("Save", enabled = hasText) { scope.launch { cur?.let { report(ws.save(it)) } } },
                                        MItem("Save All", enabled = ws.hasDirty()) {
                                            saveAllThen { Banners.show("All files saved", BannerKind.Success) }
                                        },
                                        MItem("Autosave", toggle = Prefs.autosave, keepOpen = true) { Prefs.autosave = !Prefs.autosave },
                                        MItem("Commit") { startGit(true) },
                                        MItem("Open File") { onPickFile() },
                                        MItem("Open Folder") { guardedOpenFolder() },
                                    ),
                                    "Editor" to listOf(
                                        MItem("Search in file", enabled = hasText) { onNavigate(Route.SearchFile) },
                                        MItem("Find in project", enabled = ws.projectRoot != null) { onNavigate(Route.SearchProject) },
                                        MItem("Replace", enabled = hasText && !mdMode) { dReplace = true },
                                        MItem("Go to line", enabled = hasText && !mdMode) { dGoto = true },
                                        MItem("Repo Name & Branch", enabled = ws.projectRoot != null) { dRepo = 1 },
                                        MItem("Comment / uncomment", enabled = hasText && !mdMode) {
                                            handle.toggleComment()?.let { Banners.show(it, BannerKind.Error) }
                                        },
                                        MItem("Duplicate line", enabled = hasText && !mdMode) { handle.duplicateLine() },
                                        MItem("Delete line", enabled = hasText && !mdMode) { handle.deleteLine() },
                                        MItem("Indent", enabled = hasText && !mdMode) { handle.indentLines() },
                                        MItem("Outdent", enabled = hasText && !mdMode) { handle.outdentLines() },
                                    ),
                                ),
                                onDismiss = { menuOpen = false }
                            )
                        }
                    }
                }
            )
        }

        // drawer
        GitexDrawer(open = drawerOpen, onClose = { closeDrawer() }) {
            ExplorerDrawerContent(
                ws, st,
                onClose = { closeDrawer() },
                onOpenFile = { path ->
                    closeDrawer()
                    scope.launch { openPath(ctx, ws, path) }
                },
                onNavigate = { r -> closeDrawer(); onNavigate(r) },
                onPickFolder = { guardedOpenFolder() },
            )
        }
    }

    // ---- GitHub connect ----
    GitHubConnectFlow(active = connecting) { connecting = false }

    // ---- dialogs ----
    dCloseFile?.let { path ->
        GDialog(
            onDismiss = { dCloseFile = null }, title = "Unsaved changes",
            actions = listOf(
                DialogBtn("Discard", { dCloseFile = null; scope.launch { ws.close(path) } }, danger = true),
                DialogBtn("Save all", { dCloseFile = null; saveAllThen { scope.launch { ws.close(path) } } }, primary = true),
            )
        ) { GText("\"${File(path).name}\" has changes that haven't been saved.", color = Gx.textDim) }
    }
    if (dFolderGuard) {
        GDialog(
            onDismiss = { dFolderGuard = false }, title = "Unsaved changes",
            actions = listOf(
                DialogBtn("Discard", {
                    dFolderGuard = false
                    scope.launch { for (p in ws.dirtyPaths()) ws.close(p); onPickFolder() }
                }, danger = true),
                DialogBtn("Save all", { dFolderGuard = false; saveAllThen { onPickFolder() } }, primary = true),
            )
        ) { GText("Some open files have changes that haven't been saved.", color = Gx.textDim) }
    }
    dGit?.let { commit ->
        GDialog(
            onDismiss = { dGit = null }, title = "Unsaved changes",
            actions = listOf(
                DialogBtn("Save all", { dGit = null; saveAllThen { runGit(commit) } }),
                DialogBtn(if (commit) "Commit" else "Push", { dGit = null; runGit(commit) }, primary = true),
            )
        ) {
            GText(
                "Some files have changes that aren't saved to disk. Save all saves them first; " +
                    "${if (commit) "Commit" else "Push"} uses the content as it is in the editor.",
                color = Gx.textDim
            )
        }
    }
    dConflict?.let { path ->
        GDialog(
            onDismiss = { dConflict = null }, title = "File changed outside Gitex",
            actions = listOf(
                DialogBtn("Reload", { dConflict = null; scope.launch { ws.reloadFromDisk(path) } }, danger = true),
                DialogBtn("Overwrite", { dConflict = null; scope.launch { report(ws.save(path, force = true)) } }, primary = true),
            )
        ) {
            GText(
                "\"${File(path).name}\" was modified by another app after you opened it. " +
                    "Reload discards your edits and loads the disk version. Overwrite saves your version over it.",
                color = Gx.textDim
            )
        }
    }
    if (dReplace) {
        var find by remember { mutableStateOf(TextFieldValue("")) }
        var repl by remember { mutableStateOf(TextFieldValue("")) }
        val go = {
            if (find.text.isNotEmpty()) {
                val n = handle.replaceAll(find.text, repl.text)
                Banners.show(
                    if (n == 0) "Nothing found" else "Replaced $n occurrence${if (n == 1) "" else "s"}",
                    if (n == 0) BannerKind.Info else BannerKind.Success
                )
                dReplace = false
            }
        }
        GDialog(onDismiss = { dReplace = false }, title = "Replace", actions = listOf(DialogBtn("Replace", go, primary = true, enabled = find.text.isNotEmpty()))) {
            GInput(find, { find = it }, Modifier.fillMaxWidth(), placeholder = "Find", imeAction = ImeAction.Next)
            Spacer(Modifier.height(10.dp))
            GInput(repl, { repl = it }, Modifier.fillMaxWidth(), placeholder = "Replace with", onDone = go)
        }
    }
    if (dGoto) {
        var v by remember { mutableStateOf(TextFieldValue("")) }
        var err by remember { mutableStateOf<String?>(null) }
        val go = {
            val n = v.text.trim().toIntOrNull()
            val max = handle.lineCount()
            if (n == null || n < 1 || n > max) err = "Enter a line between 1 and $max"
            else { handle.gotoLine(n); dGoto = false }
        }
        GDialog(onDismiss = { dGoto = false }, title = "Go to line", actions = listOf(DialogBtn("Go", go, primary = true))) {
            err?.let { GText(it, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(bottom = 6.dp)) }
            GInput(v, { v = it; err = null }, Modifier.fillMaxWidth(), placeholder = "Line number", error = err != null,
                keyboardType = KeyboardType.Number, onDone = go)
        }
    }
    if (dRepo != 0) {
        val root = ws.projectRoot
        val existing = root?.let { Prefs.repoLink(it.absolutePath) }
        var repo by remember { mutableStateOf(TextFieldValue(existing?.repo ?: root?.name ?: "")) }
        var branch by remember { mutableStateOf(TextFieldValue(existing?.branch ?: "main")) }
        var err by remember { mutableStateOf<String?>(null) }
        val mode = dRepo
        val save = {
            val e = Validate.repoNameError(repo.text) ?: Validate.branchError(branch.text)
            val owner = Validate.accountName(Prefs.githubUrl)
            if (e != null) err = e
            else if (owner.isBlank() || Prefs.githubUrl.isBlank()) err = "Add your GitHub account URL in Settings → GitHub first"
            else if (root != null) {
                Prefs.setRepoLink(root.absolutePath, RepoLink(owner, repo.text.trim(), branch.text.trim()))
                dRepo = 0
                if (mode == 2) startGit(false) else if (mode == 3) startGit(true)
                else Banners.show("Saved", BannerKind.Success)
            }
        }
        GDialog(onDismiss = { dRepo = 0 }, title = "Repository", actions = listOf(DialogBtn("Save", save, primary = true))) {
            GText("The GitHub repository this project pushes to, and its branch.", style = Ty.small, color = Gx.textDim)
            Spacer(Modifier.height(10.dp))
            err?.let { GText(it, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(bottom = 6.dp)) }
            GInput(repo, { repo = it; err = null }, Modifier.fillMaxWidth(), placeholder = "Repository name", error = err != null, imeAction = ImeAction.Next)
            Spacer(Modifier.height(10.dp))
            GInput(branch, { branch = it; err = null }, Modifier.fillMaxWidth(), placeholder = "Branch", error = err != null, onDone = save)
        }
    }
}