package app.gitex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class TreeEntry(val path: String, val isDir: Boolean)

/** A fully validated list of things to create. Directories come parent-first. */
class Plan(val base: File, val dirs: List<String>, val files: List<String>) {
    val total: Int get() = dirs.size + files.size
}

sealed class Interpret {
    class Entries(val entries: List<TreeEntry>, val viaAi: Boolean) : Interpret()
    object NeedsKey : Interpret()
    class Failed(val msg: String) : Interpret()
}

sealed class PlanResult {
    class Ok(val plan: Plan) : PlanResult()
    class Problems(val list: List<String>) : PlanResult()
}

class ExecResult(val created: Int, val error: String?)

/** Settings of the AI tree creation (shown in Settings → Gemini). */
object AiPrefs {
    var model by PrefValue<String>("gemini_model", "auto", { s, k, d -> s.getString(k, d) ?: d }, { e, k, v -> e.putString(k, v) })
    var localFirst by PrefValue<Boolean>("tree_local_first", true, { s, k, d -> s.getBoolean(k, d) }, { e, k, v -> e.putBoolean(k, v) })
}

/** Local, instant, offline reader for the usual ASCII-tree / indented / path-list formats. */
object TreeParser {
    private const val PREFIX = "│├└─┬┌┐┘┤┼|`+\\-—–• \t*"
    private val asciiTree = Regex("^\\s*[|`+\\\\]-{1,3}\\s")
    private val cmtHash = Regex("\\s+#.*$")
    private val cmtSlash = Regex("\\s+//.*$")
    private val cmtArrow = Regex("\\s*(←|<-|→|->).*$")
    private val cmtDash = Regex("\\s+[—–]\\s.*$")

    private fun clean(name: String): String =
        name.replace(cmtHash, "").replace(cmtSlash, "").replace(cmtArrow, "").replace(cmtDash, "").trim()

    /** Returns null when the text does not look like a tree this parser understands (the AI fallback is used then). */
    fun parse(text: String): List<TreeEntry>? {
        val lines = text.replace("\t", "    ").lines()
            .filter { it.isNotBlank() && !it.trim().startsWith("```") }
        if (lines.isEmpty()) return null
        val treeChars = lines.any { l -> l.any { it == '│' || it == '├' || it == '└' || it == '─' } || asciiTree.containsMatchIn(l) }
        val indented = lines.any { it.first() == ' ' }
        val out = ArrayList<TreeEntry>()

        if (!treeChars && !indented) {
            for (l in lines) {
                var s = l.trim()
                if (s.startsWith("- ") || s.startsWith("* ")) s = s.substring(2)
                s = clean(s)
                if (s.isEmpty()) continue
                out.add(TreeEntry(s.trimEnd('/'), s.endsWith("/")))
            }
        } else {
            class Node(val pos: Int, val path: String)
            val stack = ArrayList<Node>()
            for (raw in lines) {
                var i = 0
                while (i < raw.length && raw[i] in PREFIX) i++
                if (i >= raw.length) continue
                var name = clean(raw.substring(i))
                if (name.isEmpty()) continue
                val dir = name.endsWith("/")
                name = name.trimEnd('/')
                if (name.isEmpty()) continue
                while (stack.isNotEmpty() && stack.last().pos >= i) stack.removeAt(stack.lastIndex)
                val path = if (stack.isEmpty()) name else stack.last().path + "/" + name
                stack.add(Node(i, path))
                out.add(TreeEntry(path, dir))
            }
        }
        if (out.isEmpty()) return null

        val parents = HashSet<String>()
        for (e in out) {
            var p = e.path
            while (p.contains('/')) { p = p.substringBeforeLast('/'); parents.add(p) }
        }
        val result = out.map { TreeEntry(it.path, it.isDir || it.path in parents) }
        val ok = result.all { e -> e.path.isNotEmpty() && e.path.split('/').all { s -> s == "." || TreePlanner.segmentError(s) == null } }
        return if (ok) result else null
    }
}

/**
 * Optional AI reader for tree formats the local parser does not understand. The user's own API key is
 * used (stored encrypted on the phone, never in the APK) and only the pasted tree text is sent.
 * Busy or unavailable models are retried and, in Automatic mode, replaced by the next model in the list.
 */
object GeminiTree {
    private const val MAX_INPUT = 60_000

    val AUTO = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite", "gemini-2.5-flash")

    val MODELS = listOf(
        "auto" to "Automatic (recommended)",
        "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite",
        "gemini-3.1-flash-lite" to "Gemini 3.1 Flash-Lite",
        "gemini-2.5-flash-lite" to "Gemini 2.5 Flash-Lite",
        "gemini-2.5-flash" to "Gemini 2.5 Flash",
        "gemini-3.5-flash" to "Gemini 3.5 Flash",
    )

