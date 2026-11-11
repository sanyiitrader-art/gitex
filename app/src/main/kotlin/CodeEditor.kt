package app.gitex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------
// Small reflection helpers. Sora's cosmetic setters and colour-id constants have
// moved between 0.2x releases, so optional tweaks go through these and can never
// break the build; the core calls below use the API directly.
// ---------------------------------------------------------------------------

private fun coerce(v: Any, t: Class<*>): Any = when (t) {
    Int::class.javaPrimitiveType, Int::class.javaObjectType -> (v as Number).toInt()
    Float::class.javaPrimitiveType, Float::class.javaObjectType -> (v as Number).toFloat()
    Long::class.javaPrimitiveType, Long::class.javaObjectType -> (v as Number).toLong()
    else -> v
}

private fun poke(target: Any?, name: String, value: Any) {
    if (target == null) return
    val setter = "set" + name.replaceFirstChar { it.uppercase() }
    try {
        for (m in target.javaClass.methods) {
            if (m.name == setter && m.parameterTypes.size == 1) {
                m.invoke(target, coerce(value, m.parameterTypes[0]))
                return
            }
        }
        val f = target.javaClass.getField(name)
        f.set(target, coerce(value, f.type))
    } catch (_: Throwable) {
    }
}

private fun callNoArg(target: Any?, name: String) {
    try {
        target?.javaClass?.getMethod(name)?.invoke(target)
    } catch (_: Throwable) {
    }
}

private fun schemeId(name: String): Int = try {
    EditorColorScheme::class.java.getField(name).getInt(null)
} catch (_: Throwable) {
    -1
}

private fun setScheme(cs: EditorColorScheme, name: String, argb: Int) {
    val id = schemeId(name)
    if (id >= 0) try { cs.setColor(id, argb) } catch (_: Throwable) { }
}

// ---------------------------------------------------------------------------
// Highlighting, bracket pairs and indentation guides
// ---------------------------------------------------------------------------

private class SpanData {
    var line = IntArray(256)
    var col = IntArray(256)
    var kind = ByteArray(256)
    var n = 0
    fun add(l: Int, c: Int, k: Int) {
        if (n == line.size) {
            line = line.copyOf(n * 2); col = col.copyOf(n * 2); kind = kind.copyOf(n * 2)
        }
        line[n] = l; col[n] = c; kind[n] = k.toByte(); n++
    }
}

private class BracketData(val line: IntArray, val col: IntArray, val match: IntArray) {
    /** Index of the bracket at exactly (l, c), or -1. Entries are sorted by position. */
    fun find(l: Int, c: Int): Int {
        if (c < 0) return -1
        var lo = 0
        var hi = line.size - 1
        val key = (l.toLong() shl 32) or c.toLong()
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = (line[mid].toLong() shl 32) or col[mid].toLong()
            if (k < key) lo = mid + 1 else if (k > key) hi = mid - 1 else return mid
        }
        return -1
    }
}

private val styleExec = Executors.newSingleThreadExecutor { r -> Thread(r, "gitex-style").apply { isDaemon = true } }

