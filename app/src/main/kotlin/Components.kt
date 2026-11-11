package app.gitex

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Dead Tap
// ---------------------------------------------------------------------------

/**
 * A Dead Tap is a tap that lands where no registered interactive element is.
 * [DeadTapHost] watches every gesture on the Initial pass (it never consumes anything,
 * so nothing is activated by accident), dismisses the keyboard, and notifies listeners.
 */
class DeadTapState {
    private val dead = ArrayList<() -> Unit>()
    private val downs = ArrayList<(Offset) -> Unit>()

    /** Set by [DeadTapHost]: closes the soft keyboard and clears focus. */
    var hide: () -> Unit = {}

    fun addDead(l: () -> Unit) { dead.add(l) }
    fun removeDead(l: () -> Unit) { dead.remove(l) }
    fun addDown(l: (Offset) -> Unit) { downs.add(l) }
    fun removeDown(l: (Offset) -> Unit) { downs.remove(l) }

    fun fireDead() {
        hide()
        for (l in dead.toList()) l()
    }

    fun fireDown(p: Offset) {
        for (l in downs.toList()) l(p)
    }
}

val LocalDeadTap = staticCompositionLocalOf { DeadTapState() }

@Composable
fun rememberHideKeyboard(): () -> Unit {
    val s = LocalDeadTap.current
    return remember(s) { { s.hide() } }
}

/** Runs [action] on every Dead Tap while [enabled]. */
@Composable
fun OnDeadTap(enabled: Boolean = true, action: () -> Unit) {
    val state = LocalDeadTap.current
    val cur by rememberUpdatedState(action)
    DisposableEffect(state, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val l: () -> Unit = { cur() }
        state.addDead(l)
        onDispose { state.removeDead(l) }
    }
}

/**
 * Runs [action] when any touch begins outside [rect] (taps, swipes, scrolls all start with a touch).
 * The touch itself still reaches whatever is under it.
 */
@Composable
fun OnDownOutside(enabled: Boolean, rect: () -> Rect?, action: () -> Unit) {
    val state = LocalDeadTap.current
    val curRect by rememberUpdatedState(rect)
    val curAction by rememberUpdatedState(action)
    DisposableEffect(state, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val l: (Offset) -> Unit = { p ->
            val r = curRect()
            if (r == null || !r.contains(p)) curAction()
        }
        state.addDown(l)
        onDispose { state.removeDown(l) }
    }
}

@Composable
fun DeadTapHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reg = LocalInteractive.current
    val state = remember { DeadTapState() }
    val focus = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    SideEffect {
        state.hide = {
            focus.clearFocus(force = true)
            kb?.hide()
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            view.rootView.clearFocus()
        }
    }
    CompositionLocalProvider(LocalDeadTap provides state) {
        Box(
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        state.fireDown(down.position)
                        val slop = viewConfiguration.touchSlop
                        val longMs = viewConfiguration.longPressTimeoutMillis
                        var moved = false
                        var multi = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.size > 1) multi = true
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if ((ch.position - down.position).getDistance() > slop) moved = true
                            if (ch.changedToUp()) {
                                val onElement = reg?.hit(down.position) ?: false
                                if (!moved && !multi && !onElement &&
                                    ch.uptimeMillis - down.uptimeMillis < longMs
                                ) state.fireDead()
                                break
                            }
                            if (!ch.pressed) break
                        }
                    }
                },
            content = content
        )
    }
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

fun vibrate(ctx: Context, ms: Long = 500) {
    try {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {
    }
}

/** Quick left-right shake each time [trigger] changes to a new positive value. */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val x = remember { Animatable(0f) }
    val d = LocalDensity.current.density
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            for (t in floatArrayOf(-10f, 10f, -8f, 8f, -4f, 4f, 0f)) x.animateTo(t * d, tween(45))
        }
    }
    this.graphicsLayer { translationX = x.value }
}

