package app.gitex

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Project Store: one folder that holds the user's projects
// ---------------------------------------------------------------------------

/** A folder picked in the system picker, waiting for the user to read the explanation and press Agree. */
object StoreState {
    var pending by mutableStateOf<File?>(null)
}

object StorePrefs {
    private fun str(k: String, d: String) = PrefValue<String>(
        k, d, { s, key, def -> s.getString(key, def) ?: def }, { e, key, v -> e.putString(key, v) })

    private fun bool(k: String, d: Boolean) = PrefValue<Boolean>(
        k, d, { s, key, def -> s.getBoolean(key, def) }, { e, key, v -> e.putBoolean(key, v) })

    var path by str("store_path", "")
    var showOnStart by bool("store_show_on_start", true)
    var showHidden by bool("store_show_hidden", false)
    /** name | opened | changed */
    var sort by str("store_sort", "name")

    private var opened: HashMap<String, Long>? = null

    @Synchronized
    private fun openedMap(): HashMap<String, Long> {
        opened?.let { return it }
        val m = HashMap<String, Long>()
        try {
            val o = JSONObject(Prefs.sp.getString("store_opened", "{}") ?: "{}")
            for (k in o.keys()) m[k] = o.optLong(k)
        } catch (_: Exception) {
        }
        opened = m
        return m
    }

