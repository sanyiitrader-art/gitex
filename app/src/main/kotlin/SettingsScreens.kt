package app.gitex

import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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
private fun ToggleCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    SettingCard(icon, title, desc, trailing = { GSwitch(value, onChange) })
}

/** Dropdown: the card shows the current choice; tapping expands the options with the current one highlighted. */
@Composable
private fun Dropdown(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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