    private sealed class Call {
        class Ok(val entries: List<TreeEntry>) : Call()
        class Retry(val msg: String) : Call()   // busy / overloaded: wait and try again
        class Skip(val msg: String) : Call()    // this model can't be used: try the next one
        class Fatal(val msg: String) : Call()   // nothing will help (bad key ...)
    }

    private fun body(text: String): String {
        val prompt = "Convert the following project tree description into a flat list of files and folders.\n" +
            "Rules: use paths relative to the project root with '/' separators; give every folder type \"dir\" and every file type \"file\"; " +
            "include every file and folder that is mentioned; do not invent anything; ignore comments and descriptions; " +
            "do not include absolute paths.\n\nTREE:\n<<<\n${text.take(MAX_INPUT)}\n>>>"
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("entries", JSONObject()
                .put("type", "ARRAY")
                .put("items", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("path", JSONObject().put("type", "STRING"))
                        .put("type", JSONObject().put("type", "STRING").put("enum", JSONArray().put("file").put("dir"))))
                    .put("required", JSONArray().put("path").put("type")))))
            .put("required", JSONArray().put("entries"))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema))
            .toString()
    }

    private suspend fun call(key: String, model: String, body: String): Call {
        val r = try {
            Http.request(
                "POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                mapOf("Content-Type" to "application/json", "x-goog-api-key" to key), body, timeoutMs = 60_000
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Call.Retry(if (e is IOException) Http.describe(e) else "Request failed")
        }
        if (r.code !in 200..299) {
            val msg = try { JSONObject(r.body).getJSONObject("error").optString("message") } catch (_: Exception) { "" }
            return when {
                msg.contains("API key", true) && (r.code == 400 || r.code == 401 || r.code == 403) ->
                    Call.Fatal("Google doesn't accept this API key. Check it in Settings → Gemini.")
                r.code == 429 || r.code == 500 || r.code == 502 || r.code == 503 || r.code == 504 ->
                    Call.Retry(msg.ifBlank { "The model is busy right now." })
                else -> Call.Skip(msg.ifBlank { "HTTP ${r.code}" })
            }
        }
        return try {
            val j = JSONObject(r.body)
            val txt = j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text")
            val arr = JSONObject(txt).getJSONArray("entries")
            Call.Ok((0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                TreeEntry(o.getString("path"), o.optString("type") == "dir")
            })
        } catch (_: Exception) {
            Call.Retry("The answer couldn't be understood.")
        }
    }

    suspend fun interpret(key: String, text: String): Result<List<TreeEntry>> {
        val chosen = AiPrefs.model
        val auto = chosen == "auto" || chosen !in MODELS.map { it.first }
        val order = if (auto) AUTO else listOf(chosen)
        val attempts = if (auto) 2 else 3
        val b = body(text)
        var last = ""
        for (m in order) {
            for (a in 0 until attempts) {
                when (val c = call(key, m, b)) {
                    is Call.Ok -> return Result.success(c.entries)
                    is Call.Fatal -> return Result.failure(Exception(c.msg))
                    is Call.Skip -> { last = c.msg; break }
                    is Call.Retry -> { last = c.msg; if (a < attempts - 1) delay(1500L * (a + 1)) }
                }
            }
        }
        return Result.failure(Exception(
            (if (auto) "Google's models are all busy right now" else "That model is busy right now") +
                " ($last). Try again in a minute, or pick another model in Settings → Gemini."
        ))
    }
}

object TreePlanner {
    private const val MAX_ENTRIES = 2000
    private const val MAX_DEPTH = 25
    private const val MAX_PATH = 1000
    private val badChars = charArrayOf(':', '*', '?', '"', '<', '>', '|', '\\')

    fun segmentError(s: String): String? = when {
        s.isEmpty() -> "empty name"
        s == ".." -> "\"..\" is not allowed"
        s.any { it.code < 32 || it in badChars } -> "\"$s\" has characters Android can't use in names"
        s.toByteArray().size > 255 -> "\"${s.take(30)}…\" is too long"
        s.endsWith(".") || s != s.trim() -> "\"$s\" can't end with a dot or space"
        else -> null
    }

    fun commonRoot(entries: List<TreeEntry>): String? {
        if (entries.isEmpty()) return null
        val first = entries.map { it.path.trimStart('.', '/').substringBefore('/') }.distinct()
        if (first.size != 1) return null
        return if (entries.any { it.path.contains('/') }) first[0] else null
    }

    fun stripRoot(entries: List<TreeEntry>, root: String): List<TreeEntry> =
        entries.filter { it.path.trimStart('.', '/') != root }
            .map { TreeEntry(it.path.trimStart('.', '/').removePrefix("$root/"), it.isDir) }

