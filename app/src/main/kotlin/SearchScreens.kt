package app.gitex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Queries live here so they survive leaving and re-opening the search screens. */
object SearchMemory {
    var fileQuery by mutableStateOf(TextFieldValue(""))
    var projectQuery by mutableStateOf(TextFieldValue(""))
}

private class FileHit(val line: Int, val col: Int, val text: String)
private class ProjHit(val path: String, val line: Int, val col: Int, val text: String)

private const val MAX_FILE_HITS = 5_000
private const val MAX_PROJECT_HITS = 1_500
private const val MAX_HITS_PER_FILE = 60
private const val MAX_SEARCH_BYTES = 1_500_000L

/**
 * One hit per line, starting at the first match in that line (0-based line and column).
 * Single linear pass: newlines are counted incrementally, never re-scanned.
 */
private fun scan(s: String, q: String, maxHits: Int, onHit: (line: Int, col: Int, text: String) -> Unit) {
    var from = 0
    var line = 0
    var counted = 0
    var scanned = 0 // newlines before this index are already counted in `line`
    while (counted < maxHits) {
        val i = s.indexOf(q, from, ignoreCase = true)
        if (i < 0) break
        for (k in scanned until i) if (s[k] == '\n') line++
        scanned = i
        val ls = s.lastIndexOf('\n', i - 1) + 1
        var le = s.indexOf('\n', i)
        if (le < 0) le = s.length
        var end = le
        if (end > ls && s[end - 1] == '\r') end--
        onHit(line, i - ls, s.substring(ls, end))
        counted++
        from = le + 1
        if (from > s.length) break
    }
}

private fun snippet(text: String, col: Int, len: Int, hl: Color, hlBg: Color): AnnotatedString {
    val s = text.substring(col.coerceIn(0, text.length)).trimEnd().take(240)
    return buildAnnotatedString {
        append(s)
        val e = len.coerceAtMost(s.length)
        if (e > 0) addStyle(SpanStyle(color = hl, background = hlBg), 0, e)
    }
}

@Composable
private fun SearchBar(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    placeholder: String,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: Exception) { }
    }
    Column(Modifier.fillMaxWidth().background(Gx.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ico.Close, onClose)
            Spacer(Modifier.width(4.dp))
            GInput(
                value, onValue, Modifier.weight(1f), placeholder = placeholder, focusRequester = focus,
                imeAction = ImeAction.Search,
                leading = { GIcon(Ico.Search, size = 20.dp, tint = Gx.textDim) }
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Gx.border))
    }
}

// ---------------------------------------------------------------------------
// Search in file
// ---------------------------------------------------------------------------

@Composable
fun SearchInFileScreen(
    ws: Workspace,
    onClose: () -> Unit,
    onPick: (line: Int, col: Int, len: Int) -> Unit,
) {
    val q = SearchMemory.fileQuery
    var hits by remember { mutableStateOf<List<FileHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val hlBg = Gx.accent.copy(alpha = 0.35f)

    LaunchedEffect(q.text, ws.current) {
        val query = q.text
        if (query.isEmpty()) { hits = emptyList(); busy = false; return@LaunchedEffect }
        busy = true
        delay(140)
        val content = ws.buffer(ws.current)?.content
        val result = withContext(Dispatchers.Default) {
            val out = ArrayList<FileHit>()
            val s = content?.toString() ?: ""
            scan(s, query, MAX_FILE_HITS) { line, col, text -> out.add(FileHit(line, col, text)) }
            out
        }
        hits = result
        busy = false
    }

    Column(Modifier.fillMaxSize().background(Gx.bg).imePadding()) {
        SearchBar(q, { SearchMemory.fileQuery = it }, "Search in file", onClose)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                q.text.isEmpty() -> EmptyNote("type to search")
                hits.isEmpty() && !busy -> EmptyNote("nothing match")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hits, key = { it.line }) { h ->
                        GCard(
                            Modifier.fillMaxWidth(),
                            onClick = { onPick(h.line, h.col, q.text.length) },
                            padding = PaddingValues(0.dp)
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().background(Gx.surfaceHigh).padding(horizontal = 12.dp, vertical = 7.dp)
                                ) { GText("[Line ${h.line + 1}]", style = Ty.small, color = Gx.accent, maxLines = 1) }
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(
                                        snippet(h.text, h.col, q.text.length, Gx.text, hlBg),
                                        color = Gx.text,
                                        style = Ty.small.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (busy) GSpinner(Modifier.align(Alignment.TopEnd).padding(14.dp), 18.dp)
        }
    }
}

// ---------------------------------------------------------------------------
// Find in project
// ---------------------------------------------------------------------------

@Composable
fun FindInProjectScreen(
    ws: Workspace,
    onClose: () -> Unit,
    onPick: (path: String, line: Int, col: Int, len: Int) -> Unit,
) {
    val q = SearchMemory.projectQuery
    var hits by remember { mutableStateOf<List<ProjHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val hlBg = Gx.accent.copy(alpha = 0.35f)

    LaunchedEffect(q.text, ws.projectRoot) {
        val query = q.text
        if (query.isEmpty() || ws.projectRoot == null) { hits = emptyList(); busy = false; return@LaunchedEffect }
        busy = true
        hits = emptyList()
        delay(220)
        val files = ws.projectFiles()
        val acc = ArrayList<ProjHit>()
        withContext(Dispatchers.IO) {
            for (f in files) {
                ensureActive()
                if (acc.size >= MAX_PROJECT_HITS) break
                if (FileKinds.kindOf(f.name) != FileKind.TEXT) continue
                val path = f.absolutePath
                val open = if (ws.dirty[path] == true) ws.buffer(path)?.content?.toString() else null
                val text = open ?: run {
                    if (f.length() > MAX_SEARCH_BYTES) return@run null
                    (Fs.readText(f) as? Fs.ReadResult.Ok)?.text
                } ?: continue
                val before = acc.size
                scan(text, query, MAX_HITS_PER_FILE) { line, col, t -> acc.add(ProjHit(path, line, col, t)) }
                if (acc.size != before) {
                    // publish progressively so results appear while the scan continues
                    val snapshot = ArrayList(acc)
                    withContext(Dispatchers.Main) { hits = snapshot }
                }
            }
        }
        hits = ArrayList(acc)
        busy = false
    }

    Column(Modifier.fillMaxSize().background(Gx.bg).imePadding()) {
        SearchBar(q, { SearchMemory.projectQuery = it }, "Find in project", onClose)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                q.text.isEmpty() -> EmptyNote("type to search")
                ws.projectRoot == null -> EmptyNote("Open a folder first")
                hits.isEmpty() && !busy -> EmptyNote("nothing match")
                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hits, key = { "${it.path}:${it.line}" }) { h ->
                        val name = File(h.path).name
                        GCard(
                            Modifier.fillMaxWidth(),
                            onClick = { onPick(h.path, h.line, h.col, q.text.length) },
                            padding = PaddingValues(0.dp)
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().background(Gx.surfaceHigh).padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FileIcon(name, size = 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                    GText(name, style = Ty.small, modifier = Modifier.weight(1f), maxLines = 1)
                                    Spacer(Modifier.width(8.dp))
                                    GText("Line ${h.line + 1}", style = Ty.small, color = Gx.accent, maxLines = 1)
                                }
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(
                                        snippet(h.text, h.col, q.text.length, Gx.text, hlBg),
                                        color = Gx.text,
                                        style = Ty.small.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (busy) GSpinner(Modifier.align(Alignment.TopEnd).padding(14.dp), 18.dp)
        }
    }
}