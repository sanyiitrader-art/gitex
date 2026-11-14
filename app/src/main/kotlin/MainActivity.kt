package app.gitex

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readNotificationIntent(intent)
        setContent { GitexTheme { GitexRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNotificationIntent(intent)
    }

    /** Notification buttons open this activity with extras telling which screen and which build to show. */
    private fun readNotificationIntent(i: Intent?) {
        val route = i?.getStringExtra("route") ?: return
        NavBus.pending = NavRequest(route, i.getStringExtra("repo"), i.getLongExtra("run", 0L))
        i.removeExtra("route")
    }
}

enum class Route {
    Onboarding, Welcome, Main, SearchFile, SearchProject,
    Settings, SetEditor, SetAppearance, SetTheme, SetGithub, SetAi, SetStore, Recycle, Actions, Log
}

@Composable
fun GitexRoot() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val ws: Workspace = viewModel()
    val handle = remember { EditorHandle() }
    val explorer = remember { ExplorerState() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reconnecting by remember { mutableStateOf(false) }

    val dark = Gx.isDark
    SideEffect {
        val w = (view.context as Activity).window
        val c = WindowCompat.getInsetsController(w, view)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }

    val start = when {
        !Prefs.onboardingDone || !hasAllFilesAccess(ctx) -> Route.Onboarding
        !Prefs.welcomeDone -> Route.Welcome
        else -> Route.Main
    }
    val stack = remember { mutableStateListOf(start) }
    val route = stack.last()
    fun go(r: Route) { stack.add(r) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun reset(r: Route) { stack.clear(); stack.add(r) }

    BackHandler(enabled = stack.size > 1) { back() }

    LaunchedEffect(route == Route.Main) { if (route == Route.Main) ws.restore() }

    // Opened from a build notification: jump straight to the right screen.
    val pending = NavBus.pending
    LaunchedEffect(pending) {
        val p = NavBus.pending ?: return@LaunchedEffect
        NavBus.pending = null
        if (!Prefs.onboardingDone || !Prefs.welcomeDone) return@LaunchedEffect
        stack.clear()
        stack.add(Route.Main)
        when (p.route) {
            "log" -> {
                ActionsNav.tab = 2
                ActionsNav.logRepo = p.repo
                ActionsNav.logRunId = p.runId
                stack.add(Route.Actions)
                stack.add(Route.Log)
            }
            "artifacts" -> {
                ActionsNav.tab = 0
                ActionsNav.highlightRun = p.runId
                stack.add(Route.Actions)
            }
            else -> stack.add(Route.Actions)
        }
    }

    // Duplicate-content check: a few seconds after the project or its files change.
    LaunchedEffect(ws.treeVersion, ws.projectRoot, Prefs.duplicateWarnings) {
        if (ws.projectRoot == null || !Prefs.onboardingDone) return@LaunchedEffect
        delay(4000)
        Duplicates.scan(ws)
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && Prefs.onboardingDone) {
                scope.launch { ws.refreshFromDisk() }
                BuildState.refreshTick++
                val exp = Prefs.tokenExpiresAt
                if (Prefs.githubConnected && exp in 1..System.currentTimeMillis()) {
                    AuthBus.expire("The time GitHub set for this sign-in has passed.")
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val dir = Fs.pathFromTree(uri)
            if (dir == null) {
                Banners.show("Gitex can't reach that location. Choose a folder in your phone's storage.", BannerKind.Error)
            } else {
                ws.setProject(dir)
                explorer.reset()
                StorePrefs.markOpened(dir.absolutePath)
                Banners.show("Opened ${dir.name}")
            }
        }
    }
    val storeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val dir = Fs.pathFromTree(uri)
            if (dir == null) {
                Banners.show("Gitex can't reach that location. Choose a folder in your phone's storage.", BannerKind.Error)
            } else {
                StoreState.pending = dir // the Project Store screen explains it and waits for "Agree"
            }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val f = withContext(Dispatchers.IO) { Fs.pathFromDoc(ctx, uri) }
                if (f == null) Banners.show("Gitex can't reach that file. Pick one from your phone's storage.", BannerKind.Error)
                else openPath(ctx, ws, f.absolutePath)
            }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ReplaceState.picked = uri else ReplaceState.target = null
    }

    DeadTapHost {
        Crossfade(route, animationSpec = tween(120), label = "route") { r ->
            when (r) {
                Route.Onboarding -> OnboardingScreen {
                    Prefs.onboardingDone = true
                    reset(if (Prefs.welcomeDone) Route.Main else Route.Welcome)
                }
                Route.Welcome -> WelcomeScreen {
                    Prefs.welcomeDone = true
                    reset(Route.Main)
                }
                Route.Main -> MainScreen(
                    ws, handle, explorer,
                    onNavigate = { go(it) },
                    onPickFolder = { folderLauncher.launch(null) },
                    onPickFile = { fileLauncher.launch(arrayOf("*/*")) },
                    onPickImage = { imageLauncher.launch(arrayOf("image/*")) },
                )
                Route.SearchFile -> SearchInFileScreen(ws, onClose = { back() }) { line, col, len ->
                    ws.current?.let { handle.pendingJump = EditorHandle.Jump(it, line, col, len) }
                    back()
                }
                Route.SearchProject -> FindInProjectScreen(ws, onClose = { back() }) { path, line, col, len ->
                    scope.launch {
                        if (openPath(ctx, ws, path)) {
                            handle.pendingJump = EditorHandle.Jump(path, line, col, len)
                            back()
                        }
                    }
                }
                Route.Settings -> SettingsHomeScreen({ back() }, { go(it) })
                Route.SetEditor -> EditorSettingsScreen { back() }
                Route.SetAppearance -> AppearanceSettingsScreen({ back() }, { go(it) })
                Route.SetTheme -> ThemePickerScreen { back() }
                Route.SetGithub -> GitHubSettingsScreen { back() }
                Route.SetAi -> AiSettingsScreen { back() }
                Route.SetStore -> StoreSettingsScreen(onBack = { back() }, onChoose = { storeLauncher.launch(null) })
                Route.Recycle -> RecycleBinScreen(ws) { back() }
                Route.Actions -> ActionsScreen(ws, onBack = { back() }, onOpenLog = { run ->
                    ActionsNav.logRepo = run.fullName
                    ActionsNav.logRunId = run.id
                    go(Route.Log)
                })
                Route.Log -> FailureLogScreen(ActionsNav.logRepo, ActionsNav.logRunId) { back() }
            }
        }
        BannerHost()

        // GitHub really rejected the saved sign-in: offer a one-tap reconnect from anywhere in the app.
        if (AuthBus.expired) {
            GDialog(
                onDismiss = { AuthBus.expired = false },
                title = "GitHub sign-in ended",
                cancel = DialogBtn("Later", { AuthBus.expired = false }),
                actions = listOf(DialogBtn("Reconnect", { AuthBus.expired = false; reconnecting = true }, primary = true))
            ) {
                GText(AuthBus.reason, style = Ty.small, color = Gx.textDim)
                GText(
                    "Your projects, files and settings are untouched. Reconnecting takes a few seconds.",
                    modifier = Modifier.padding(top = 10.dp), color = Gx.textDim
                )
            }
        }
        GitHubConnectFlow(active = reconnecting) { reconnecting = false }
    }
}