    @Synchronized
    fun markOpened(p: String) {
        val m = openedMap()
        m[p] = System.currentTimeMillis()
        if (m.size > 300) {
            val keep = m.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
            m.clear(); m.putAll(keep)
        }
        try {
            val o = JSONObject()
            for ((k, v) in m) o.put(k, v)
            Prefs.sp.edit().putString("store_opened", o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun openedAt(p: String): Long = openedMap()[p] ?: 0L

    fun dir(): File? = path.takeIf { it.isNotBlank() }?.let { File(it) }

    fun available(): Boolean {
        val d = dir() ?: return false
        return d.isDirectory && d.canRead()
    }
}

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Gx.bg)) {
        ScreenBar(title, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun ToggleCard(icon: ImageVector, title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    SettingCard(icon, title, desc, trailing = { GSwitch(value, onChange) })
}

/** Dropdown: the card shows the current choice; tapping expands the options with the current one highlighted. */
@Composable
private fun Dropdown(
    icon: ImageVector,
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        SettingCard(
            icon, title, options.firstOrNull { it.first == current }?.second ?: "",
            onClick = { open = !open },
            trailing = { GIcon(if (open) Ico.ArrowUp else Ico.ArrowDown, tint = Gx.textDim) }
        )
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier.padding(top = 6.dp).fillMaxWidth().clip(Shapes.card)
                    .background(Gx.surface).border(1.dp, Gx.border, Shapes.card).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((key, label) in options) {
                    val on = key == current
                    Box(
                        Modifier.fillMaxWidth().pressable(scale = 0.98f, onTap = { onPick(key); open = false })
                            .clip(Shapes.small).background(if (on) Gx.selected else Gx.surface)
                            .border(1.dp, if (on) Gx.selectedBorder else Gx.surface, Shapes.small)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) { GText(label, color = if (on) Gx.accent else Gx.text, maxLines = 1) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
fun SettingsHomeScreen(onBack: () -> Unit, onNavigate: (Route) -> Unit) {
    Page("Settings", onBack) {
        // Named onClick matters: a trailing lambda here would be taken as the card's content slot.
        SettingCard(Ico.Code, "Editor", "Font, highlighting, indentation and smart actions",
            glow = true, onClick = { onNavigate(Route.SetEditor) })
        SettingCard(Ico.Palette, "Appearance", "Themes, UI temperature and code colours",
            glow = true, onClick = { onNavigate(Route.SetAppearance) })
        SettingCard(Ico.Github, "GitHub", "Account, Git identity and notifications",
            glow = true, onClick = { onNavigate(Route.SetGithub) })
        SettingCard(Ico.Robot, "Gemini", "API key, model and tree creation behaviour",
            glow = true, onClick = { onNavigate(Route.SetAi) })
        SettingCard(Ico.Folder, "Project Store", "One folder that keeps all your projects, one tap away",
            glow = true, onClick = { onNavigate(Route.SetStore) })
        SettingCard(Ico.Delete, "Recycle Bin", "Restore or permanently delete removed items",
            glow = true, onClick = { onNavigate(Route.Recycle) })
        SettingCard(HeartIcon, "Support", "Back the Gitex Community and see where it stands",
            glow = true, onClick = { onNavigate(Route.Support) })
    }
}

@Composable
fun StoreSettingsScreen(onBack: () -> Unit, onChoose: () -> Unit) {
    val dir = StorePrefs.dir()
    val ok = StorePrefs.available()
    var removeDlg by remember { mutableStateOf(false) }
    val count by produceState(-1, dir?.absolutePath, StorePrefs.showHidden) {
        value = withContext(Dispatchers.IO) {
            if (dir == null || !ok) -1
            else dir.listFiles()?.count { it.isDirectory && !Fs.isHiddenName(it.name) && (StorePrefs.showHidden || !it.name.startsWith(".")) } ?: 0
        }
    }

    Page("Project Store", onBack) {
        SettingCard(
            Ico.Folder, "Store folder",
            when {
                dir == null -> "Not set. Tap to choose the folder that will hold your projects"
                !ok -> "${dir.absolutePath}\nNot available right now. Tap to choose it again"
                else -> "${dir.absolutePath}\n" + (if (count == 1) "1 project" else "$count projects")
            },
            glow = true, onClick = onChoose
        )
        if (dir != null) {
            SettingCard(Ico.Delete, "Remove store folder", "Gitex forgets it. Nothing inside is deleted", onClick = { removeDlg = true })
        }
        ToggleCard(
            Ico.Home, "Show on the start screen",
            "Adds \"Choose from store\" next to Select Folder and Open file",
            StorePrefs.showOnStart
        ) { StorePrefs.showOnStart = it }
        Dropdown(
            Ico.Code, "Sort projects by",
            listOf("name" to "Name", "opened" to "Recently opened", "changed" to "Recently changed"),
            StorePrefs.sort
        ) { StorePrefs.sort = it }
        ToggleCard(
            Ico.Folder, "Show hidden folders",
            "Folders whose name starts with a dot", StorePrefs.showHidden
        ) { StorePrefs.showHidden = it }
        SettingCard(
            Ico.Warning, "How it works",
            "Each folder in the store is a project. Tap one on the start screen (or Menu → Open from store) to open it at once. " +
                "\"New project\" there creates a folder in the store. Gitex never moves or deletes anything in the store by itself."
        )
    }

    StoreState.pending?.let { picked ->
        GDialog(
            onDismiss = { StoreState.pending = null },
            title = "Use this folder as your Project Store?",
            cancel = DialogBtn("Cancel", { StoreState.pending = null }),
            actions = listOf(DialogBtn("Agree", {
                StorePrefs.path = picked.absolutePath
                StoreState.pending = null
                if (!picked.canWrite()) {
                    Banners.show("Store set, but Gitex can't write there, so New project won't work.", BannerKind.Error)
                } else Banners.show("Project Store set", BannerKind.Success)
            }, primary = true))
        ) {
            GText(picked.absolutePath, style = Ty.small, color = Gx.accent, maxLines = 3)
            Spacer(Modifier.height(10.dp))
            GText(
                "What the Project Store does:\n" +
                    "• Shows this folder's projects and files on the start screen under \"Choose from store\", and in Menu → Open from store.\n" +
                    "• Tapping a folder opens it as your project at once; tapping a file opens it in the editor.\n" +
                    "• \"New project\" creates a folder here and opens it.\n" +
                    "• Lists can be sorted, and folders with Git are marked.\n\n" +
                    "Gitex never moves, changes or deletes anything in it by itself, and nothing is uploaded. " +
                    "You can remove or change the store at any time; your files stay where they are.",
                style = Ty.small, color = Gx.textDim
            )
        }
    }
    if (removeDlg) {
        GDialog(
            onDismiss = { removeDlg = false }, title = "Remove the store folder?",
            actions = listOf(DialogBtn("Remove", { StorePrefs.path = ""; removeDlg = false; Banners.show("Project Store removed") }, danger = true))
        ) { GText("Gitex only forgets which folder it was. All files and projects in it stay untouched.", color = Gx.textDim) }
    }
}

@Composable
fun EditorSettingsScreen(onBack: () -> Unit) {
    Page("Editor Settings", onBack) {
        SettingCard(Ico.FontA, "Font Size", "${Prefs.fontSize.roundToInt()} sp, line numbers scale with it", trailing = {
            GSlider(Prefs.fontSize, { Prefs.fontSize = it.roundToInt().toFloat() }, 10f..24f, Modifier.width(140.dp), steps = 13)
        })
        Dropdown(Ico.FontA, "Font Type", Fonts.options.map { it.key to it.label }, Prefs.fontKey) { Prefs.fontKey = it }
        ToggleCard(Ico.Code, "Syntax Highlighting", "Colour keywords, strings and comments", Prefs.syntaxHighlight) { Prefs.syntaxHighlight = it }
        ToggleCard(Ico.Wrench, "Smart Actions", "Keep indentation, pair brackets and quotes, smart backspace", Prefs.smartActions) { Prefs.smartActions = it }
        ToggleCard(Ico.Book, "Indentation Guides", "Faint vertical lines at each indentation level", Prefs.indentGuides) { Prefs.indentGuides = it }
        SettingCard(Ico.Code, "Indentation Width", "Spaces per indentation level", trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in listOf(2, 4, 8)) {
                    val on = Prefs.indentWidth == w
                    Box(
                        Modifier.pressable(scale = 0.92f, onTap = { Prefs.indentWidth = w })
                            .clip(Shapes.small).background(if (on) Gx.accent else Gx.surfaceHigh)
                            .border(1.dp, if (on) Gx.accent else Gx.border, Shapes.small)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { GText("$w", color = if (on) Gx.onAccent else Gx.text, style = Ty.label) }
                }
            }
        })
        ToggleCard(Ico.Code, "Bracket Highlight", "Highlight the matching bracket next to the cursor", Prefs.bracketHighlight) { Prefs.bracketHighlight = it }
        ToggleCard(Ico.Pencil, "Highlight Current Line", "Subtle background on the line you're editing", Prefs.highlightCurrentLine) { Prefs.highlightCurrentLine = it }
        ToggleCard(Ico.Check, "Autosave", "Save a moment after you stop typing", Prefs.autosave) { Prefs.autosave = it }
        ToggleCard(Ico.Warning, "Duplicate Content Warning", "Notify when different files contain identical content", Prefs.duplicateWarnings) { Prefs.duplicateWarnings = it }
    }
}

@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, onNavigate: (Route) -> Unit) {
    Page("Appearance Settings", onBack) {
        SettingCard(Ico.Palette, "Theme", Themes.byId(Prefs.themeId).name, glow = true, onClick = { onNavigate(Route.SetTheme) },
            trailing = { GIcon(Ico.ChevronRight, tint = Gx.textDim) })
        SettingCard(Ico.Palette, "UI Temperature", "Cool blue on the left, warm brown on the right", trailing = {
            GSlider(Prefs.uiTemperature, { Prefs.uiTemperature = it }, 0f..1f, Modifier.width(140.dp))
        })
        Dropdown(Ico.Code, "Syntax Highlight Theme", Syntax.schemes.map { it.id to it.name }, Prefs.syntaxThemeId) { Prefs.syntaxThemeId = it }
    }
}

// ---- theme previews: any of .jpg .jpeg .png .webp, matched by theme id or name ignoring case/spaces/punctuation ----

private object PreviewFiles {
    private var names: List<String>? = null

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun find(ctx: Context, id: String, name: String): String? {
        val list = names ?: (try { ctx.assets.list("theme-previews")?.toList() } catch (_: Exception) { null } ?: emptyList()).also { names = it }
        val exts = setOf("jpg", "jpeg", "png", "webp")
        val imgs = list.filter { it.substringAfterLast('.', "").lowercase() in exts }
        val wanted = setOf(norm(id), norm(name))
        imgs.firstOrNull { norm(it.substringBeforeLast('.')) in wanted }?.let { return it }
        return imgs.firstOrNull { f -> val n = norm(f.substringBeforeLast('.')); wanted.any { it.isNotEmpty() && n.contains(it) } }
    }
}

private fun loadThemePreview(ctx: Context, t: ThemeDef): ImageBitmap? {
    val file = PreviewFiles.find(ctx, t.id, t.name) ?: return null
    return try {
        val path = "theme-previews/$file"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1100 || bounds.outHeight / sample > 1100) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ThemePickerScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val currentDark = Themes.byId(Prefs.themeId).dark
    var tab by remember { mutableStateOf(if (currentDark) 1 else 0) }
    val list = if (tab == 0) Themes.light else Themes.dark
    Column(Modifier.fillMaxSize().background(Gx.bg)) {
        ScreenBar("Theme", onClose, icon = Ico.Close)
        GTabs(listOf("Light", "Dark"), tab, { tab = it })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (t in list) {
                val img by produceState<ImageBitmap?>(null, t.id) {
                    value = withContext(Dispatchers.IO) { loadThemePreview(ctx, t) }
                }
                GCard(
                    Modifier.fillMaxWidth(), selected = Prefs.themeId == t.id,
                    onClick = { Prefs.themeId = t.id }, padding = PaddingValues(0.dp)
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            GText(t.name, style = Ty.heading, modifier = Modifier.weight(1f))
                            if (Prefs.themeId == t.id) GIcon(Ico.Check, tint = Gx.accent)
                        }
                        Box(
                            Modifier.fillMaxWidth().height(190.dp).background(Gx.bg.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val b = img
                            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                            else GText("Later", color = Gx.textDim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GitHubSettingsScreen(onBack: () -> Unit) {
    var urlDlg by remember { mutableStateOf(false) }
    var idDlg by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val connected = Prefs.githubConnected

    Page("GitHub Settings", onBack) {
        SettingCard(Ico.Github, "GitHub URL", Prefs.githubUrl.ifBlank { "Not set: tap to add your account URL" },
            glow = true, onClick = { urlDlg = true })
        SettingCard(
            Ico.Github, "Account", if (connected) "Connected" else "Not connected",
            trailing = {
                GButton(
                    if (connected) "Disconnect" else "Connect",
                    onClick = {
                        if (connected) {
                            GitHubAuth.disconnect()
                            Banners.show("Disconnected. To revoke access completely, use github.com/settings/applications")
                        } else connecting = true
                    },
                    primary = !connected, glow = false, height = 38.dp
                )
            }
        )
        SettingCard(
            Ico.Pencil, "Git identity",
            if (Prefs.gitName.isBlank()) "Name and email used on your commits" else "${Prefs.gitName} <${Prefs.gitEmail}>",
            onClick = { idDlg = true }
        )
        ToggleCard(Ico.Warning, "Build notifications", "Follow GitHub Actions runs after you push", Prefs.buildNotifications) { Prefs.buildNotifications = it }
    }

    GitHubConnectFlow(active = connecting) { connecting = false }

    if (urlDlg) {
        GitHubUrlDialog(
            initial = Prefs.githubUrl.ifBlank { "https://github.com/" },
            onSave = { Prefs.githubUrl = it; urlDlg = false },
            onDismiss = { urlDlg = false }
        )
    }
    if (idDlg) {
        var name by remember { mutableStateOf(TextFieldValue(Prefs.gitName)) }
        var email by remember { mutableStateOf(TextFieldValue(Prefs.gitEmail)) }
        var err by remember { mutableStateOf<String?>(null) }
        val save = {
            if (name.text.isBlank()) err = "Enter a name"
            else if (!email.text.contains('@') || email.text.contains(' ')) err = "Enter a valid email"
            else { Prefs.gitName = name.text.trim(); Prefs.gitEmail = email.text.trim(); idDlg = false }
        }
        GDialog(onDismiss = { idDlg = false }, title = "Git identity", actions = listOf(DialogBtn("Save", save, primary = true))) {
            GText("Shown as the author of commits made from this phone.", style = Ty.small, color = Gx.textDim)
            Spacer(Modifier.height(10.dp))
            err?.let { GText(it, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(bottom = 6.dp)) }
            GInput(name, { name = it; err = null }, Modifier.fillMaxWidth(), placeholder = "Name", imeAction = ImeAction.Next)
            Spacer(Modifier.height(10.dp))
            GInput(email, { email = it; err = null }, Modifier.fillMaxWidth(), placeholder = "Email",
                keyboardType = KeyboardType.Email, onDone = save)
        }
    }
}

@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val key = remember(tick) { Prefs.geminiKey() }
    var keyDlg by remember { mutableStateOf(false) }
    var removeDlg by remember { mutableStateOf(false) }

    Page("Gemini", onBack) {
        SettingCard(
            Ico.Robot, "API key",
            if (key == null) "Not set. Add your own key to use AI tree creation" else "Saved on this phone, ending …${key.takeLast(4)}",
            glow = true, onClick = { keyDlg = true }
        )
        if (key != null) {
            SettingCard(Ico.Delete, "Remove API key", "Delete it from this phone", onClick = { removeDlg = true })
        }
        Dropdown(Ico.Code, "Model", GeminiTree.MODELS, AiPrefs.model) { AiPrefs.model = it }
        ToggleCard(
            Ico.Wrench, "Use the built-in reader first",
            "Normal trees are created instantly and offline, and nothing is sent to Google. Turn off to always ask Gemini.",
            AiPrefs.localFirst
        ) { AiPrefs.localFirst = it }
        SettingCard(
            Ico.Warning, "How it works",
            "Automatic tries the newest model first and moves to the next one when Google reports it is busy. " +
                "Only the text you paste is sent, using your own key, which is stored encrypted on this phone."
        )
    }

    if (keyDlg) {
        var v by remember { mutableStateOf(TextFieldValue("")) }
        var err by remember { mutableStateOf<String?>(null) }
        val save = {
            if (v.text.trim().length < 20) err = "That doesn't look like an API key"
            else { Prefs.saveGeminiKey(v.text); tick++; keyDlg = false; Banners.show("API key saved", BannerKind.Success) }
        }
        GDialog(onDismiss = { keyDlg = false }, title = if (key == null) "Add API key" else "Change API key",
            actions = listOf(DialogBtn("Save", save, primary = true))) {
            GText("Get a free key at aistudio.google.com/apikey and paste it here.", style = Ty.small, color = Gx.textDim)
            Spacer(Modifier.height(10.dp))
            err?.let { GText(it, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(bottom = 6.dp)) }
            GInput(v, { v = it; err = null }, Modifier.fillMaxWidth(), placeholder = "API key", error = err != null, mono = true, onDone = save)
        }
    }
    if (removeDlg) {
        GDialog(onDismiss = { removeDlg = false }, title = "Remove API key?",
            actions = listOf(DialogBtn("Remove", {
                Prefs.saveGeminiKey(null); tick++; removeDlg = false; Banners.show("API key removed")
            }, danger = true))) {
            GText("AI tree creation will ask for a key again the next time it needs one.", color = Gx.textDim)
        }
    }
}

// ---------------------------------------------------------------------------
// Support / Community: an invitation, the level dashboard, and the usage timer
// ---------------------------------------------------------------------------

val HeartIcon: ImageVector by lazy {
    ImageVector.Builder("heart", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        ).toNodes(),
        fill = SolidColor(Color.Black)
    ).build()
}

private val SendIcon: ImageVector by lazy {
    ImageVector.Builder("send", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString("M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z").toNodes(),
        fill = SolidColor(Color.Black)
    ).build()
}

/** Set when the usage invitation should be on screen. */
object SupportState {
    var inviteOpen by mutableStateOf(false)
}

object SupportPrefs {
    /** Active time in the app after which the invitation appears (once). */
    const val INVITE_AT_MS = 3L * 60 * 60 * 1000

    var inviteShown by PrefValue<Boolean>(
        "support_invite_shown", false,
        { s, k, d -> s.getBoolean(k, d) }, { e, k, v -> e.putBoolean(k, v) }
    )
}

/** Counts only the time Gitex is on screen. Saved every few seconds, so restarting the app never resets it. */
object UsageTracker {
    private const val KEY = "usage_ms"
    var totalMs by mutableLongStateOf(0L)
        private set
    private var startedAt = 0L
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        totalMs = Prefs.sp.getLong(KEY, 0L)
    }

    fun resume() {
        load()
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
    }

    fun tick() {
        if (startedAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        val d = (now - startedAt).coerceIn(0L, 60_000L)
        startedAt = now
        if (d > 0) {
            totalMs += d
            Prefs.sp.edit().putLong(KEY, totalMs).apply()
        }
    }

    fun pause() {
        tick()
        startedAt = 0L
    }
}

fun fmtDuration(ms: Long): String {
    val m = ms / 60_000
    val h = m / 60
    return if (h > 0) "$h h ${m % 60} min" else "$m min"
}

class Note(val at: Long, val text: String)

/** Messages typed in the Support screen. Kept on this phone only until the community exists. */
object SupportNotes {
    val list = mutableStateListOf<Note>()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(Prefs.sp.getString("support_notes", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(Note(o.optLong("t"), o.optString("m")))
            }
        } catch (_: Exception) {
        }
    }

    fun add(text: String) {
        load()
        list.add(0, Note(System.currentTimeMillis(), text.take(2000)))
        while (list.size > 50) list.removeAt(list.lastIndex)
        try {
            val a = JSONArray()
            for (n in list) a.put(JSONObject().put("t", n.at).put("m", n.text))
            Prefs.sp.edit().putString("support_notes", a.toString()).apply()
        } catch (_: Exception) {
        }
    }
}

class SupportLevel(val name: String, val goalUsd: Int, val unlocks: String)

object SupportData {
    /** Placeholder until payments and the community server exist. */
    const val collectedUsd = 0

    /** Planned goals. They are estimates and can be changed here. */
    val levels = listOf(
        SupportLevel("Seed", 0, "Gitex stays free and keeps improving."),
        SupportLevel("Spark", 25, "A small shared database: the first developers can publish a product page."),
        SupportLevel("Roots", 100, "Profiles, follows and a shared feed for a few hundred people."),
        SupportLevel("Branch", 300, "Chat: direct messages, groups and channels."),
        SupportLevel("Canopy", 750, "Courses and live rooms, plus storage for shared projects."),
        SupportLevel("Forest", 2000, "A full community for thousands, with moderation tools and backups."),
    )
}

@Composable
private fun Para(t: String) {
    GText(t, style = Ty.body, color = Gx.textDim)
}

@Composable
private fun Bullet(t: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.padding(top = 8.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(Gx.accent))
        GText(t, style = Ty.body, color = Gx.textDim, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    GCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GText(title, style = Ty.heading)
            content()
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { GText(text, style = Ty.tiny.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1) }
}

private val soonNote: () -> Unit = {
    Banners.show("Payments aren't switched on yet. This button will work when the community opens.")
}

@Composable
fun SupportScreen(onBack: () -> Unit, onLevel: () -> Unit) {
    SupportNotes.load()
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val used = UsageTracker.totalMs
    val canSend = text.text.isNotBlank()
    val send: () -> Unit = {
        val t = text.text.trim()
        if (t.isNotEmpty()) {
            SupportNotes.add(t)
            text = TextFieldValue("")
            Banners.show("Saved on this phone. Sharing starts with the community.", BannerKind.Success)
        }
    }

    Column(Modifier.fillMaxSize().background(Gx.bg)) {
        ScreenBar("Support", onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GCard(Modifier.fillMaxWidth(), glow = true) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(Shapes.small).background(Gx.selected), contentAlignment = Alignment.Center) {
                            GIcon(HeartIcon, tint = Gx.accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        GText("An offer, from one developer to others", style = Ty.heading, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Para(
                        "Hi, I'm the developer of Gitex. Gitex is free, and it stays free: no ads, no forced accounts, nothing locked. " +
                            "This page isn't a plea. It's an invitation to build something together that we would all use, " +
                            "and an honest look at what it takes."
                    )
                }
            }
            Section("Where Gitex comes from") {
                Para(
                    "Gitex was built entirely on a phone, using GitHub Actions to compile it, with no computer and no paid platform subscriptions. " +
                        "If you build on your phone too, you already know it works. I'd like to show it can go much further."
                )
            }
            Section("The idea: Gitex Community") {
                Para("A place made by developers, for developers, where you can:")
                Bullet("Publish your products, so others can find, try and follow them.")
                Bullet("Invite each other to projects and build together.")
                Bullet("See everyone's products together in one central place.")
                Bullet("Chat, create channels and groups, and talk live.")
                Bullet("Post courses, teach each other and learn from each other.")
                Bullet("Grow into a large, friendly community of developers who build on their phones.")
            }
            Section("What it takes") {
                Para(
                    "A community runs on servers: a database, storage and live messaging. Cloud providers do offer free tiers, " +
                        "but they are tiny. Honestly, not enough for even three people to post their products. " +
                        "Paid capacity is what lets it open to everyone, and it's the one piece I can't build with code."
                )
            }
            Section("The offer") {
                Para(
                    "Back the community early, and you help it exist at all. Each level on the dashboard shows what that amount unlocks: " +
                        "what the community can do and how many people it can hold. Everyone who backs it appears on the Founding supporters wall " +
                        "with their own message, or stays private if they prefer."
                )
                Para("Not ready for that? Using Gitex, telling a friend, or sending an idea in the box below is already a real contribution. No pressure, ever.")
            }
            GButton("Support", onClick = soonNote, modifier = Modifier.fillMaxWidth())
            GButton("Current level", onClick = onLevel, modifier = Modifier.fillMaxWidth(), primary = false, glow = false)

            if (SupportNotes.list.isNotEmpty()) {
                GText("Your messages", style = Ty.heading, modifier = Modifier.padding(top = 6.dp))
                GText("Saved on this phone for now. They can be shared once the community opens.", style = Ty.small, color = Gx.textDim)
                for (n in SupportNotes.list.toList()) {
                    GCard(Modifier.fillMaxWidth()) {
                        Column {
                            GText(n.text, style = Ty.body)
                            GText(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.at)),
                                style = Ty.tiny, color = Gx.textDim, modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            GText(
                "You've spent ${fmtDuration(used)} in Gitex. Thank you for the time you give it.",
                style = Ty.small, color = Gx.textDim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }

        // The message box stays at the bottom while the text scrolls.
        Column(Modifier.fillMaxWidth().background(Gx.surface)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconBtn(
                    SendIcon, send, enabled = canSend, tint = Gx.onAccent,
                    bg = if (canSend) Gx.accent else Gx.disabled, size = 48.dp
                )
                Spacer(Modifier.width(8.dp))
                GInput(
                    text, { text = it }, Modifier.weight(1f),
                    placeholder = "Tell me what you'd want first…", singleLine = false, maxLines = 4,
                    imeAction = ImeAction.Default
                )
            }
        }
    }
}

@Composable
fun SupportLevelScreen(onBack: () -> Unit) {
    val total = SupportData.collectedUsd
    val levels = SupportData.levels
    val curIdx = levels.indexOfLast { total >= it.goalUsd }.coerceAtLeast(0)
    val cur = levels[curIdx]
    val next = levels.getOrNull(curIdx + 1)
    val frac = if (next == null) 1f else ((total - cur.goalUsd).toFloat() / (next.goalUsd - cur.goalUsd)).coerceIn(0f, 1f)

    Column(Modifier.fillMaxSize().background(Gx.bg)) {
        ScreenBar("Current level", onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GCard(Modifier.fillMaxWidth(), glow = true) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GText("Collected so far", style = Ty.small, color = Gx.textDim, modifier = Modifier.weight(1f))
                        Chip("Preview", Gx.accent)
                    }
                    GText("$" + total, style = Ty.title.copy(fontSize = 38.dp.value.let { androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp) }), modifier = Modifier.padding(top = 4.dp))
                    GText("Level ${curIdx} · ${cur.name}", style = Ty.label, color = Gx.accent)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Gx.border)) {
                        Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(RoundedCornerShape(50)).background(Gx.accent))
                    }
                    Spacer(Modifier.height(6.dp))
                    GText(
                        if (next == null) "Every level is reached. Thank you!"
                        else "$" + (next.goalUsd - total) + " more to reach Level ${curIdx + 1} · ${next.name}",
                        style = Ty.small, color = Gx.textDim
                    )
                    Spacer(Modifier.height(14.dp))
                    GButton("Support", onClick = soonNote, modifier = Modifier.fillMaxWidth())
                }
            }

            GText("What each level opens up", style = Ty.heading, modifier = Modifier.padding(top = 4.dp))
            levels.forEachIndexed { i, lv ->
                val done = total >= lv.goalUsd
                val here = i == curIdx
                GCard(Modifier.fillMaxWidth(), selected = here) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(if (done) Gx.accent else Gx.surfaceHigh)
                                .border(1.dp, if (done) Gx.accent else Gx.border, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done) GIcon(Ico.Check, size = 18.dp, tint = Gx.onAccent)
                            else GText("$i", style = Ty.label, color = Gx.textDim)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GText(lv.name, style = Ty.label, modifier = Modifier.weight(1f))
                                GText(if (lv.goalUsd == 0) "Now" else "$" + lv.goalUsd, style = Ty.small, color = Gx.accent)
                            }
                            GText(lv.unlocks, style = Ty.small, color = Gx.textDim)
                        }
                    }
                }
            }
            GText("These goals are estimates of what each step of hosting costs, and may change.", style = Ty.tiny, color = Gx.textDim)

            GText("Founding supporters", style = Ty.heading, modifier = Modifier.padding(top = 4.dp))
            GCard(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    GIcon(HeartIcon, size = 30.dp, tint = Gx.accent)
                    Spacer(Modifier.height(8.dp))
                    GText("No one has backed it yet", style = Ty.label)
                    GText(
                        "Everyone who joins in early will appear here with their own message, unless they prefer to stay private.",
                        style = Ty.small, color = Gx.textDim, textAlign = TextAlign.Center
                    )
                }
            }
            GText("How a thank-you will look", style = Ty.small, color = Gx.textDim)
            GCard(Modifier.fillMaxWidth().alpha(0.6f)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Gx.selected), contentAlignment = Alignment.Center) {
                            GIcon(HeartIcon, size = 18.dp, tint = Gx.accent)
                        }
                        Spacer(Modifier.width(10.dp))
                        GText("A fellow developer", style = Ty.label, modifier = Modifier.weight(1f))
                        Chip("Example", Gx.textDim)
                    }
                    GText("“Building on a phone made me a believer. Count me in.”", style = Ty.small, modifier = Modifier.padding(top = 8.dp))
                }
            }
            GText(
                "This screen is a preview. The real total, payments and thank-you messages arrive when the community begins.",
                style = Ty.tiny, color = Gx.textDim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
        }
    }
}