class LexAnalyzer(
    private val lang: LangDef?,
    tokenIds: IntArray,
    bracketFgId: Int,
    bracketBgId: Int,
    private val highlight: Boolean,
    private val guides: Boolean,
    private val bracketsOn: Boolean,
    private val indentW: Int,
) : SimpleAnalyzeManager<Any>() {

    private val tok = LongArray(Tok.COUNT) { TextStyle.makeStyle(tokenIds[it]) }
    private val bracketStyle = TextStyle.makeStyle(bracketFgId, bracketBgId, true, false, false, false)

    @Volatile private var data: SpanData? = null
    @Volatile private var brackets: BracketData? = null
    @Volatile private var blocks: List<CodeBlock> = emptyList()
    @Volatile private var lineCount = 1
    private val ticket = AtomicInteger()
    private var lastPair = -1L

    override fun analyze(text: StringBuilder, delegate: SimpleAnalyzeManager.Delegate<Any>): Styles {
        val d = SpanData()
        val l = lang
        if (highlight && l != null) {
            Lexer.run(text, l, { delegate.isCancelled }) { line, col, kind -> d.add(line, col, kind) }
        }
        if (d.n == 0) d.add(0, 0, Tok.PLAIN)
        var lines = 1
        for (i in 0 until text.length) if (text[i] == '\n') lines++
        lineCount = lines
        data = d
        brackets = if (bracketsOn && highlight && l != null) findBrackets(text, d) else null
        blocks = if (guides) computeBlocks(text) else emptyList()
        lastPair = -1L
        return build(d, -1L, -1L)
    }

    private fun pack(line: Int, col: Int) = (line.toLong() shl 32) or col.toLong()

    private fun build(d: SpanData, a: Long, b: Long): Styles {
        val mb = MappedSpans.Builder()
        val aL = if (a >= 0) (a shr 32).toInt() else -1
        val aC = if (a >= 0) (a and 0xFFFFFFFFL).toInt() else -1
        val bL = if (b >= 0) (b shr 32).toInt() else -1
        val bC = if (b >= 0) (b and 0xFFFFFFFFL).toInt() else -1
        for (i in 0 until d.n) {
            val line = d.line[i]
            val col = d.col[i]
            val k = d.kind[i].toInt()
            val st = tok[k]
            if (k == Tok.PLAIN && (line == aL || line == bL)) {
                val end = if (i + 1 < d.n && d.line[i + 1] == line) d.col[i + 1] else Int.MAX_VALUE
                var c1 = -1
                var c2 = -1
                if (line == aL && aC >= col && aC < end) c1 = aC
                if (line == bL && bC >= col && bC < end) { if (c1 < 0) c1 = bC else c2 = bC }
                if (c2 in 0 until c1) { val t = c1; c1 = c2; c2 = t }
                if (c1 < 0) {
                    mb.addIfNeeded(line, col, st)
                } else {
                    var pos = col
                    for (pc in intArrayOf(c1, c2)) {
                        if (pc < 0) continue
                        if (pc > pos) mb.addIfNeeded(line, pos, st)
                        mb.addIfNeeded(line, pc, bracketStyle)
                        pos = pc + 1
                    }
                    mb.addIfNeeded(line, pos, st)
                }
            } else {
                mb.addIfNeeded(line, col, st)
            }
        }
        mb.determine(lineCount - 1)
        val styles = Styles(mb.build())
        val bl = blocks
        if (bl.isNotEmpty()) poke(styles, "blocks", ArrayList(bl))
        callNoArg(styles, "finishBuilding")
        return styles
    }

    /** Call when the caret moves. line < 0 clears the highlight. Cheap: runs on a background thread. */
    fun update(line: Int, col: Int) {
        val br = brackets ?: return
        val d = data ?: return
        val req = ticket.incrementAndGet()
        styleExec.execute {
            if (ticket.get() != req) return@execute
            var idx = if (line >= 0) br.find(line, col - 1) else -1
            if (idx < 0 && line >= 0) idx = br.find(line, col)
            val m = if (idx >= 0) br.match[idx] else -1
            val key = if (m >= 0) pack(br.line[idx], br.col[idx]) xor (pack(br.line[m], br.col[m]) * 31) else -1L
            if (key == lastPair) return@execute
            lastPair = key
            val st = if (m >= 0) build(d, pack(br.line[idx], br.col[idx]), pack(br.line[m], br.col[m]))
            else build(d, -1L, -1L)
            if (ticket.get() == req) receiver?.setStyles(this, st)
        }
    }

    /** Bracket matching that ignores brackets inside strings and comments (they are not PLAIN tokens). */
    private fun findBrackets(text: CharSequence, d: SpanData): BracketData {
        val ls = ArrayList<Int>()
        val cs = ArrayList<Int>()
        val chs = ArrayList<Char>()
        var si = 0
        var line = 0
        var col = 0
        var kind = Tok.PLAIN
        for (i in 0 until text.length) {
            while (si < d.n && (d.line[si] < line || (d.line[si] == line && d.col[si] <= col))) {
                kind = d.kind[si].toInt(); si++
            }
            val ch = text[i]
            if (ch == '\n') { line++; col = 0; continue }
            if (kind == Tok.PLAIN && (ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{' || ch == '}')) {
                ls.add(line); cs.add(col); chs.add(ch)
            }
            col++
        }
        val n = ls.size
        val match = IntArray(n) { -1 }
        val stacks = Array(3) { ArrayList<Int>() }
        for (i in 0 until n) {
            val ch = chs[i]
            val t = when (ch) { '(', ')' -> 0; '[', ']' -> 1; else -> 2 }
            if (ch == '(' || ch == '[' || ch == '{') stacks[t].add(i)
            else if (stacks[t].isNotEmpty()) {
                val o = stacks[t].removeAt(stacks[t].lastIndex)
                match[o] = i; match[i] = o
            }
        }
        return BracketData(ls.toIntArray(), cs.toIntArray(), match)
    }

    /** One block per indentation level; Sora draws these as the vertical guide lines. */
    private fun computeBlocks(text: CharSequence): List<CodeBlock> {
        val n = text.length
        var iw = IntArray(1024)
        var ic = IntArray(1024)
        var lines = 0
        var i = 0
        while (i <= n) {
            if (lines == iw.size) { iw = iw.copyOf(lines * 2); ic = ic.copyOf(lines * 2) }
            var w = 0
            var c = 0
            var j = i
            while (j < n && (text[j] == ' ' || text[j] == '\t')) {
                w += if (text[j] == '\t') indentW else 1
                c++; j++
            }
            val blank = j >= n || text[j] == '\n' || text[j] == '\r'
            iw[lines] = if (blank) -1 else w
            ic[lines] = c
            lines++
            while (j < n && text[j] != '\n') j++
            i = j + 1
        }
        val out = ArrayList<CodeBlock>()
        val stStart = ArrayList<Int>()
        val stW = ArrayList<Int>()
        var prev = -1
        var prevW = 0
        fun close(start: Int, end: Int) {
            if (end > start && out.size < 50_000) {
                val b = CodeBlock()
                b.startLine = start; b.endLine = end
                b.startColumn = ic[start]; b.endColumn = ic[start]
                out.add(b)
            }
        }
        for (ln in 0 until lines) {
            val w = iw[ln]
            if (w < 0) continue
            while (stW.isNotEmpty() && w <= stW.last()) {
                close(stStart.removeAt(stStart.lastIndex), prev); stW.removeAt(stW.lastIndex)
            }
            if (prev >= 0 && w > prevW) { stStart.add(prev); stW.add(prevW) }
            prev = ln; prevW = w
        }
        while (stStart.isNotEmpty()) {
            close(stStart.removeAt(stStart.lastIndex), prev); stW.removeAt(stW.lastIndex)
        }
        return out
    }
}

class LexLanguage(val analyzer: LexAnalyzer) : EmptyLanguage() {
    override fun getAnalyzeManager(): AnalyzeManager = analyzer
}

// ---------------------------------------------------------------------------
// Handle used by the screens to drive the editor
// ---------------------------------------------------------------------------

class EditorHandle {
    class Jump(val path: String, val line: Int, val col: Int, val len: Int)

    internal var ed: CodeEditor? = null
    var lang: LangDef? = null
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)

    /** Set by search screens; applied as soon as the editor shows that file. */
    var pendingJump by mutableStateOf<Jump?>(null)

    fun refreshUndo() {
        val e = ed ?: return
        canUndo = e.canUndo()
        canRedo = e.canRedo()
    }

    fun undo() { ed?.undo(); refreshUndo() }
    fun redo() { ed?.redo(); refreshUndo() }

    private fun unit(): String = if (lang?.id == "makefile") "\t" else " ".repeat(Prefs.indentWidth)

    fun tab() = typeText(unit())

    fun typeText(s: String) {
        ed?.commitText(s)
        refreshUndo()
    }

    /** Inserts open+close with the caret between, or wraps the current selection. */
    fun typePair(open: String, close: String) {
        val e = ed ?: return
        val c = e.cursor
        if (c.isSelected) {
            val sel = e.text.substring(c.left, c.right)
            e.commitText(open + sel + close)
        } else {
            e.commitText(open + close)
            val cc = e.cursor
            e.setSelection(cc.leftLine, (cc.leftColumn - close.length).coerceAtLeast(0))
        }
        refreshUndo()
    }

    fun lineCount(): Int = ed?.text?.lineCount ?: 0

    /** 1-based line number. Returns false when out of range. */
    fun gotoLine(line1: Int): Boolean {
        val e = ed ?: return false
        if (line1 < 1 || line1 > e.text.lineCount) return false
        e.setSelection(line1 - 1, 0)
        callNoArg(e, "ensureSelectionVisible")
        return true
    }

    fun select(line: Int, col: Int, len: Int) {
        val e = ed ?: return
        if (line < 0 || line >= e.text.lineCount) return
        val max = e.text.getColumnCount(line)
        val c = col.coerceIn(0, max)
        e.setSelectionRegion(line, c, line, (c + len).coerceAtMost(max))
        callNoArg(e, "ensureSelectionVisible")
    }

    /** Replaces every occurrence as ONE undoable edit. Returns the number replaced. */
    fun replaceAll(find: String, with: String, matchCase: Boolean = true): Int {
        val e = ed ?: return 0
        if (find.isEmpty()) return 0
        val t = e.text
        val s = t.toString()
        var count = 0
        var from = 0
        while (true) {
            val i = s.indexOf(find, from, ignoreCase = !matchCase)
            if (i < 0) break
            count++; from = i + find.length
        }
        if (count == 0) return 0
        val replaced = s.replace(find, with, ignoreCase = !matchCase)
        val last = t.lineCount - 1
        t.replace(0, 0, last, t.getColumnCount(last), replaced)
        refreshUndo()
        return count
    }

    private fun lineRange(e: CodeEditor): IntRange {
        val c = e.cursor
        val sl = c.leftLine
        var el = c.rightLine
        if (c.isSelected && c.rightColumn == 0 && el > sl) el--
        return sl..el
    }

    fun indentLines() {
        val e = ed ?: return
        val t = e.text
        val u = unit()
        t.beginBatchEdit()
        for (ln in lineRange(e)) if (t.getColumnCount(ln) > 0) t.insert(ln, 0, u)
        t.endBatchEdit()
        refreshUndo()
    }

    fun outdentLines() {
        val e = ed ?: return
        val t = e.text
        val w = Prefs.indentWidth
        t.beginBatchEdit()
        for (ln in lineRange(e)) {
            val s = t.getLineString(ln)
            var k = 0
            if (s.startsWith("\t")) k = 1
            else while (k < w && k < s.length && s[k] == ' ') k++
            if (k > 0) t.delete(ln, 0, ln, k)
        }
        t.endBatchEdit()
        refreshUndo()
    }

    /** Returns an error message, or null on success. */
    fun toggleComment(): String? {
        val e = ed ?: return null
        val p = lang?.commentPrefix ?: return "This file type has no line-comment syntax"
        val t = e.text
        val r = lineRange(e)
        val body = r.filter { t.getLineString(it).isNotBlank() }
        if (body.isEmpty()) return null
        val allCommented = body.all { t.getLineString(it).trimStart().startsWith(p) }
        t.beginBatchEdit()
        if (allCommented) {
            for (ln in body) {
                val s = t.getLineString(ln)
                val at = s.indexOf(p)
                val extra = if (s.startsWith(" ", at + p.length)) 1 else 0
                t.delete(ln, at, ln, at + p.length + extra)
            }
        } else {
            val col = body.minOf { ln ->
                val s = t.getLineString(ln); s.length - s.trimStart().length
            }
            for (ln in body) t.insert(ln, col, "$p ")
        }
        t.endBatchEdit()
        refreshUndo()
        return null
    }

    fun duplicateLine() {
        val e = ed ?: return
        val t = e.text
        val r = lineRange(e)
        val txt = r.joinToString("\n") { t.getLineString(it) }
        t.insert(r.last, t.getColumnCount(r.last), "\n$txt")
        refreshUndo()
    }

    fun deleteLine() {
        val e = ed ?: return
        val t = e.text
        val r = lineRange(e)
        t.beginBatchEdit()
        if (r.last + 1 < t.lineCount) t.delete(r.first, 0, r.last + 1, 0)
        else if (r.first > 0) t.delete(r.first - 1, t.getColumnCount(r.first - 1), r.last, t.getColumnCount(r.last))
        else t.delete(0, 0, r.last, t.getColumnCount(r.last))
        t.endBatchEdit()
        refreshUndo()
    }
}

