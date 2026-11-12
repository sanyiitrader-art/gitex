package app.gitex

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
        SettingCard(Ico.Delete, "Recycle Bin", "Restore or permanently delete removed items",
            glow = true, onClick = { onNavigate(Route.Recycle) })
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
                    value = withContext(Dispatchers.IO) { Themes.loadPreview(ctx, t.id) }
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
                            Modifier.fillMaxWidth().height(170.dp).background(Gx.bg.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val b = img
                            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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