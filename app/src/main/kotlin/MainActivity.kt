package app.gitex

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    Settings, SetEditor, SetAppearance, SetTheme, SetGithub, SetAi, Recycle, Actions, Log
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
                Banners.show("Opened ${dir.name}")
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
                Route.Recycle -> RecycleBinScreen(ws) { back() }
                Route.Actions -> ActionsScreen(ws, onBack = { back() }, onOpenLog = { run ->
                    ActionsNav.logRepo = run.fullName
                    ActionsNav.logRunId = run.id
                    go(Route.Log)
                })
                Route.Log -> LogScreen(ActionsNav.logRepo, ActionsNav.logRunId) { back() }
            }
        }
        BannerHost()
    }
}