// ---------------------------------------------------------------------------
// The editor view
// ---------------------------------------------------------------------------

private class Applied(var buffer: BufferState) {
    var content: Content? = null
    var ver = -1
    var sig = ""
    var fontKey = ""
    var settingText = false
    var analyzer: LexAnalyzer? = null
}

@Composable
fun CodeEditorView(
    buffer: BufferState,
    ws: Workspace,
    handle: EditorHandle,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val bg = Gx.bg
    val fg = Gx.text
    val dim = Gx.textDim
    val accent = Gx.accent
    val scheme = Syntax.byId(Prefs.syntaxThemeId)
    val tok = scheme.colors(bg, fg)
    val fontKey = Prefs.fontKey
    val fontSize = Prefs.fontSize
    val smart = Prefs.smartActions
    val guides = Prefs.indentGuides && !buffer.big
    val brackets = Prefs.bracketHighlight && !buffer.big
    val highlight = Prefs.syntaxHighlight && !buffer.big
    val currentLine = Prefs.highlightCurrentLine
    val indentW = Prefs.indentWidth
    val content = buffer.content
    val ver = ws.contentVersion
    val lang = Languages.forName(java.io.File(buffer.path).name)

    AndroidView(
        modifier = modifier.interactive(),
        factory = { c ->
            val ed = CodeEditor(c)
            val ap = Applied(buffer)
            ed.tag = ap
            ed.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                val a = ed.tag as? Applied
                if (a != null && !a.settingText) {
                    ws.onEdited(a.buffer.path)
                    handle.refreshUndo()
                }
            }
            ed.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
                val a = ed.tag as? Applied
                if (a != null) {
                    val cur = ed.cursor
                    a.buffer.cursorLine = cur.leftLine
                    a.buffer.cursorCol = cur.leftColumn
                    if (cur.isSelected) a.analyzer?.update(-1, 0)
                    else a.analyzer?.update(cur.leftLine, cur.leftColumn)
                }
            }
            poke(ed, "wordwrap", false)
            poke(ed, "lineNumberEnabled", true)
            poke(ed, "scalable", false)
            ed
        },
        update = { ed ->
            val ap = ed.tag as Applied
            ap.buffer = buffer

            if (ap.fontKey != fontKey) {
                val tf = Fonts.typeface(ctx, fontKey)
                poke(ed, "typefaceText", tf)
                poke(ed, "typefaceLineNumber", tf)
                ap.fontKey = fontKey
            }
            poke(ed, "textSize", fontSize)
            poke(ed, "tabWidth", indentW)
            poke(ed, "blockLineEnabled", guides)
            val props = ed.props
            poke(props, "symbolPairAutoCompletion", smart)
            poke(props, "autoIndent", smart)
            poke(props, "deleteMultiSpaces", if (smart) -1 else 1)
            poke(props, "deleteEmptyLineFast", smart)

            if (content != null && (ap.content !== content || ap.ver != ver)) {
                ap.settingText = true
                try { ed.setText(content) } finally { ap.settingText = false }
                ap.content = content
                ap.ver = ver
                ap.sig = ""
                try {
                    val ln = buffer.cursorLine.coerceIn(0, content.lineCount - 1)
                    ed.setSelection(ln, buffer.cursorCol.coerceIn(0, content.getColumnCount(ln)))
                } catch (_: Throwable) {
                }
            }

            val sig = "${lang?.id}|$highlight|$guides|$brackets|$indentW|${scheme.id}|${bg.toArgb()}|${fg.toArgb()}|${accent.toArgb()}|$currentLine"
            if (ap.sig != sig) {
                ap.sig = sig
                val cs = ed.colorScheme
                val ids = intArrayOf(
                    schemeId("TEXT_NORMAL"), schemeId("KEYWORD"), schemeId("ATTRIBUTE_NAME"),
                    schemeId("LITERAL"), schemeId("ATTRIBUTE_VALUE"), schemeId("COMMENT"),
                    schemeId("FUNCTION_NAME"),
                )
                for (i in ids.indices) if (ids[i] >= 0) try { cs.setColor(ids[i], tok[i]) } catch (_: Throwable) { }
                setScheme(cs, "WHOLE_BACKGROUND", bg.toArgb())
                setScheme(cs, "LINE_NUMBER_BACKGROUND", bg.toArgb())
                setScheme(cs, "LINE_NUMBER", dim.toArgb())
                setScheme(cs, "LINE_NUMBER_CURRENT", fg.toArgb())
                setScheme(cs, "LINE_DIVIDER", 0)
                setScheme(cs, "CURRENT_LINE", (if (currentLine) lerp(bg, fg, 0.07f) else bg).toArgb())
                setScheme(cs, "SELECTION_INSERT", accent.toArgb())
                setScheme(cs, "SELECTION_HANDLE", accent.toArgb())
                setScheme(cs, "SELECTED_TEXT_BACKGROUND", accent.copy(alpha = 0.35f).toArgb())
                setScheme(cs, "MATCHED_TEXT_BACKGROUND", accent.copy(alpha = 0.30f).toArgb())
                setScheme(cs, "BLOCK_LINE", lerp(bg, fg, 0.17f).toArgb())
                setScheme(cs, "BLOCK_LINE_CURRENT", lerp(bg, fg, 0.34f).toArgb())
                setScheme(cs, "HIGHLIGHTED_DELIMITERS_FOREGROUND", fg.toArgb())
                setScheme(cs, "HIGHLIGHTED_DELIMITERS_BACKGROUND", accent.copy(alpha = 0.40f).toArgb())
                setScheme(cs, "HIGHLIGHTED_DELIMITERS_UNDERLINE", accent.toArgb())
                setScheme(cs, "NON_PRINTABLE_CHAR", dim.toArgb())
                setScheme(cs, "SCROLL_BAR_THUMB", lerp(bg, fg, 0.25f).toArgb())
                setScheme(cs, "SCROLL_BAR_THUMB_PRESSED", dim.toArgb())
                setScheme(cs, "SCROLL_BAR_TRACK", 0)

                val analyzer = LexAnalyzer(
                    lang, ids.map { if (it >= 0) it else 0 }.toIntArray(),
                    schemeId("HIGHLIGHTED_DELIMITERS_FOREGROUND").coerceAtLeast(0),
                    schemeId("HIGHLIGHTED_DELIMITERS_BACKGROUND").coerceAtLeast(0),
                    highlight, guides, brackets, indentW
                )
                ap.analyzer = analyzer
                ed.setEditorLanguage(LexLanguage(analyzer))
            }

            handle.ed = ed
            handle.lang = lang
            handle.refreshUndo()

            val j = handle.pendingJump
            if (j != null && j.path == buffer.path && content != null) {
                handle.pendingJump = null
                handle.select(j.line, j.col, j.len)
            }
        },
        onRelease = { ed ->
            if (handle.ed === ed) handle.ed = null
            try { ed.release() } catch (_: Throwable) { }
        }
    )
}

