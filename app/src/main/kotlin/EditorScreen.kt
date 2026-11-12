package app.gitex

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private const val COMMIT_MESSAGE = "Update from Gitex"

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

/** Menu with a bare scroll thumb in its left gutter, so it is obvious that it scrolls. */
@Composable
private fun MainMenu(sections: List<Pair<String, List<MItem>>>, onDismiss: () -> Unit) {
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    val dens = LocalDensity.current
    val sc = rememberScrollState()
    var viewH by remember { mutableIntStateOf(0) }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, with(dens) { 46.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        CompositionLocalProvider(LocalInteractive provides null) {
            Box(
                Modifier.width(262.dp).heightIn(max = maxH)
                    .glow(Shapes.card, Color.Black.copy(alpha = 0.6f), 14.dp)
                    .clip(Shapes.card).background(Gx.surfaceHigh, Shapes.card).border(1.dp, Gx.border, Shapes.card)
            ) {
                Column(
                    Modifier.fillMaxWidth().onSizeChanged { viewH = it.height }
                        .verticalScroll(sc).padding(start = 14.dp, top = 6.dp, bottom = 6.dp)
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
                if (sc.maxValue > 0 && viewH > 0) {
                    val total = viewH + sc.maxValue
                    val thumbH = (viewH.toFloat() * viewH / total).coerceAtLeast(28f * dens.density)
                    val y = (viewH - thumbH) * sc.value / sc.maxValue
                    Box(
                        Modifier.padding(start = 5.dp)
                            .offset { IntOffset(0, y.roundToInt()) }
                            .width(4.dp).height(with(dens) { thumbH.toDp() })
                            .clip(RoundedCornerShape(50)).background(Gx.accent.copy(alpha = 0.9f))
                    )
                }
            }
        }
    }
}

/**
 * Opens on touch-down instead of on tap-release, and listens on the Initial pass without consuming,
 * so a cancelled or stolen tap can never leave this button dead.
 */
@Composable
private fun MenuButton(onOpen: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) 0.88f else 1f, tween(100), label = "menuBtn")
    val open by rememberUpdatedState(onOpen)
    Box(
        Modifier.size(44.dp).interactive()
            .graphicsLayer { scaleX = s; scaleY = s }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    open()
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                    } while (e.changes.any { it.pressed })
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) { GIcon(Ico.Menu) }
}

