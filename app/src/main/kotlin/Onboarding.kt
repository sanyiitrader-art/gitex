package app.gitex

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException

// ---------------------------------------------------------------------------
// Permission helpers
// ---------------------------------------------------------------------------

fun hasAllFilesAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun notificationsGranted(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openAllFilesSettings(ctx: Context) {
    val pkg = Uri.parse("package:${ctx.packageName}")
    try {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            } catch (_: Exception) {
            }
        }
    }
}

private fun openAppDetails(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    } catch (_: Exception) {
    }
}

// ---------------------------------------------------------------------------
// "Before We Start"
// ---------------------------------------------------------------------------

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var filesOk by remember { mutableStateOf(hasAllFilesAccess(ctx)) }
    var notifOk by remember { mutableStateOf(notificationsGranted(ctx)) }
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    var storageDenied by rememberSaveable { mutableStateOf(false) }

    // Coming back from the Android settings screen: check again.
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                filesOk = hasAllFilesAccess(ctx)
                notifOk = notificationsGranted(ctx)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        filesOk = hasAllFilesAccess(ctx)
        if (!filesOk) storageDenied = true
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifOk = it
    }

    // Normal runtime dialog for notifications, right after files access is granted.
    LaunchedEffect(filesOk) {
        if (filesOk && !notifOk && !notifAsked && Build.VERSION.SDK_INT >= 33) {
            notifAsked = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val canContinue = filesOk && (notifOk || notifAsked || Build.VERSION.SDK_INT < 33)
    val modern = Build.VERSION.SDK_INT >= 30

    Column(
        Modifier.fillMaxSize().background(Gx.bg).statusBarsPadding().navigationBarsPadding().padding(20.dp)
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            GText("Before We Start", style = Ty.title.copy(fontSize = 28.sp))
            Spacer(Modifier.height(8.dp))
            GText("Gitex needs a couple of permissions to work properly", color = Gx.textDim)
            Spacer(Modifier.height(28.dp))
            GCard(Modifier.fillMaxWidth(), glow = true) {
                Column {
                    GText("Access to your files", style = Ty.heading)
                    Spacer(Modifier.height(8.dp))
                    GText(
                        if (modern)
                            "Gitex edits Real files and folders on your phone to keep git repositories inside them, so it needs all files access. Android opens a settings screen where you switch it on for Gitex, then come back here."
                        else
                            "Gitex edits Real files and folders on your phone to keep git repositories inside them, so it needs access to your storage. Allow it in the Android prompt, then come back here.",
                        style = Ty.small, color = Gx.textDim
                    )
                    Spacer(Modifier.height(16.dp))
                    if (filesOk) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIcon(Ico.Check, tint = Gx.success)
                            Spacer(Modifier.width(8.dp))
                            GText("Access granted", color = Gx.success, style = Ty.label)
                        }
                    } else {
                        GButton(
                            if (modern) "Open Settings" else "Allow access",
                            onClick = {
                                if (modern) openAllFilesSettings(ctx)
                                else if (storageDenied) openAppDetails(ctx)
                                else storageLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GButton("Continue", onClick = onContinue, enabled = canContinue, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------------------
// GitHub welcome screen
// ---------------------------------------------------------------------------

@Composable
fun AppIcon(size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val bmp = remember {
        runCatching {
            ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)!!.toBitmap(256, 256).asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Gx.accent),
            contentAlignment = Alignment.Center
        ) { GText("G", style = Ty.title.copy(fontSize = 34.sp), color = Gx.onAccent) }
    }
}

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    var connecting by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Gx.bg)) {
        IconBtn(
            Ico.Close, onClick = onDone,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
        )
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AppIcon(96.dp)
            Spacer(Modifier.height(22.dp))
            GText("Gitex", style = Ty.title.copy(fontSize = 30.sp))
            Spacer(Modifier.height(10.dp))
            GText(
                "A calm text editor with github built in. Edit files on you phone push you work and follow your builds.",
                color = Gx.textDim, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(36.dp))
            GButton(
                "Connect to github", onClick = { connecting = true },
                icon = Ico.Github, modifier = Modifier.fillMaxWidth()
            )
        }
        GitHubConnectFlow(active = connecting, onFinished = { connected ->
            connecting = false
            if (connected) onDone()
        })
    }
}

// ---------------------------------------------------------------------------
// GitHub connection flow (Device Flow) + account URL popup. Reused from the
// main screen's "Connect to github" button and from GitHub settings.
// ---------------------------------------------------------------------------

private sealed interface ConnectStep {
    data object Idle : ConnectStep
    data object Requesting : ConnectStep
    class Code(val dc: GitHubAuth.DeviceCode) : ConnectStep
    class Url(val initial: String) : ConnectStep
    class Fail(val msg: String) : ConnectStep
}

@Composable
fun GitHubConnectFlow(active: Boolean, onFinished: (connected: Boolean) -> Unit) {
    var step by remember { mutableStateOf<ConnectStep>(ConnectStep.Idle) }
    val ctx = LocalContext.current

    LaunchedEffect(active) {
        if (!active) {
            step = ConnectStep.Idle
            return@LaunchedEffect
        }
        step = ConnectStep.Requesting
        try {
            val dc = GitHubAuth.requestCode()
            step = ConnectStep.Code(dc)
            when (val r = GitHubAuth.pollForToken(dc)) {
                is GitHubAuth.Poll.Token -> {
                    Prefs.saveGithubToken(r.token)
                    val login = GitHubAuth.fetchLogin(r.token)
                    step = ConnectStep.Url(
                        if (login != null) "https://github.com/$login"
                        else Prefs.githubUrl.ifBlank { "https://github.com/" }
                    )
                }
                GitHubAuth.Poll.Denied -> step = ConnectStep.Fail("Authorization was cancelled on GitHub.")
                GitHubAuth.Poll.Expired -> step = ConnectStep.Fail("The code expired. Try connecting again.")
                is GitHubAuth.Poll.Error -> step = ConnectStep.Fail(r.msg)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step = ConnectStep.Fail(e.message ?: "Couldn't reach GitHub")
        }
    }

    when (val s = step) {
        ConnectStep.Idle -> {}
        ConnectStep.Requesting -> GDialog(
            onDismiss = { onFinished(false) }, title = "Connect to GitHub",
            dismissOnOutside = false
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GSpinner()
                Spacer(Modifier.width(12.dp))
                GText("Contacting GitHub…", color = Gx.textDim)
            }
        }
        is ConnectStep.Code -> GDialog(
            onDismiss = { onFinished(false) }, title = "Connect to GitHub",
            dismissOnOutside = false,
            actions = listOf(
                DialogBtn("Copy code", {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("GitHub code", s.dc.userCode))
                    Banners.show("Code copied")
                }),
                DialogBtn("Open GitHub", {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.dc.verificationUri)))
                    } catch (_: Exception) {
                        Banners.show("No browser found", BannerKind.Error)
                    }
                }, primary = true),
            )
        ) {
            GText("Open GitHub and enter this code:", style = Ty.small, color = Gx.textDim)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().clip(Shapes.small).background(Gx.surfaceHigh)
                    .border(1.dp, Gx.border, Shapes.small).padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                GText(
                    s.dc.userCode,
                    style = Ty.title.copy(fontSize = 28.sp, letterSpacing = 4.sp),
                    color = Gx.accent, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GSpinner(size = 18.dp)
                Spacer(Modifier.width(10.dp))
                GText("Waiting for you to authorize…", style = Ty.small, color = Gx.textDim)
            }
        }
        is ConnectStep.Url -> GitHubUrlDialog(
            initial = s.initial,
            onSave = { url ->
                Prefs.githubUrl = url
                onFinished(true)
            },
            onDismiss = { onFinished(true) }
        )
        is ConnectStep.Fail -> GDialog(
            onDismiss = { onFinished(false) }, title = "Couldn't connect",
            cancel = DialogBtn("Close", { onFinished(false) })
        ) {
            GText(s.msg, color = Gx.textDim)
        }
    }
}

/** Asks for the account URL (never a repository URL), validates it and returns the normalised form. */
@Composable
fun GitHubUrlDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var tv by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var err by remember { mutableStateOf<String?>(null) }
    val save = {
        val u = Validate.accountUrl(tv.text)
        if (u == null) err = Validate.accountUrlError(tv.text) else onSave(u)
    }
    GDialog(
        onDismiss = onDismiss,
        title = "Your GitHub account",
        cancel = DialogBtn("Cancel", onDismiss),
        actions = listOf(DialogBtn("Save", save, primary = true)),
    ) {
        GText(
            "Enter the URL of your account, for example https://github.com/username. Not a repository.",
            style = Ty.small, color = Gx.textDim
        )
        Spacer(Modifier.height(12.dp))
        err?.let {
            GText(it, style = Ty.small, color = Gx.danger)
            Spacer(Modifier.height(6.dp))
        }
        GInput(
            tv, { tv = it; err = null }, Modifier.fillMaxWidth(),
            placeholder = "https://github.com/username", error = err != null,
            keyboardType = KeyboardType.Uri, onDone = save
        )
    }
}