@Composable
fun GText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Ty.body,
    color: Color = Gx.text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
    fontFamily: FontFamily? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = if (fontFamily != null) style.copy(fontFamily = fontFamily) else style,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}

@Composable
fun GSpinner(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = Gx.accent,
        trackColor = Color.Transparent,
        strokeWidth = 2.5.dp,
    )
}

// ---------------------------------------------------------------------------
// Buttons, cards, bars
// ---------------------------------------------------------------------------

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Gx.text,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    shape: Shape = CircleShape,
    bg: Color? = null,
) {
    var m = modifier.size(size).pressable(enabled = enabled, scale = 0.88f, onTap = onClick)
    if (bg != null) m = m.clip(shape).background(bg, shape)
    Box(m, contentAlignment = Alignment.Center) {
        GIcon(icon, size = iconSize, tint = if (enabled) tint else Gx.textDim.copy(alpha = 0.5f))
    }
}

@Composable
fun GButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
    glow: Boolean = primary,
    height: Dp = 50.dp,
) {
    val fill = when {
        !enabled -> Gx.disabled
        primary && danger -> Gx.danger
        primary -> Gx.accent
        else -> Gx.surfaceHigh
    }
    val fg = when {
        !enabled -> Gx.textDim.copy(alpha = 0.7f)
        primary -> if (danger) Color.White else Gx.onAccent
        danger -> Gx.danger
        else -> Gx.text
    }
    val shape = Shapes.card
    var m = modifier.heightIn(min = height).pressable(enabled = enabled, onTap = onClick)
    if (glow && enabled) m = m.glow(shape, fill.copy(alpha = 0.5f), 10.dp)
    m = m.clip(shape).background(fill, shape)
    if (!primary && enabled) m = m.border(1.dp, Gx.border, shape)
    Row(
        m.padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            GIcon(icon, size = 20.dp, tint = fg)
            Spacer(Modifier.width(10.dp))
        }
        GText(text, style = Ty.label.copy(fontSize = 15.5.sp), color = fg, maxLines = 1)
    }
}

@Composable
fun GCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    selected: Boolean = false,
    glow: Boolean = false,
    shape: Shape = Shapes.card,
    padding: PaddingValues = PaddingValues(Dim.pad),
    content: @Composable BoxScope.() -> Unit,
) {
    val bg by animateColorAsState(if (selected) Gx.selected else Gx.surface, tween(140), label = "cardBg")
    val bd by animateColorAsState(if (selected) Gx.selectedBorder else Gx.border, tween(140), label = "cardBd")
    var m = modifier
    if (onClick != null || onLongPress != null) m = m.pressable(onLongPress = onLongPress, onTap = onClick)
    if (glow) m = m.glow(shape, Gx.accent.copy(alpha = 0.4f), 10.dp)
    Box(
        m.background(bg, shape).border(1.dp, bd, shape).clip(shape).padding(padding),
        content = content
    )
}

@Composable
fun GTopBar(
    modifier: Modifier = Modifier,
    left: @Composable BoxScope.() -> Unit = {},
    center: @Composable BoxScope.() -> Unit = {},
    right: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().background(Gx.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp)) {
            Box(Modifier.align(Alignment.CenterStart), content = left)
            Box(Modifier.align(Alignment.Center), content = center)
            Row(
                Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                content = right
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
    }
}

/** Standard secondary-screen bar: back (or close) button, title, optional right actions. */
@Composable
fun ScreenBar(
    title: String,
    onBack: () -> Unit,
    icon: ImageVector = Ico.Back,
    right: @Composable RowScope.() -> Unit = {},
) {
    GTopBar(
        left = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(icon, onBack)
                GText(title, style = Ty.heading, modifier = Modifier.padding(start = 4.dp), maxLines = 1)
            }
        },
        right = right
    )
}