// ---------------------------------------------------------------------------
// Special-key toolbar (shown above the keyboard by EditorScreen)
// ---------------------------------------------------------------------------

@Composable
private fun Key(
    label: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 42.dp)
            .pressable(enabled = enabled, scale = 0.88f, onTap = onTap)
            .clip(Shapes.small)
            .background(Gx.surfaceHigh)
            .border(1.dp, Gx.border, Shapes.small)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) GIcon(icon, size = 20.dp, tint = if (enabled) Gx.text else Gx.textDim.copy(alpha = 0.4f))
        if (label != null) GText(label, style = Ty.label.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), maxLines = 1)
    }
}

@Composable
fun SpecialKeysBar(handle: EditorHandle, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Gx.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Key(icon = Ico.Undo, enabled = handle.canUndo) { handle.undo() }
        Key(icon = Ico.Redo, enabled = handle.canRedo) { handle.redo() }
        Key("Tab") { handle.tab() }
        Key("{}") { handle.typePair("{", "}") }
        Key("()") { handle.typePair("(", ")") }
        Key("[]") { handle.typePair("[", "]") }
        Key("<>") { handle.typePair("<", ">") }
        for (ch in listOf("=", ";", ":", "'", "\"", "/", "\\", "|", "&", "!", "?", "+", "-", "*", "_", "$", "#", "@", "~")) {
            Key(ch) { handle.typeText(ch) }
        }
    }
}