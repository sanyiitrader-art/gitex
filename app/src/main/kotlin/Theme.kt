package app.gitex

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Colours
// ---------------------------------------------------------------------------

@Immutable
data class GitexColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val success: Color,
    /** Background of the highlighted / selected item. */
    val selected: Color,
    val selectedBorder: Color,
    val scrim: Color,
    /** Greyed-out (disabled) button background. */
    val disabled: Color,
)

class ThemeDef(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: Long,
    val surface: Long,
    val high: Long,
    val border: Long,
    val text: Long,
    val dim: Long,
    val accent: Long,
)

private const val BRAND = 0xFFB05D42

object Themes {
    // Order matches the specification. `id` is also the preview image file name:
    // assets/theme-previews/<id>.png
    val dark = listOf(
        ThemeDef("obsidian", "Obsidian", true, 0xFF0F0F10, 0xFF17171A, 0xFF202024, 0xFF2C2C31, 0xFFE8E6E3, 0xFF9A9A9F, BRAND),
        ThemeDef("charcoal", "Charcoal", true, 0xFF1B1C1E, 0xFF232427, 0xFF2C2D31, 0xFF38393E, 0xFFE4E4E6, 0xFF98999E, BRAND),
        ThemeDef("midnight", "Midnight", true, 0xFF0B1020, 0xFF111830, 0xFF18213F, 0xFF25305A, 0xFFDCE3F5, 0xFF8591B5, 0xFF5B8DEF),
        ThemeDef("nord", "Nord", true, 0xFF2E3440, 0xFF3B4252, 0xFF434C5E, 0xFF4C566A, 0xFFECEFF4, 0xFF9AA5B8, 0xFF88C0D0),
        ThemeDef("dracula", "Dracula", true, 0xFF282A36, 0xFF303341, 0xFF3A3D4F, 0xFF44475A, 0xFFF8F8F2, 0xFF9AA0C0, 0xFFBD93F9),
        ThemeDef("one_dark", "One Dark", true, 0xFF21252B, 0xFF282C34, 0xFF2F343D, 0xFF3B4048, 0xFFABB2BF, 0xFF7F8796, 0xFF61AFEF),
        ThemeDef("amoled_black", "AMOLED Black", true, 0xFF000000, 0xFF0A0A0A, 0xFF141414, 0xFF222222, 0xFFEDEDED, 0xFF8E8E8E, BRAND),
        ThemeDef("forest", "Forest", true, 0xFF0F1612, 0xFF15201A, 0xFF1C2B23, 0xFF28392F, 0xFFDCE8DF, 0xFF8BA395, 0xFF5FA06A),
        ThemeDef("graphite", "Graphite", true, 0xFF18191B, 0xFF202124, 0xFF292A2E, 0xFF35373B, 0xFFE2E3E5, 0xFF96979C, BRAND),
        ThemeDef("warm_ember", "Warm Ember", true, 0xFF141210, 0xFF1C1917, 0xFF25211E, 0xFF332D28, 0xFFEDE7E1, 0xFFA0968D, BRAND),
    )

    val light = listOf(
        ThemeDef("paper", "Paper", false, 0xFFF6F3EE, 0xFFFBF9F5, 0xFFEFEAE2, 0xFFDDD6CB, 0xFF24201C, 0xFF7A7068, BRAND),
        ThemeDef("github_light", "GitHub Light", false, 0xFFFFFFFF, 0xFFF6F8FA, 0xFFEAEEF2, 0xFFD0D7DE, 0xFF1F2328, 0xFF656D76, 0xFF0969DA),
        ThemeDef("solarized_light", "Solarized Light", false, 0xFFFDF6E3, 0xFFEEE8D5, 0xFFE6DFC8, 0xFFD3CBB0, 0xFF586E75, 0xFF93A1A1, 0xFF268BD2),
        ThemeDef("rose_pine_dawn", "Rose Pine Dawn", false, 0xFFFAF4ED, 0xFFFFFAF3, 0xFFF2E9E1, 0xFFDFD4C8, 0xFF575279, 0xFF9893A5, 0xFFD7827E),
    )