@Composable
fun GTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Gx.bg)) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            val line by animateColorAsState(if (on) Gx.accent else Color.Transparent, tween(160), label = "tabLine")
            Column(
                Modifier.weight(1f).pressable(scale = 0.97f, onTap = { onSelect(i) }),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GText(
                    t, style = Ty.label, color = if (on) Gx.text else Gx.textDim,
                    modifier = Modifier.padding(vertical = 13.dp), maxLines = 1
                )
                Box(Modifier.fillMaxWidth().height(2.5.dp).background(line))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
}

@Composable
fun SettingCard(
    icon: ImageVector,
    title: String,
    desc: String,
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    GCard(modifier.fillMaxWidth(), onClick = onClick, glow = glow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(Shapes.small).background(Gx.selected),
                contentAlignment = Alignment.Center
            ) { GIcon(icon, tint = Gx.accent) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                GText(title, style = Ty.label.copy(fontSize = 15.5.sp))
                GText(desc, style = Ty.small, color = Gx.textDim)
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

@Composable
fun GSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) Gx.accent else Gx.border, tween(140), label = "swTrack")
    val x by animateDpAsState(if (checked) 20.dp else 2.dp, tween(140), label = "swX")
    Box(
        modifier
            .size(46.dp, 28.dp)
            .pressable(scale = 0.93f, onTap = { onChange(!checked) })
            .clip(CircleShape)
            .background(track)
    ) {
        Box(
            Modifier.offset(x = x, y = 2.dp).size(24.dp).clip(CircleShape)
                .background(if (checked) Gx.onAccent else Gx.textDim)
        )
    }
}

@Composable
fun GSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        modifier = modifier.interactive(),
        colors = SliderDefaults.colors(
            thumbColor = Gx.accent,
            activeTrackColor = Gx.accent,
            inactiveTrackColor = Gx.border,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        )
    )
}

// ---------------------------------------------------------------------------
// Text input
// ---------------------------------------------------------------------------

@Composable
fun GInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: Boolean = false,
    shakeKey: Int = 0,
    singleLine: Boolean = true,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    mono: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    height: Dp = 48.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        when {
            error -> Gx.danger
            focused -> Gx.accent
            else -> Gx.border
        }, tween(120), label = "inputBorder"
    )
    Row(
        modifier
            .shake(shakeKey)
            .interactive()
            .heightIn(min = height)
            .clip(Shapes.small)
            .background(Gx.surfaceHigh)
            .border(1.dp, borderColor, Shapes.small)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                GText(placeholder, color = Gx.textDim, maxLines = 1)
            }
            var fm = Modifier.fillMaxWidth()
            if (focusRequester != null) fm = fm.focusRequester(focusRequester)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = fm.onFocusChanged { focused = it.isFocused },
                enabled = enabled,
                textStyle = Ty.body.copy(
                    color = Gx.text,
                    fontFamily = if (mono) FontFamily.Monospace else null
                ),
                cursorBrush = SolidColor(Gx.accent),
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = keyboardType),
                keyboardActions = KeyboardActions(
                    onDone = { onDone?.invoke() },
                    onGo = { onDone?.invoke() },
                    onSearch = { onDone?.invoke() },
                    onSend = { onDone?.invoke() },
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs and menus (separate windows, so they must not register with Dead Tap)
// ---------------------------------------------------------------------------

class DialogBtn(
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
private fun DialogTextBtn(b: DialogBtn) {
    val color = when {
        !b.enabled -> Gx.textDim.copy(alpha = 0.5f)
        b.danger -> Gx.danger
        b.primary -> Gx.accent
        else -> Gx.textDim
    }
    Box(
        Modifier
            .pressable(enabled = b.enabled, scale = 0.94f, onTap = b.onClick)
            .clip(Shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        GText(
            b.label,
            style = Ty.label.copy(fontWeight = if (b.primary || b.danger) androidx.compose.ui.text.font.FontWeight.SemiBold
            else androidx.compose.ui.text.font.FontWeight.Medium),
            color = color, maxLines = 1
        )
    }
}

/**
 * Cancel sits at the bottom-left; [actions] are right-aligned, last one at the far right.
 */
@Composable
fun GDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    cancel: DialogBtn? = DialogBtn("Cancel", onDismiss),
    actions: List<DialogBtn> = emptyList(),
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnOutside,
            usePlatformDefaultWidth = false
        )
    ) {
        CompositionLocalProvider(LocalInteractive provides null) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(Shapes.large)
                    .background(Gx.surface, Shapes.large)
                    .border(1.dp, Gx.border, Shapes.large)
                    .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 10.dp)
            ) {
                if (title != null) {
                    GText(title, style = Ty.heading, modifier = Modifier.padding(end = 8.dp))
                    Spacer(Modifier.height(10.dp))
                }
                Column(Modifier.padding(end = 8.dp)) { content() }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (cancel != null) DialogTextBtn(cancel)
                    Spacer(Modifier.weight(1f))
                    for (a in actions) DialogTextBtn(a)
                }
            }
        }
    }
}

