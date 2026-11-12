package app.gitex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.graphics.Typeface
import android.os.Environment
import android.os.SystemClock
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** Where the Actions screens should open (set by notifications and by the Failed Builds cards). */
object ActionsNav {
    var tab by mutableIntStateOf(0)
    var highlightRun by mutableLongStateOf(0L)
    var logRepo by mutableStateOf<String?>(null)
    var logRunId by mutableLongStateOf(0L)
}

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

private fun ago(iso: String): String = try {
    val d = (System.currentTimeMillis() - Instant.parse(iso).toEpochMilli()) / 1000
    when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        else -> "${d / 86400} d ago"
    }
} catch (_: Exception) {
    ""
}

// ---------------------------------------------------------------------------
// Downloads live outside the screen so they keep going when you leave it
// ---------------------------------------------------------------------------

class DlState(val done: Long, val total: Long, val bps: Long, val file: File?, val error: String?, val running: Boolean)

object Downloads {
    val states = mutableStateMapOf<Long, DlState>()
    private val jobs = HashMap<Long, Job>()

    private fun nid(a: ArtifactInfo) = 6000 + (a.id % 900).toInt()

    fun start(a: ArtifactInfo) {
        if (jobs[a.id]?.isActive == true) return
        states[a.id] = DlState(0, a.size, 0, null, null, true)
        jobs[a.id] = App.scope.launch {
            var lastN = 0L
            try {
                val f = GitHub.download(a) { d, t, s ->
                    states[a.id] = DlState(d, t, s, null, null, true)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastN > 1000) { lastN = now; progressNotif(a, d, t) }
                }
                states[a.id] = DlState(f.length(), f.length(), 0, f, null, false)
                doneNotif(a, f)
            } catch (e: CancellationException) {
                states.remove(a.id)
                clearNotif(a)
                throw e
            } catch (e: Throwable) {
                states[a.id] = DlState(0, 0, 0, null, e.message ?: "Download failed", false)
                clearNotif(a)
            }
        }
    }

    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }

    private fun progressNotif(a: ArtifactInfo, d: Long, t: Long) {
        val ctx = App.instance
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val pct = if (t > 0) ((d * 100) / t).toInt().coerceIn(0, 100) else 0
        val n = NotificationCompat.Builder(ctx, Notif.CH_DOWNLOADS)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle("Downloading ${a.name}")
            .setContentText("${fmtSize(d)} of ${fmtSize(if (t > 0) t else a.size)}")
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, pct, t <= 0)
            .build()
        try { nm.notify(nid(a), n) } catch (_: SecurityException) { }
    }

    private fun doneNotif(a: ArtifactInfo, f: File) {
        val ctx = App.instance
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, nid(a), Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, Notif.CH_DOWNLOADS)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle("Download finished")
            .setContentText("${f.name} saved to Download/Gitex")
            .setAutoCancel(true).setContentIntent(open).build()
        try { nm.notify(nid(a), n) } catch (_: SecurityException) { }
    }

    private fun clearNotif(a: ArtifactInfo) {
        try { NotificationManagerCompat.from(App.instance).cancel(nid(a)) } catch (_: Exception) { }
    }
}

// ---------------------------------------------------------------------------
// Screen shell
// ---------------------------------------------------------------------------

@Composable
fun ActionsScreen(ws: Workspace, onBack: () -> Unit, onOpenLog: (Run) -> Unit) {
    var tab by remember { mutableIntStateOf(ActionsNav.tab) }
    var refresh by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Gx.bg)) {
        ScreenBar("Actions", onBack, right = {
            if (tab != 1) IconBtn(Ico.Refresh, { refresh++ })
        })
        GTabs(listOf("Artifacts", "Create", "Failed Builds"), tab, { tab = it; ActionsNav.tab = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ArtifactsTab(refresh)
                1 -> CreateTab(ws)
                else -> FailedTab(refresh, onOpenLog)
            }
        }
    }
}

@Composable
private fun StatusChip(run: Run?) {
    val (label, color) = when {
        run == null -> "Unknown" to Gx.textDim
        !run.finished -> (if (run.status == "queued") "Queued" else "Running") to Gx.accent
        run.succeeded -> "Succeeded" to Gx.success
        run.failed -> "Failed" to Gx.danger
        else -> (run.conclusion ?: "Finished").replaceFirstChar { it.uppercase() } to Gx.textDim
    }
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { GText(label, style = Ty.tiny.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1) }
}

