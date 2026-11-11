package app.gitex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class FileKind { TEXT, IMAGE, BINARY }

class BufferState(var path: String, val kind: FileKind) {
    /** The live document. Kept per file so undo history survives switching files. */
    var content: Content? = null
    var diskModified = 0L
    var diskLength = 0L
    var cursorLine = 0
    var cursorCol = 0
    var scrollX = 0
    var scrollY = 0
    var big = false
    var externalChange by mutableStateOf(false)
}

sealed class OpenResult {
    object Ok : OpenResult()
    class External(val file: File) : OpenResult()
    class Failed(val msg: String) : OpenResult()
}

sealed class SaveResult {
    object Saved : SaveResult()
    object NoChange : SaveResult()
    class Conflict(val path: String) : SaveResult()
    class Failed(val msg: String) : SaveResult()
}

class SaveAllResult(val saved: Int, val conflicts: List<String>, val failed: List<String>)

/** Crash-recovery copies of unsaved buffers in filesDir/drafts. */
private object Drafts {
    class Draft(val path: String, val text: String, val diskModified: Long, val diskLength: Long)

    private fun dir(): File = File(App.instance.filesDir, "drafts").also { it.mkdirs() }

    private fun fileFor(path: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        return File(dir(), h.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun write(path: String, text: String, mod: Long, len: Long) {
        val j = JSONObject().put("path", path).put("text", text).put("mod", mod).put("len", len)
        Fs.atomicWrite(fileFor(path), j.toString())
    }

    fun delete(path: String) {
        fileFor(path).delete()
    }

    fun loadAll(): List<Draft> {
        val out = ArrayList<Draft>()
        for (f in dir().listFiles() ?: return out) {
            if (!f.name.endsWith(".json")) continue
            try {
                val j = JSONObject(f.readText())
                out.add(Draft(j.getString("path"), j.getString("text"), j.optLong("mod"), j.optLong("len")))
            } catch (_: Exception) {
                // Unreadable draft: leave it on disk rather than risk deleting someone's work.
            }
        }
        return out
    }
}

/**
 * Single source of truth for the project, open buffers, dirty state and recent files.
 * The recent-files list ([Prefs.recents]) doubles as the list of open files.
 */
class Workspace : ViewModel() {
    var projectRoot by mutableStateOf<File?>(null)
        private set
    var current by mutableStateOf<String?>(null)
        private set

    /** Bumped after any file/folder change so explorer and caches refresh. */
    var treeVersion by mutableIntStateOf(0)
        private set

    /** Bumped when a buffer's Content object is swapped (reload from disk). */
    var contentVersion by mutableIntStateOf(0)
        private set

    val dirty = mutableStateMapOf<String, Boolean>()

    private val buffers = HashMap<String, BufferState>()
    private val draftJobs = HashMap<String, Job>()
    private val autosaveJobs = HashMap<String, Job>()
    private var restored = false
    private var fileCache: List<File>? = null
    private var fileCacheKey = ""

    fun buffer(path: String?): BufferState? = if (path == null) null else buffers[path]

    fun hasDirty(): Boolean = dirty.isNotEmpty()

    fun dirtyPaths(): List<String> = dirty.keys.toList()

    fun dirtyUnder(root: File): List<String> {
        val prefix = root.absolutePath + File.separator
        return dirty.keys.filter { it.startsWith(prefix) }
    }

    // ---- session ----

    suspend fun restore() {
        if (restored) return
        restored = true
        val rootPath = Prefs.projectRoot
        if (rootPath.isNotEmpty()) {
            val d = File(rootPath)
            if (d.isDirectory) projectRoot = d
        }
        val drafts = withContext(Dispatchers.IO) { Drafts.loadAll() }
        for (d in drafts) {
            val content = withContext(Dispatchers.IO) { Content(d.text) }
            val b = BufferState(d.path, FileKind.TEXT)
            b.content = content
            b.diskModified = d.diskModified
            b.diskLength = d.diskLength
            b.big = d.text.length > Fs.BIG_FILE_BYTES
            val f = File(d.path)
            if (f.exists() && (f.lastModified() != d.diskModified || f.length() != d.diskLength)) {
                b.externalChange = true
            }
            buffers[d.path] = b
            dirty[d.path] = true
            if (!Prefs.recents.contains(d.path)) Prefs.addRecent(d.path)
        }
        val last = Prefs.lastOpenFile
        if (last.isNotEmpty() && File(last).exists()) open(last)
        else Prefs.recents.firstOrNull { File(it).exists() }?.let { open(it) }
    }

    // ---- project ----

    fun setProject(dir: File) {
        projectRoot = dir
        Prefs.projectRoot = dir.absolutePath
        bump()
    }

    fun bump() {
        treeVersion++
        fileCache = null
    }

    suspend fun projectFiles(): List<File> {
        val root = projectRoot ?: return emptyList()
        val key = "${root.absolutePath}#$treeVersion"
        fileCache?.let { if (key == fileCacheKey) return it }
        val list = withContext(Dispatchers.IO) { Fs.listAllFiles(root) }
        fileCache = list
        fileCacheKey = key
        return list
    }

    fun relativeDir(f: File): String {
        val root = projectRoot ?: return f.parent ?: ""
        val p = f.parentFile?.absolutePath ?: return ""
        val r = root.absolutePath
        return when {
            p == r -> root.name
            p.startsWith(r + File.separator) -> root.name + p.substring(r.length)
            else -> p
        }
    }

    // ---- opening / closing ----

    private fun activate(path: String) {
        current = path
        Prefs.addRecent(path)
        Prefs.lastOpenFile = path
    }

    suspend fun open(path: String): OpenResult {
        val existing = buffers[path]
        if (existing != null) {
            activate(path)
            return OpenResult.Ok
        }
        val f = File(path)
        if (!f.exists()) {
            Prefs.removeRecent(path)
            return OpenResult.Failed("That file no longer exists")
        }
        return when (FileKinds.kindOf(f.name)) {
            FileKind.BINARY -> OpenResult.External(f)
            FileKind.IMAGE -> {
                buffers[path] = BufferState(path, FileKind.IMAGE)
                activate(path)
                OpenResult.Ok
            }
            FileKind.TEXT -> {
                val mod = f.lastModified()
                val len = f.length()
                when (val r = withContext(Dispatchers.IO) { Fs.readText(f) }) {
                    is Fs.ReadResult.Ok -> {
                        val b = BufferState(path, FileKind.TEXT)
                        b.content = withContext(Dispatchers.IO) { Content(r.text) }
                        b.diskModified = mod
                        b.diskLength = len
                        b.big = len > Fs.BIG_FILE_BYTES
                        buffers[path] = b
                        activate(path)
                        OpenResult.Ok
                    }
                    Fs.ReadResult.Binary -> OpenResult.External(f)
                    Fs.ReadResult.TooLarge ->
                        OpenResult.Failed("This file is over 12 MB, too large to edit safely here")
                    Fs.ReadResult.NotUtf8 ->
                        OpenResult.Failed("This file isn't UTF-8 text, so Gitex won't open it for editing (saving could corrupt it)")
                    is Fs.ReadResult.Err -> OpenResult.Failed(r.msg)
                }
            }
        }
    }

    /** Removes the buffer (its unsaved edits are dropped, so the UI must have asked first). */
    suspend fun close(path: String) {
        dropBuffer(path)
        Prefs.removeRecent(path)
        if (current == path) {
            current = null
            Prefs.lastOpenFile = ""
            val next = Prefs.recents.firstOrNull()
            if (next != null) open(next)
        }
    }

    private suspend fun dropBuffer(path: String) {
        buffers.remove(path)
        dirty.remove(path)
        draftJobs.remove(path)?.cancel()
        autosaveJobs.remove(path)?.cancel()
        withContext(Dispatchers.IO) { Drafts.delete(path) }
    }

    /** Closes every buffer inside [root] (after the UI saved or discarded them). */
    suspend fun closeUnder(root: File) {
        val prefix = root.absolutePath + File.separator
        for (p in buffers.keys.filter { it.startsWith(prefix) }) close(p)
    }

    // ---- editing / saving ----

    fun onEdited(path: String) {
        val b = buffers[path] ?: return
        if (dirty[path] != true) dirty[path] = true
        draftJobs[path]?.cancel()
        draftJobs[path] = viewModelScope.launch {
            delay(700)
            val text = b.content?.toString() ?: return@launch
            val mod = b.diskModified
            val len = b.diskLength
            withContext(Dispatchers.IO) { Drafts.write(b.path, text, mod, len) }
        }
        if (Prefs.autosave) {
            autosaveJobs[path]?.cancel()
            autosaveJobs[path] = viewModelScope.launch {
                delay(1500)
                save(b.path)
            }
        }
    }

    suspend fun save(path: String, force: Boolean = false): SaveResult {
        val b = buffers[path] ?: return SaveResult.NoChange
        if (b.kind != FileKind.TEXT) return SaveResult.NoChange
        if (dirty[path] != true && !force) return SaveResult.NoChange
        val f = File(path)
        if (!force && f.exists() && (f.lastModified() != b.diskModified || f.length() != b.diskLength)) {
            b.externalChange = true
            return SaveResult.Conflict(path)
        }
        val text = b.content?.toString() ?: return SaveResult.NoChange
        return try {
            withContext(Dispatchers.IO) { Fs.atomicWrite(f, text) }
            b.diskModified = f.lastModified()
            b.diskLength = f.length()
            b.externalChange = false
            draftJobs.remove(path)?.cancel()
            autosaveJobs.remove(path)?.cancel()
            // Edits made while writing keep the buffer dirty: compare against what we wrote.
            if (b.content?.toString() == text) {
                dirty.remove(path)
                withContext(Dispatchers.IO) { Drafts.delete(path) }
            } else onEdited(path)
            bump()
            SaveResult.Saved
        } catch (e: Exception) {
            SaveResult.Failed(Fs.friendly(e))
        }
    }

    suspend fun saveAll(): SaveAllResult {
        var saved = 0
        val conflicts = ArrayList<String>()
        val failed = ArrayList<String>()
        for (p in dirty.keys.toList()) {
            when (save(p)) {
                SaveResult.Saved -> saved++
                is SaveResult.Conflict -> conflicts.add(p)
                is SaveResult.Failed -> failed.add(p)
                SaveResult.NoChange -> {}
            }
        }
        return SaveAllResult(saved, conflicts, failed)
    }

    /** Throws away unsaved edits and loads the file from disk again. */
    suspend fun reloadFromDisk(path: String): Boolean {
        val f = File(path)
        val r = withContext(Dispatchers.IO) { Fs.readText(f) }
        if (r !is Fs.ReadResult.Ok) return false
        val b = buffers[path] ?: return false
        b.content = withContext(Dispatchers.IO) { Content(r.text) }
        b.diskModified = f.lastModified()
        b.diskLength = f.length()
        b.externalChange = false
        draftJobs.remove(path)?.cancel()
        autosaveJobs.remove(path)?.cancel()
        dirty.remove(path)
        withContext(Dispatchers.IO) { Drafts.delete(path) }
        if (current == path) contentVersion++
        return true
    }

    /** On resume: reload clean buffers that changed on disk, flag dirty ones as conflicted. */
    suspend fun refreshFromDisk() {
        for (b in buffers.values.toList()) {
            if (b.kind != FileKind.TEXT) continue
            val f = File(b.path)
            if (!f.exists()) continue
            if (f.lastModified() == b.diskModified && f.length() == b.diskLength) continue
            if (dirty[b.path] == true) b.externalChange = true
            else reloadFromDisk(b.path)
        }
    }

    // ---- file operations done from the explorer ----

    suspend fun renamed(old: File, new: File) {
        val oldP = old.absolutePath
        val newP = new.absolutePath
        for (p in buffers.keys.toList()) {
            val np = when {
                p == oldP -> newP
                p.startsWith(oldP + File.separator) -> newP + p.substring(oldP.length)
                else -> continue
            }
            val b = buffers.remove(p) ?: continue
            b.path = np
            buffers[np] = b
            val wasDirty = dirty.remove(p) == true
            draftJobs.remove(p)?.cancel()
            autosaveJobs.remove(p)?.cancel()
            withContext(Dispatchers.IO) { Drafts.delete(p) }
            Prefs.replaceRecent(p, np)
            if (current == p) {
                current = np
                Prefs.lastOpenFile = np
            }
            if (wasDirty) {
                dirty[np] = true
                onEdited(np)
            }
        }
        bump()
    }

    /**
     * After [f] was moved to the Recycle Bin. Clean buffers close; buffers with unsaved
     * edits stay open (saving recreates the file) so nothing is lost silently.
     */
    suspend fun deleted(f: File) {
        val p = f.absolutePath
        for (path in buffers.keys.toList()) {
            if (path == p || path.startsWith(p + File.separator)) {
                if (dirty[path] == true) continue
                close(path)
            }
        }
        bump()
    }
}