    val all = dark + light

    fun byId(id: String): ThemeDef = all.firstOrNull { it.id == id } ?: all.first { it.id == "warm_ember" }

    /** Preview screenshot supplied by the user, or null (the UI then shows "Later"). */
    fun loadPreview(ctx: Context, id: String): ImageBitmap? = try {
        ctx.assets.open("theme-previews/$id.png").use { input ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
        }
    } catch (_: Exception) {
        null
    }
}

private val COOL = Color(0xFF3E6FB0)
private val WARM = Color(0xFFB5603A)

private fun tint(c: Color, amount: Float, strength: Float, dark: Boolean): Color {
    if (amount == 0f) return c
    var target = if (amount < 0) COOL else WARM
    if (!dark) target = lerp(target, Color.White, 0.6f)
    return lerp(c, target, abs(amount) * strength)
}

/** [temperature]: 0 = cool blue ... 0.5 = theme as designed ... 1 = warm brown/orange. */
fun buildColors(def: ThemeDef, temperature: Float): GitexColors {
    val a = ((temperature - 0.5f) * 2f).coerceIn(-1f, 1f)
    val d = def.dark
    val bg = tint(Color(def.bg), a, 0.16f, d)
    val surface = tint(Color(def.surface), a, 0.16f, d)
    val high = tint(Color(def.high), a, 0.16f, d)
    val border = tint(Color(def.border), a, 0.16f, d)
    val text = tint(Color(def.text), a, 0.05f, d)
    val dim = tint(Color(def.dim), a, 0.10f, d)

    var accent = Color(def.accent)
    if (a < 0) accent = lerp(accent, Color(0xFF4F8FE0), abs(a) * 0.65f)
    else if (a > 0) accent = lerp(accent, Color(0xFFD9733A), a * 0.35f)

    return GitexColors(
        isDark = d,
        bg = bg,
        surface = surface,
        surfaceHigh = high,
        border = border,
        text = text,
        textDim = dim,
        accent = accent,
        onAccent = if (accent.luminance() > 0.55f) Color(0xFF111111) else Color.White,
        danger = if (d) Color(0xFFE5534B) else Color(0xFFD1242F),
        success = if (d) Color(0xFF3FB950) else Color(0xFF1A7F37),
        selected = lerp(surface, accent, if (d) 0.16f else 0.14f),
        selectedBorder = accent.copy(alpha = 0.85f),
        scrim = Color.Black.copy(alpha = 0.5f),
        disabled = lerp(surface, text, 0.12f),
    )
}

val LocalGitex = staticCompositionLocalOf<GitexColors> { error("GitexTheme missing") }

/** Shorthand used everywhere: `Gx.accent`, `Gx.text`, ... */
val Gx: GitexColors
    @Composable @ReadOnlyComposable get() = LocalGitex.current

@Composable
fun GitexTheme(content: @Composable () -> Unit) {
    val id = Prefs.themeId
    val temp = Prefs.uiTemperature
    val colors = remember(id, temp) { buildColors(Themes.byId(id), temp) }
    val scheme = remember(colors) {
        if (colors.isDark) darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.bg, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text,
            outline = colors.border,
        ) else lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.bg, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text,
            outline = colors.border,
        )
    }
    val registry = remember { InteractiveRegistry() }
    CompositionLocalProvider(LocalGitex provides colors, LocalInteractive provides registry) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ---------------------------------------------------------------------------
// Design tokens
// ---------------------------------------------------------------------------

object Dim {
    val radius = 14.dp
    val radiusSmall = 10.dp
    val radiusLarge = 20.dp
    val pad = 16.dp
}

object Shapes {
    val card = RoundedCornerShape(Dim.radius)
    val small = RoundedCornerShape(Dim.radiusSmall)
    val large = RoundedCornerShape(Dim.radiusLarge)
}

object Ty {
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val heading = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val tiny = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal)
}