@Composable
private fun NoteCard(text: String) = EmptyNote(text, Modifier.padding(top = 24.dp))

// ---------------------------------------------------------------------------
// Artifacts tab: global history, one card per workflow run
// ---------------------------------------------------------------------------

@Composable
private fun ArtifactRow(a: ArtifactInfo) {
    val ctx = LocalContext.current
    val st = Downloads.states[a.id]
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileIcon("${a.name}.zip", size = 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                GText(a.name, maxLines = 1)
                GText(if (a.expired) "${fmtSize(a.size)} · expired" else fmtSize(a.size), style = Ty.small, color = Gx.textDim)
            }
            when {
                a.expired -> GText("Expired", style = Ty.small, color = Gx.textDim)
                st?.running == true -> IconBtn(Ico.Close, { Downloads.cancel(a.id) }, tint = Gx.danger)
                st?.file != null -> IconBtn(Ico.Check, { openExternally(ctx, st.file) }, tint = Gx.success)
                else -> IconBtn(Ico.Download, { Downloads.start(a) }, tint = Gx.accent)
            }
        }
        if (st?.running == true) {
            val frac = if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Gx.border)) {
                Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(50)).background(Gx.accent))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                GText("${fmtSize(st.bps)}/s", style = Ty.tiny, color = Gx.textDim, modifier = Modifier.weight(1f))
                GText(if (st.total > 0) "${(frac * 100).toInt()}%" else "…", style = Ty.tiny, color = Gx.textDim)
            }
        }
        if (st?.error != null) {
            Spacer(Modifier.height(4.dp))
            GText("${st.error}. Tap the arrow to try again.", style = Ty.small, color = Gx.danger)
        }
        if (st?.file != null) {
            Spacer(Modifier.height(4.dp))
            GText("Saved to Download/Gitex/${st.file.name}", style = Ty.tiny, color = Gx.success)
        }
    }
}

@Composable
private fun ArtifactsTab(refresh: Int) {
    val scope = rememberCoroutineScope()
    var runs by remember { mutableStateOf<Map<Long, Run>>(emptyMap()) }
    var arts by remember { mutableStateOf<List<ArtifactInfo>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val connected = Prefs.githubConnected
    val hasLinks = Prefs.allRepoLinks().isNotEmpty()

    LaunchedEffect(refresh, BuildState.refreshTick, connected) {
        val links = Prefs.allRepoLinks()
        if (!connected || links.isEmpty()) { loading = false; arts = emptyList(); return@LaunchedEffect }
        loading = true
        errors = emptyList()
        try {
            val h = GitHub.history(links, 1)
            runs = h.runs; arts = h.artifacts; errors = h.errors; more = h.more; page = 1
        } catch (e: GitHubException) {
            errors = listOf(e.message ?: "Couldn't load your builds")
        }
        loading = false
    }

    val groups = remember(arts, runs) {
        arts.groupBy { it.runId }.map { (id, list) -> Triple(id, runs[id], list) }
            .sortedByDescending { it.second?.createdAt ?: it.third.maxOf { a -> a.createdAt } }
    }
    val active = BuildState.active

    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (active != null) item(key = "active") {
            GCard(Modifier.fillMaxWidth(), selected = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GSpinner()
                    Spacer(Modifier.width(12.dp))
                    Column {
                        GText("Building", style = Ty.label)
                        GText("${active.title} · ${active.repo}", style = Ty.small, color = Gx.textDim, maxLines = 1)
                    }
                }
            }
        }
        for (e in errors) item(key = "err-$e") { GText(e, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(4.dp)) }
        when {
            !connected -> item { NoteCard("Connect your GitHub account in Settings → GitHub to see your builds.") }
            !hasLinks -> item { NoteCard("Push a project from Gitex, and the builds of its repository will appear here.") }
            loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { GSpinner() } }
            groups.isEmpty() && errors.isEmpty() -> item { NoteCard("No artifacts yet. Builds that upload artifacts will show up here.") }
        }
        items(groups, key = { it.first.toString() + it.third.first().id }) { (runId, run, list) ->
            val first = list.first()
            GCard(Modifier.fillMaxWidth(), selected = ActionsNav.highlightRun == runId && runId != 0L) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GText(
                            if (run != null) "${run.name} #${run.number}" else "Build $runId",
                            style = Ty.heading, modifier = Modifier.weight(1f), maxLines = 1
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusChip(run)
                    }
                    GText(
                        "${first.owner}/${first.repo}" + (run?.branch?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = Ty.small, color = Gx.accent, maxLines = 1
                    )
                    if (run != null && run.message.isNotBlank()) GText(run.message, style = Ty.small, color = Gx.textDim, maxLines = 2)
                    GText(ago(run?.createdAt ?: first.createdAt), style = Ty.tiny, color = Gx.textDim)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
                    for (a in list) ArtifactRow(a)
                }
            }
        }
        if (more && !loading) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                if (loadingMore) GSpinner()
                else GButton("Load more", onClick = {
                    scope.launch {
                        loadingMore = true
                        try {
                            val h = GitHub.history(Prefs.allRepoLinks(), page + 1)
                            runs = runs + h.runs
                            arts = (arts + h.artifacts).distinctBy { it.id }
                            more = h.more
                            page++
                        } catch (e: GitHubException) {
                            Banners.show(e.message ?: "Couldn't load more", BannerKind.Error)
                        }
                        loadingMore = false
                    }
                }, primary = false)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Failed builds