@Composable
private fun TopActionButton(
    connected: Boolean, pushState: Int, busy: Boolean, verb: String, status: String,
    onPush: () -> Unit, onCancel: () -> Unit, onConnect: () -> Unit,
) {
    when {
        !connected -> GButton("Connect to github", onConnect, height = 40.dp, glow = false)
        busy -> Column(
            Modifier.heightIn(min = 44.dp).widthIn(min = 120.dp, max = 200.dp)
                .pressable(scale = 0.95f, onTap = onCancel)
                .clip(Shapes.card).background(Gx.accent, Shapes.card)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            GText("Cancel", style = Ty.label, color = Gx.onAccent, maxLines = 1)
            GText(
                if (status.isBlank()) "$verb…" else "$verb · $status",
                style = Ty.tiny.copy(fontSize = 10.sp), color = Gx.onAccent.copy(alpha = 0.85f), maxLines = 1
            )
        }
        pushState == 2 -> GButton("Pushed", {}, enabled = false, height = 40.dp, glow = false)
        else -> GButton("Push", onPush, height = 40.dp, glow = false)
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
    var drawerEpoch by remember { mutableIntStateOf(0) }
    var recentsOpen by remember { mutableStateOf(false) }
    var recentsClosedAt by remember { mutableLongStateOf(0L) }
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

    // Git state: 0 idle, 1 working, 2 "Pushed" (shown for about a second)
    var pushState by remember { mutableIntStateOf(0) }
    var committing by remember { mutableStateOf(false) }
    var gitStatus by remember { mutableStateOf("") }
    var gitJob by remember { mutableStateOf<Job?>(null) }
    var dRejected by remember { mutableStateOf(false) }
    var dForce by remember { mutableStateOf(false) }
    var dMergeConflicts by remember { mutableStateOf<List<String>?>(null) }
    var dLarge by remember { mutableStateOf<Triple<Boolean, Int, Long>?>(null) }
    val largeOk = remember { mutableSetOf<String>() }

    val cur = ws.current
    val buf = ws.buffer(cur)
    val isMd = cur != null && (cur.endsWith(".md", true) || cur.endsWith(".markdown", true))
    val mdMode = isMd && mdOn[cur] == true
    val textFile = buf != null && buf.kind == FileKind.TEXT
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val gitBusy = pushState == 1 || committing

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

    // ---- Git ----
    fun cancelGit() {
        gitJob?.cancel()
        gitJob = null
        pushState = 0
        committing = false
        Banners.show("Cancelled")
    }

    fun runGit(commit: Boolean, force: Boolean = false) {
        val root = ws.projectRoot ?: return
        val link = Prefs.repoLink(root.absolutePath) ?: return
        if (gitBusy) return
        if (commit) committing = true else pushState = 1
        gitStatus = "Starting…"
        gitJob = scope.launch {
            val buffers = ws.dirtyPaths().mapNotNull { p -> ws.buffer(p)?.content?.toString()?.let { p to it } }.toMap()
            var offlineShown = false
            var tries = 0
            var res: GitResult = GitResult.NothingToCommit
            while (true) {
                if (!Net.online(ctx)) {
                    gitStatus = "Waiting for network…"
                    if (!offlineShown) {
                        offlineShown = true
                        Banners.show("No network. Gitex will keep waiting and continue by itself when it is back.")
                    }
                    Net.await(ctx)
                    delay(1200)
                }
                gitStatus = "Starting…"
                res = if (commit) GitOps.commit(root, link, COMMIT_MESSAGE, buffers) { gitStatus = it }
                else GitOps.push(root, link, COMMIT_MESSAGE, buffers, force) { gitStatus = it }
                if (res is GitResult.Offline && ++tries < 40) {
                    gitStatus = "Waiting for network…"
                    if (!offlineShown) {
                        offlineShown = true
                        Banners.show("No network. Gitex will keep waiting and continue by itself when it is back.")
                    }
                    if (Net.online(ctx)) delay(4000) else Net.await(ctx)
                    continue
                }
                break
            }
            val result = res
            committing = false
            when (result) {
                is GitResult.Committed -> Banners.show(
                    "Committed ${result.files} file${if (result.files == 1) "" else "s"} (not pushed yet)" +
                        if (result.skipped.isEmpty()) "" else ", skipped ${result.skipped.size} over 100 MB",
                    BannerKind.Success
                )
                GitResult.NothingToCommit -> Banners.show("Nothing to commit")
                is GitResult.Pushed -> {
                    pushState = 2
                    Banners.show(
                        (if (result.upToDate) "Already up to date" else "Pushed") +
                            if (result.skipped.isEmpty()) "" else " (skipped ${result.skipped.size} file(s) over 100 MB)",
                        BannerKind.Success
                    )
                    if (!result.upToDate && GitOps.hasWorkflows(root)) BuildWatcher.start(ctx, link.owner, link.repo, result.sha, link.branch)
                    delay(1000)
                    pushState = 0
                }
                is GitResult.Rejected -> {
                    pushState = 0
                    Banners.show(if (result.remoteAhead) "Push rejected" else "Push rejected: ${result.detail}", BannerKind.Error)
                    if (result.remoteAhead) dRejected = true
                }
                is GitResult.AuthFailed -> { pushState = 0; Banners.show(result.detail, BannerKind.Error) }
                is GitResult.NotFound -> { pushState = 0; Banners.show(result.detail, BannerKind.Error) }
                is GitResult.Offline -> { pushState = 0; Banners.show(result.detail, BannerKind.Error) }
                is GitResult.Failed -> { pushState = 0; Banners.show(result.detail, BannerKind.Error) }
            }
        }
    }

    /** Warns before the first push of a very large folder, then runs. */
    fun launchGit(commit: Boolean) {
        val root = ws.projectRoot ?: return
        if (gitBusy) return
        if (GitOps.hasCommits(root) || root.absolutePath in largeOk) { runGit(commit); return }
        scope.launch {
            val m = GitOps.measure(root)
            if (m.files > 8000 || m.bytes > 250L * 1024 * 1024) dLarge = Triple(commit, m.files, m.bytes)
            else runGit(commit)
        }
    }

    fun mergeThenPush() {
        val root = ws.projectRoot ?: return
        val link = Prefs.repoLink(root.absolutePath) ?: return
        if (gitBusy) return
        pushState = 1
        gitStatus = "Saving files…"
        gitJob = scope.launch {
            val r = ws.saveAll()
            if (r.conflicts.isNotEmpty()) { pushState = 0; dConflict = r.conflicts.first(); return@launch }
            if (r.failed.isNotEmpty()) { pushState = 0; Banners.show("Couldn't save ${r.failed.size} file(s)", BannerKind.Error); return@launch }
            when (val m = GitOps.fetchAndMerge(root, link, COMMIT_MESSAGE, emptyMap()) { gitStatus = it }) {
                MergeOutcome.Merged, MergeOutcome.UpToDate -> {
                    ws.bump()
                    ws.refreshFromDisk()
                    pushState = 0
                    runGit(false)
                }
                MergeOutcome.NoRemoteBranch -> { pushState = 0; Banners.show("The branch doesn't exist on GitHub yet", BannerKind.Error) }
                is MergeOutcome.Conflicts -> { pushState = 0; dMergeConflicts = m.files }
                is MergeOutcome.Failed -> { pushState = 0; Banners.show(m.detail, BannerKind.Error) }
            }
        }
    }

    fun startGit(commit: Boolean) {
        if (!Prefs.githubConnected) { connecting = true; return }
        val root = ws.projectRoot ?: run { Banners.show("Open a project folder first", BannerKind.Error); return }
        if (Prefs.repoLink(root.absolutePath) == null) { dRepo = if (commit) 3 else 2; return }
        if (ws.hasDirty()) { dGit = commit; return }
        launchGit(commit)
    }

    // ---- layout ----
    Box(Modifier.fillMaxSize().background(Gx.bg)) {
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

        // recent files panel
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
        OnDownOutside(recentsOpen, { panelRect }) {
            recentsOpen = false
            recentsClosedAt = SystemClock.uptimeMillis()
        }

        // top bar
        Box(Modifier.zIndex(2f).onSizeChanged { topH = it.height }) {
            GTopBar(
                left = {
                    MenuButton {
                        // A fresh drawer every time: no stale drag/animation state can survive.
                        drawerEpoch++
                        drawerOpen = true
                        scope.launch {
                            delay(120)
                            try { hide() } catch (_: Throwable) { }
                        }
                    }
                },
                center = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TopActionButton(
                            connected = Prefs.githubConnected, pushState = pushState, busy = gitBusy,
                            verb = if (committing) "Committing" else "Pushing", status = gitStatus,
                            onPush = { startGit(false) }, onCancel = { cancelGit() }, onConnect = { connecting = true }
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(40.dp)
                                .pressable(scale = 0.9f, onTap = {
                                    if (SystemClock.uptimeMillis() - recentsClosedAt > 600L) recentsOpen = !recentsOpen
                                })
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
                                        MItem("Commit", enabled = !gitBusy) { startGit(true) },
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

        // drawer: above everything, rebuilt fresh on every open
        Box(Modifier.fillMaxSize().zIndex(10f)) {
            key(drawerEpoch) {
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
        }
    }

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
                DialogBtn("Save all", { dGit = null; saveAllThen { launchGit(commit) } }),
                DialogBtn(if (commit) "Commit" else "Push", { dGit = null; launchGit(commit) }, primary = true),
            )
        ) {
            GText(
                "Some files have changes that aren't saved to disk. Save all saves them first; " +
                    "${if (commit) "Commit" else "Push"} uses the content as it is in the editor.",
                color = Gx.textDim
            )
        }
    }
    dLarge?.let { (commit, files, bytes) ->
        GDialog(
            onDismiss = { dLarge = null }, title = "This folder is large",
            actions = listOf(DialogBtn(if (commit) "Commit anyway" else "Push anyway", {
                dLarge = null
                ws.projectRoot?.let { largeOk.add(it.absolutePath) }
                runGit(commit)
            }, primary = true))
        ) {
            GText(
                "It holds about $files files (${fmtSize(bytes)}). The first push uploads all of it, which can take very long on a phone. " +
                    "A project folder with only your code is usually better.",
                color = Gx.textDim
            )
        }
    }
    if (dRejected) {
        GDialog(
            onDismiss = { dRejected = false }, title = "GitHub has newer commits",
            actions = listOf(
                DialogBtn("Force push…", { dRejected = false; dForce = true }, danger = true),
                DialogBtn("Merge and push", { dRejected = false; mergeThenPush() }, primary = true),
            )
        ) {
            GText(
                "The branch on GitHub contains commits this phone doesn't have, so the push was rejected and nothing on GitHub was changed. " +
                    "Merge and push brings those commits in first (your unsaved edits are saved before that).",
                color = Gx.textDim
            )
        }
    }
    if (dForce) {
        val b = ws.projectRoot?.let { Prefs.repoLink(it.absolutePath)?.branch } ?: "the branch"
        GDialog(
            onDismiss = { dForce = false }, title = "Force push?",
            actions = listOf(DialogBtn("Force push", { dForce = false; runGit(false, force = true) }, danger = true))
        ) {
            GText(
                "This replaces \"$b\" on GitHub with what is on this phone. Any commits that exist only on GitHub will be lost. " +
                    "Only continue if you are sure.",
                color = Gx.danger
            )
        }
    }
    dMergeConflicts?.let { files ->
        GDialog(
            onDismiss = { dMergeConflicts = null }, title = "Can't merge automatically",
            cancel = DialogBtn("Close", { dMergeConflicts = null }),
            actions = listOf(DialogBtn("Force push…", { dMergeConflicts = null; dForce = true }, danger = true))
        ) {
            GText(
                "GitHub and this phone both changed the same lines in:\n" + files.take(8).joinToString("\n") { "• $it" } +
                    (if (files.size > 8) "\n…and ${files.size - 8} more" else "") +
                    "\n\nNothing was changed. Resolve it on github.com, or force push to replace GitHub's version.",
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
            GText("The GitHub repository this project pushes to, and its branch. A branch that doesn't exist yet is created on the first push.", style = Ty.small, color = Gx.textDim)
            Spacer(Modifier.height(10.dp))
            err?.let { GText(it, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(bottom = 6.dp)) }
            GInput(repo, { repo = it; err = null }, Modifier.fillMaxWidth(), placeholder = "Repository name", error = err != null, imeAction = ImeAction.Next)
            Spacer(Modifier.height(10.dp))
            GInput(branch, { branch = it; err = null }, Modifier.fillMaxWidth(), placeholder = "Branch", error = err != null, onDone = save)
        }
    }
}