/** The large window shown once, after 3 hours of active use. */
@Composable
fun SupportInviteDialog(onTry: () -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        CompositionLocalProvider(LocalInteractive provides null) {
            Column(
                Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f)
                    .clip(Shapes.large).background(Gx.surface).border(1.dp, Gx.border, Shapes.large)
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(Shapes.small).background(Gx.selected), contentAlignment = Alignment.Center) {
                        GIcon(HeartIcon, tint = Gx.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    GText("An offer for fellow builders", style = Ty.heading, modifier = Modifier.weight(1f))
                    IconBtn(Ico.Close, onClose)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Para(
                        "You've spent ${fmtDuration(UsageTracker.totalMs)} in Gitex. Thank you, that means a lot to the one person building it."
                    )
                    Para(
                        "Gitex is free and stays free. It was built entirely on a phone, with GitHub Actions and no computer. " +
                            "The next step is a community made by developers, for developers:"
                    )
                    Bullet("publish your products and follow each other's work,")
                    Bullet("invite each other to projects, chat in channels and groups, talk live,")
                    Bullet("post courses and teach each other.")
                    Para(
                        "It needs real server capacity, and free cloud tiers are too small even for a handful of people. " +
                            "So this is an open offer: back the community early, and see on the dashboard exactly what each level unlocks. " +
                            "No pressure. Reading the full story costs nothing."
                    )
                }
                Box(Modifier.fillMaxWidth().padding(14.dp)) {
                    GButton("Try", onClick = onTry, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}