// ---------------------------------------------------------------------------

@Composable
private fun FailedTab(refresh: Int, onOpenLog: (Run) -> Unit) {
    val scope = rememberCoroutineScope()
    var runs by remember { mutableStateOf<List<Run>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val connected = Prefs.githubConnected
    val hasLinks = Prefs.allRepoLinks().isNotEmpty()

    LaunchedEffect(refresh, BuildState.refreshTick, connected) {
        val links = Prefs.allRepoLinks()
        if (!connected || links.isEmpty()) { loading = false; runs = emptyList(); return@LaunchedEffect }
        loading = true
        errors = emptyList()
        try {
            val f = GitHub.failedRuns(links, 1)
            runs = f.runs; errors = f.errors; more = f.more; page = 1
        } catch (e: GitHubException) {
            errors = listOf(e.message ?: "Couldn't load your builds")
        }
        loading = false
    }

    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (e in errors) item(key = "err-$e") { GText(e, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(4.dp)) }
        when {
            !connected -> item { NoteCard("Connect your GitHub account in Settings → GitHub to see your builds.") }
            !hasLinks -> item { NoteCard("Push a project from Gitex, and its failed builds will appear here.") }
            loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { GSpinner() } }
            runs.isEmpty() && errors.isEmpty() -> item { NoteCard("No failed builds. 🎉") }
        }
        items(runs, key = { it.id }) { r ->
            GCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GText("${r.name} #${r.number}", style = Ty.heading, modifier = Modifier.weight(1f), maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        StatusChip(r)
                    }
                    GText("${r.fullName} · ${r.branch}", style = Ty.small, color = Gx.accent, maxLines = 1)
                    if (r.message.isNotBlank()) GText(r.message, style = Ty.body, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
                    GText(
                        "${r.sha.take(7)} · ${r.event} · ${ago(r.createdAt)}",
                        style = Ty.tiny, color = Gx.textDim, modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    GButton("View Log", onClick = { onOpenLog(r) }, modifier = Modifier.fillMaxWidth(), primary = false, glow = false, height = 44.dp)
                }
            }
        }
        if (more && !loading) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                if (loadingMore) GSpinner()
                else GButton("Load more", onClick = {
                    scope.launch {
                        loadingMore = true
                        try {
                            val f = GitHub.failedRuns(Prefs.allRepoLinks(), page + 1)
                            runs = (runs + f.runs).distinctBy { it.id }.sortedByDescending { it.createdAt }
                            more = f.more
                            page++
                        } catch (e: GitHubException) {
                            Banners.show(e.message ?: "Couldn't load more", BannerKind.Error)
                        }
                        loadingMore = false
                    }
                }, primary = false)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Failure log viewer
// ---------------------------------------------------------------------------

private sealed interface LogState {
    data object Loading : LogState
    class Ready(val log: FailedLog) : LogState
    class Err(val msg: String) : LogState
}

@Composable
fun LogScreen(repo: String?, runId: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<LogState>(LogState.Loading) }

    LaunchedEffect(repo, runId) {
        val parts = repo?.split("/")
        if (parts == null || parts.size != 2) { state = LogState.Err("This build can't be found."); return@LaunchedEffect }
        state = LogState.Loading
        state = try {
            LogState.Ready(GitHub.failedStepLog(parts[0], parts[1], runId))
        } catch (e: GitHubException) {
            LogState.Err(e.message ?: "Couldn't load the log")
        }
    }

    val ready = state as? LogState.Ready
    Column(Modifier.fillMaxSize().background(Gx.bg)) {
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
        when (val s = state) {
            LogState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { GSpinner() }
            is LogState.Err -> Box(Modifier.weight(1f).fillMaxWidth()) { EmptyNote(s.msg) }
            is LogState.Ready -> {
                Column(Modifier.fillMaxWidth().background(Gx.surface).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    GText("${s.log.job} → ${s.log.step}", style = Ty.label, maxLines = 2)
                    if (!s.log.exact) {
                        GText(
                            "Gitex couldn't isolate this step exactly, so the end of the job's log is shown instead.",
                            style = Ty.tiny, color = Gx.textDim
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
                val fg = Gx.text.toArgb()
                val hl = Gx.accent.copy(alpha = 0.35f).toArgb()
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().interactive(),
                    factory = { c ->
                        val pad = (14 * c.resources.displayMetrics.density).toInt()
                        val tv = TextView(c).apply {
                            typeface = Typeface.MONOSPACE
                            textSize = 12f
                            setTextIsSelectable(true)
                            setPadding(pad, pad, pad, pad)
                        }
                        ScrollView(c).apply { addView(tv) }
                    },
                    update = { sv ->
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

// ---------------------------------------------------------------------------
// Create tab: internal storage explorer + "create a project from a pasted tree"
// ---------------------------------------------------------------------------

@Composable
private fun CreateTab(ws: Workspace) {
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember { mutableStateOf(root) }
    var version by remember { mutableIntStateOf(0) }
    var dlg by remember { mutableStateOf(false) }
    val items by produceState(emptyList<File>(), dir, version) {
        value = withContext(Dispatchers.IO) { Fs.listDir(dir) }
    }
    BackHandler(dir.absolutePath != root.absolutePath) { dir = dir.parentFile ?: root }

    val rel = dir.absolutePath.removePrefix(root.absolutePath).trim('/')
    val title = when {
        rel.isEmpty() -> "internal storage"
        rel.count { it == '/' } >= 2 -> "…/" + rel.split('/').takeLast(2).joinToString("/")
        else -> rel
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Gx.surface).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rel.isNotEmpty()) IconBtn(Ico.Back, { dir = dir.parentFile ?: root }, size = 38.dp, iconSize = 20.dp)
            else Spacer(Modifier.width(8.dp))
            GText(title, style = Ty.heading, modifier = Modifier.weight(1f), maxLines = 1)
            GButton("Create", onClick = { dlg = true }, height = 40.dp, glow = false)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (items.isEmpty()) item { EmptyNote("This folder is empty") }
            items(items, key = { it.absolutePath }) { f ->
                val isDir = f.isDirectory
                GCard(
                    Modifier.fillMaxWidth(),
                    onClick = if (isDir) { { dir = f } } else null, // files are inert
                    padding = PaddingValues(horizontal = 12.dp, vertical = 11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isDir) FolderIcon(f.name, size = 24.dp) else FileIcon(f.name, size = 24.dp)
                        Spacer(Modifier.width(12.dp))
                        GText(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (dlg) CreateTreeDialog(dir, onClose = { dlg = false }, onDone = { dlg = false; version++; ws.bump() })
}

@Composable
private fun CreateTreeDialog(base: File, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Reading the tree…") }
    var err by remember { mutableStateOf<String?>(null) }
    var needKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf(TextFieldValue("")) }
    var keyErr by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<Pair<List<TreeEntry>, String>?>(null) }
    var problems by remember { mutableStateOf<List<String>?>(null) }

    fun build(entries: List<TreeEntry>) {
        scope.launch {
            busy = true
            status = "Checking…"
            when (val p = withContext(Dispatchers.Default) { TreePlanner.plan(entries, base) }) {
                is PlanResult.Problems -> { problems = p.list; busy = false }
                is PlanResult.Ok -> {
                    val r = TreeCreator.execute(p.plan) { d, t -> status = "Creating $d of $t…" }
                    busy = false
                    if (r.error != null) err = r.error
                    else {
                        Banners.show("Created ${r.created} items", BannerKind.Success)
                        onDone()
                    }
                }
            }
        }
    }

    fun proceed(entries: List<TreeEntry>) {
        val root = TreePlanner.commonRoot(entries)
        if (root != null) choice = entries to root else build(entries)
    }

    fun handle(r: Interpret) {
        when (r) {
            is Interpret.Entries -> proceed(r.entries)
            Interpret.NeedsKey -> needKey = true
            is Interpret.Failed -> err = r.msg
        }
    }

    GDialog(
        onDismiss = { if (!busy) onClose() },
        title = "Create from a tree",
        dismissOnOutside = false,
        cancel = DialogBtn("Cancel", { if (!busy) onClose() }, enabled = !busy),
        actions = listOf(DialogBtn("Create", {
            err = null
            scope.launch {
                busy = true; status = "Reading the tree…"
                val r = TreeCreator.interpret(text.text)
                busy = false
                handle(r)
            }
        }, primary = true, enabled = text.text.isNotBlank() && !busy))
    ) {
        GText("Paste your repository tree in any common format. Folders and files are created in this folder:", style = Ty.small, color = Gx.textDim)
        GText(base.absolutePath, style = Ty.tiny, color = Gx.accent, maxLines = 2, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        err?.let { GText(it, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(bottom = 6.dp)) }
        GInput(
            text, { text = it; err = null }, Modifier.fillMaxWidth(), placeholder = "Paste the tree here",
            singleLine = false, minLines = 8, maxLines = 12, mono = true, enabled = !busy
        )
        if (busy) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GSpinner(size = 18.dp)
                Spacer(Modifier.width(10.dp))
                GText(status, style = Ty.small, color = Gx.textDim)
            }
        }
    }

    if (needKey) {
        GDialog(
            onDismiss = { needKey = false },
            title = "Gemini API key",
            actions = listOf(DialogBtn("Save and continue", {
                if (keyInput.text.trim().length < 20) keyErr = "That doesn't look like an API key"
                else {
                    Prefs.saveGeminiKey(keyInput.text)
                    needKey = false
                    scope.launch {
                        busy = true; status = "Asking the AI…"
                        val r = TreeCreator.aiInterpret(Prefs.geminiKey() ?: "", text.text)
                        busy = false
                        handle(r)
                    }
                }
            }, primary = true))
        ) {
            GText(
                "This tree isn't in a format Gitex can read by itself, so it asks Google's Gemini model. " +
                    "Paste your own free key from aistudio.google.com/apikey. It is stored encrypted on this phone, " +
                    "and only the text you pasted is sent.",
                style = Ty.small, color = Gx.textDim
            )
            Spacer(Modifier.height(10.dp))
            keyErr?.let { GText(it, style = Ty.small, color = Gx.danger, modifier = Modifier.padding(bottom = 6.dp)) }
            GInput(keyInput, { keyInput = it; keyErr = null }, Modifier.fillMaxWidth(), placeholder = "API key", error = keyErr != null, mono = true)
        }
    }

    choice?.let { (entries, root) ->
        GDialog(
            onDismiss = { choice = null },
            title = "Where should it go?",
            actions = listOf(
                DialogBtn("Contents here", { choice = null; build(TreePlanner.stripRoot(entries, root)) }),
                DialogBtn("New \"$root\" folder", { choice = null; build(entries) }, primary = true),
            )
        ) {
            GText(
                "Your tree starts with a folder called \"$root\". Create that folder here, or put what's inside it directly into the current folder?",
                color = Gx.textDim
            )
        }
    }

    problems?.let { list ->
        GDialog(
            onDismiss = { problems = null },
            title = "Nothing was created",
            cancel = DialogBtn("Close", { problems = null })
        ) {
            GText("Gitex stopped before changing anything, so your existing files are safe:", style = Ty.small, color = Gx.textDim)
            Spacer(Modifier.height(8.dp))
            for (p in list) GText("• $p", style = Ty.small, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}