// ---------------------------------------------------------------------------
// Editor fonts (loaded from assets/fonts/<key>.ttf, falling back to monospace)
// ---------------------------------------------------------------------------

class FontOption(val key: String, val label: String)

object Fonts {
    val options = listOf(
        FontOption("jetbrains_mono", "JetBrains Mono"),
        FontOption("fira_code", "Fira Code"),
        FontOption("source_code_pro", "Source Code Pro"),
        FontOption("ibm_plex_mono", "IBM Plex Mono"),
        FontOption("roboto_mono", "Roboto Mono"),
        FontOption("ubuntu_mono", "Ubuntu Mono"),
        FontOption("cascadia_code", "Cascadia Code"),
        FontOption("inconsolata", "Inconsolata"),
        FontOption("noto_sans_mono", "Noto Sans Mono"),
        FontOption("system", "System Monospace"),
    )

    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun typeface(ctx: Context, key: String): Typeface = cache.getOrPut(key) {
        if (key == "system") Typeface.MONOSPACE
        else try {
            Typeface.createFromAsset(ctx.assets, "fonts/$key.ttf")
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    fun family(ctx: Context, key: String): FontFamily = FontFamily(typeface(ctx, key))
}

// ---------------------------------------------------------------------------
// Dead Tap support: interactive elements register their layout coordinates.
// A root-level observer (Components.kt) asks `hit()` at tap time, so nothing
// is hard-coded and nothing is measured while scrolling.
// ---------------------------------------------------------------------------

class InteractiveRegistry {
    private val map = HashMap<Any, LayoutCoordinates>()

    fun put(key: Any, coords: LayoutCoordinates) {
        if (map[key] !== coords) map[key] = coords
    }

    fun remove(key: Any) {
        map.remove(key)
    }

    /** True if [p] (in root coordinates) is inside any currently attached interactive element. */
    fun hit(p: Offset): Boolean {
        for (c in map.values) {
            if (c.isAttached && c.boundsInRoot().contains(p)) return true
        }
        return false
    }
}

val LocalInteractive = staticCompositionLocalOf<InteractiveRegistry?> { null }

/** Marks this element as interactive for Dead Tap detection (buttons, inputs, editor, ...). */
fun Modifier.interactive(): Modifier = composed {
    val reg = LocalInteractive.current
    val key = remember { Any() }
    if (reg == null) {
        this
    } else {
        DisposableEffect(reg) { onDispose { reg.remove(key) } }
        this.onGloballyPositioned { reg.put(key, it) }
    }
}

// ---------------------------------------------------------------------------
// Touch feedback + glow
// ---------------------------------------------------------------------------

private class PosHolder {
    var pos = Offset.Zero
}

/**
 * Tap / long-press with the Gitex press effect: shrinks slightly on touch-down,
 * stays reduced while pressed, eases back on release or cancellation.
 * [onLongPress] receives the touch position in root coordinates (for popups).
 * Also registers the element as interactive for Dead Tap.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.965f,
    onLongPress: ((Offset) -> Unit)? = null,
    onTap: (() -> Unit)?,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val holder = remember { PosHolder() }
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val s = animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = tween(if (pressed) 90 else 170, easing = FastOutSlowInEasing),
        label = "press",
    )
    val hasLong = onLongPress != null
    val onLp: (Offset) -> Unit = { off -> long?.invoke(holder.pos + off) }

    this
        .interactive()
        .onGloballyPositioned { holder.pos = it.positionInRoot() }
        .graphicsLayer {
            scaleX = s.value
            scaleY = s.value
        }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (enabled) onClick { tap?.invoke(); true }
        }
        .pointerInput(enabled, hasLong) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressed = false
                    }
                },
                onLongPress = if (hasLong) onLp else null,
                onTap = { tap?.invoke() },
            )
        }
}

/** Soft coloured glow using the hardware shadow path (cheap, no blur passes). Use sparingly. */
fun Modifier.glow(shape: Shape, color: Color, elevation: Dp = 10.dp): Modifier =
    shadow(elevation, shape, clip = false, ambientColor = color, spotColor = color)