// ---------------------------------------------------------------------------
// Failure log viewer
// ---------------------------------------------------------------------------

private sealed interface FLog {
    data object Loading : FLog
    class Ready(val log: FailedLog) : FLog
    class Err(val msg: String) : FLog
}

/** Both bars are drawn on top of the log with solid backgrounds, and the log is clipped to its own area. */
@Composable
private fun FailureLogScreen(repo: String?, runId: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<FLog>(FLog.Loading) }

    LaunchedEffect(repo, runId) {
        val parts = repo?.split("/")
        if (parts == null || parts.size != 2) { state = FLog.Err("This build can't be found."); return@LaunchedEffect }
        state = FLog.Loading
        state = try {
            FLog.Ready(GitHub.failedStepLog(parts[0], parts[1], runId))
        } catch (e: GitHubException) {
            FLog.Err(e.message ?: "Couldn't load the log")
        }
    }

    val ready = state as? FLog.Ready
    val bgArgb = Gx.bg.toArgb()
    val fg = Gx.text.toArgb()
    val hl = Gx.accent.copy(alpha = 0.35f).toArgb()

    Box(Modifier.fillMaxSize().background(Gx.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().zIndex(2f).background(Gx.bg)) {
                GTopBar(
                    left = { IconBtn(Ico.Close, onClose) },
                    center = { GText("Failure log", style = Ty.heading) },
                    right = {
                        IconBtn(Ico.Copy, onClick = {
                            ready?.let {
                                ctx.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Gitex log", it.log.text))
                                Banners.show("Log copied", BannerKind.Success)
                            }
                        }, enabled = ready != null)
                    }
                )
            }
            when (val s = state) {
                FLog.Loading -> Box(Modifier.weight(1f).fillMaxWidth()) { Box(Modifier.padding(32.dp)) { GSpinner() } }
                is FLog.Err -> Box(Modifier.weight(1f).fillMaxWidth()) { EmptyNote(s.msg) }
                is FLog.Ready -> {
                    Column(Modifier.fillMaxWidth().zIndex(2f).background(Gx.surface).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        GText("${s.log.job} → ${s.log.step}", style = Ty.label, maxLines = 2)
                        if (!s.log.exact) {
                            GText(
                                "Gitex couldn't isolate this step exactly, so the end of the job's log is shown instead.",
                                style = Ty.tiny, color = Gx.textDim
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).zIndex(2f).background(Gx.border))
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize().navigationBarsPadding().interactive(),
                            factory = { c ->
                                val pad = (14 * c.resources.displayMetrics.density).toInt()
                                val tv = TextView(c).apply {
                                    typeface = Typeface.MONOSPACE
                                    textSize = 12f
                                    setTextIsSelectable(true)
                                    setPadding(pad, pad, pad, pad)
                                }
                                ScrollView(c).apply {
                                    clipChildren = true
                                    clipToPadding = true
                                    addView(tv)
                                }
                            },
                            update = { sv ->
                                sv.setBackgroundColor(bgArgb)
                                val tv = sv.getChildAt(0) as TextView
                                tv.setTextColor(fg)
                                tv.highlightColor = hl
                                if (tv.tag != s.log) { tv.tag = s.log; tv.text = s.log.text }
                            }
                        )
                    }
                }
            }
        }
    }
}