class MenuItem(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

private class ClampProvider(val anchor: Offset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val pad = 12
        val maxX = (windowSize.width - popupContentSize.width - pad).coerceAtLeast(pad)
        val maxY = (windowSize.height - popupContentSize.height - pad).coerceAtLeast(pad)
        return IntOffset(anchor.x.toInt().coerceIn(pad, maxX), anchor.y.toInt().coerceIn(pad, maxY))
    }
}

/** Small popup placed near [anchor] (root coordinates) and always kept inside the screen. */
@Composable
fun ContextMenu(anchor: Offset, items: List<MenuItem>, onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = remember(anchor) { ClampProvider(anchor) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        CompositionLocalProvider(LocalInteractive provides null) {
            Column(
                Modifier
                    .widthIn(min = 150.dp, max = 240.dp)
                    .glow(Shapes.card, Color.Black.copy(alpha = 0.6f), 14.dp)
                    .clip(Shapes.card)
                    .background(Gx.surfaceHigh, Shapes.card)
                    .border(1.dp, Gx.border, Shapes.card)
                    .padding(vertical = 6.dp)
            ) {
                for (it in items) {
                    Box(
                        Modifier.fillMaxWidth()
                            .pressable(scale = 0.97f, onTap = { onDismiss(); it.onClick() })
                            .padding(horizontal = 18.dp, vertical = 13.dp)
                    ) {
                        GText(it.label, style = Ty.body, color = if (it.danger) Gx.danger else Gx.text, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Banners ("Pushed", "Push rejected", errors ...)
// ---------------------------------------------------------------------------

enum class BannerKind { Info, Success, Error }

class BannerMsg(val id: Long, val text: String, val kind: BannerKind)

object Banners {
    private var counter = 0L
    var current by mutableStateOf<BannerMsg?>(null)

    fun show(text: String, kind: BannerKind = BannerKind.Info) {
        current = BannerMsg(++counter, text, kind)
    }
}

@Composable
fun BannerHost() {
    val msg = Banners.current
    var shown by remember { mutableStateOf<BannerMsg?>(null) }
    if (msg != null) shown = msg
    LaunchedEffect(msg?.id) {
        if (msg != null) {
            delay(2800)
            if (Banners.current?.id == msg.id) Banners.current = null
        }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = msg != null,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
        ) {
            val m = shown
            if (m != null) {
                val accent = when (m.kind) {
                    BannerKind.Success -> Gx.success
                    BannerKind.Error -> Gx.danger
                    BannerKind.Info -> Gx.accent
                }
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .widthIn(max = 480.dp)
                        .glow(Shapes.card, accent.copy(alpha = 0.35f), 10.dp)
                        .clip(Shapes.card)
                        .background(Gx.surfaceHigh, Shapes.card)
                        .border(1.dp, accent.copy(alpha = 0.7f), Shapes.card)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(12.dp))
                    GText(m.text, style = Ty.label, maxLines = 3)
                }
            }
        }
    }
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        GText(text, style = Ty.body, color = Gx.textDim, textAlign = TextAlign.Center)
    }
}