    /**
     * Validates everything BEFORE anything is created: no absolute paths, no "..", valid names, sane size
     * and depth, no duplicates, and nothing that already exists in [base] (nothing is ever overwritten).
     */
    fun plan(entries: List<TreeEntry>, base: File): PlanResult {
        val problems = ArrayList<String>()
        if (entries.isEmpty()) return PlanResult.Problems(listOf("No files or folders were found in the text."))
        if (entries.size > MAX_ENTRIES) return PlanResult.Problems(listOf("That's more than $MAX_ENTRIES items; please split it up."))

        val planned = LinkedHashMap<String, Pair<String, Boolean>>()
        fun add(path: String, isDir: Boolean) {
            val key = path.lowercase()
            val ex = planned[key]
            if (ex == null) planned[key] = path to isDir
            else if (ex.second != isDir) problems.add("\"$path\" is listed as both a file and a folder")
            else if (ex.first != path) problems.add("\"$path\" and \"${ex.first}\" differ only by capital letters")
        }

        for (e in entries) {
            val raw = e.path.trim()
            if (raw.startsWith("/") || raw.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(raw)) {
                problems.add("Absolute paths aren't allowed: $raw"); continue
            }
            val segs = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (segs.isEmpty()) { problems.add("Empty name found"); continue }
            if (segs.any { it == ".." }) { problems.add("Paths can't go outside the folder: $raw"); continue }
            val err = segs.firstNotNullOfOrNull { segmentError(it) }
            if (err != null) { problems.add(err); continue }
            if (segs.size > MAX_DEPTH) { problems.add("Too deeply nested: ${raw.take(60)}"); continue }
            if (segs.sumOf { it.length + 1 } > MAX_PATH) { problems.add("Path too long: ${raw.take(60)}"); continue }
            for (i in 1 until segs.size) add(segs.take(i).joinToString("/"), true)
            add(segs.joinToString("/"), e.isDir)
        }

        for ((path, isDir) in planned.values) {
            val parent = path.substringBeforeLast('/', "").let { if (it.isEmpty()) base else File(base, it) }
            val name = path.substringAfterLast('/')
            val clash = parent.list()?.firstOrNull { it.equals(name, ignoreCase = true) }
            if (clash != null) {
                problems.add(if (isDir) "The folder \"$path\" already exists here" else "The file \"$path\" already exists here")
            }
        }

        if (problems.isNotEmpty()) {
            val shown = problems.distinct().take(12)
            val extra = problems.distinct().size - shown.size
            return PlanResult.Problems(if (extra > 0) shown + "…and $extra more" else shown)
        }
        val all = planned.values.toList()
        val dirs = all.filter { it.second }.map { it.first }.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))
        val files = all.filter { !it.second }.map { it.first }.sorted()
        return PlanResult.Ok(Plan(base, dirs, files))
    }
}

object TreeCreator {
    /**
     * By default the local parser goes first (instant, offline, nothing leaves the phone); the AI is only asked
     * when the text isn't a recognisable tree. With "built-in reader first" off, the AI is asked first and the
     * local parser is the fallback if the AI fails.
     */
    suspend fun interpret(text: String): Interpret {
        if (text.isBlank()) return Interpret.Failed("Paste a repository tree first.")
        val local = TreeParser.parse(text)
        if (AiPrefs.localFirst && local != null) return Interpret.Entries(local, viaAi = false)
        val key = Prefs.geminiKey()
        if (key == null) return if (local != null) Interpret.Entries(local, viaAi = false) else Interpret.NeedsKey
        val r = aiInterpret(key, text)
        if (r is Interpret.Failed && local != null) return Interpret.Entries(local, viaAi = false)
        return r
    }

    suspend fun aiInterpret(key: String, text: String): Interpret {
        val r = GeminiTree.interpret(key, text)
        return r.fold(
            onSuccess = { if (it.isEmpty()) Interpret.Failed("The AI found no files or folders in that text.") else Interpret.Entries(it, true) },
            onFailure = { Interpret.Failed(it.message ?: "The AI request failed.") }
        )
    }

    /** Creates the plan. If anything fails midway, everything created so far is removed again. */
    suspend fun execute(plan: Plan, onProgress: (Int, Int) -> Unit): ExecResult = withContext(Dispatchers.IO) {
        val made = ArrayList<File>()
        var n = 0
        try {
            for (d in plan.dirs) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val f = File(plan.base, d)
                if (!f.mkdir()) throw IOException("Couldn't create the folder \"$d\"")
                made.add(f); onProgress(++n, plan.total)
            }
            for (p in plan.files) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val f = File(plan.base, p)
                if (!f.createNewFile()) throw IOException("\"$p\" already exists")
                made.add(f); onProgress(++n, plan.total)
            }
            ExecResult(plan.total, null)
        } catch (e: Throwable) {
            for (f in made.asReversed()) f.delete()
            if (e is CancellationException) throw e
            ExecResult(0, "${Fs.friendly(e)}. Everything created so far was removed again.")
        }
    }
}