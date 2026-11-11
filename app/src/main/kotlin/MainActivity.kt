package app.gitex

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GitexTheme { GitexRoot() } }
    }
}

enum class Route {
    Onboarding, Welcome, Main, SearchFile, SearchProject,
    Settings, SetEditor, SetAppearance, SetTheme, SetGithub, Recycle, Actions
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

    // Light status/navigation icons on dark themes and the reverse.
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

    // Coming back to the app: pick up files changed by other apps.
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && Prefs.onboardingDone) {
                scope.launch { ws.refreshFromDisk() }
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
                Route.Recycle -> RecycleBinScreen(ws) { back() }
                Route.Actions -> ActionsPlaceholder { back() }
            }
        }
        BannerHost()
    }
}

/** Replaced by the real Actions screen (Artifacts, Create, Failed Builds) in Phase 2. */
@Composable
private fun ActionsPlaceholder(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenBar("Actions", onBack)
        EmptyNote("Builds, artifacts and logs arrive in the next build.")
    }
}