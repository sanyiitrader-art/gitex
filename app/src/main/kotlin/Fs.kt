package app.gitex

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Real-filesystem helpers. Everything here is blocking: call it from Dispatchers.IO. */
object Fs {
    const val MAX_EDIT_BYTES = 12L * 1024 * 1024

    /** Above this size highlighting and indentation guides are switched off for responsiveness. */
    const val BIG_FILE_BYTES = 1_500_000L

    private val badChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun isHiddenName(name: String): Boolean = name == ".git" || name.endsWith(".gitex-tmp")

    /** Folders first, then files; both alphabetical (case-insensitive). */
    fun listDir(dir: File): List<File> {
        val arr = dir.listFiles() ?: return emptyList()
        return arr.filter { !isHiddenName(it.name) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Returns an error message, or null when [raw] is a valid new name inside [parent].
     * Android's shared storage is case-insensitive and a file and a folder can never share
     * a name in one directory, so any sibling with the same name (any case) is a clash.
     */
    fun nameError(raw: String, parent: File, ignoreName: String? = null): String? {
        if (raw.isBlank()) return "Name can't be empty"
        if (raw != raw.trim()) return "Name can't start or end with a space"
        if (raw == "." || raw == "..") return "Invalid name"
        if (raw.any { it.code < 32 || it in badChars }) return "Names can't contain / \\ : * ? \" < > | or control characters"
        if (raw.toByteArray().size > 255) return "Name is too long"
        if (raw.endsWith(".")) return "Name can't end with a dot"
        val clash = parent.list()?.any { it.equals(raw, ignoreCase = true) && it != ignoreName } == true
        if (clash) return "\"$raw\" already exists in this folder"
        return null
    }

    fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("ENOSPC", true) || m.contains("No space", true) -> "The phone's storage is full"
            m.contains("EACCES", true) || m.contains("Permission denied", true) -> "Permission denied"
            m.contains("EROFS", true) || m.contains("Read-only", true) -> "That location is read-only"
            m.isNotBlank() -> m
            else -> e.javaClass.simpleName
        }
    }

    fun createFile(parent: File, name: String): Result<File> = runCatching {
        val f = File(parent, name)
        if (!f.createNewFile()) throw IOException("\"$name\" already exists")
        f
    }

    fun createDir(parent: File, name: String): Result<File> = runCatching {
        val f = File(parent, name)
        if (f.exists()) throw IOException("\"$name\" already exists")
        if (!f.mkdir()) throw IOException("Couldn't create the folder")
        f
    }

    fun rename(f: File, newName: String): Result<File> = runCatching {
        val parent = f.parentFile ?: throw IOException("No parent folder")
        val target = File(parent, newName)
        if (f.name.equals(newName, ignoreCase = true) && f.name != newName) {
            // Case-only rename on a case-insensitive volume goes through a temporary name.
            val tmp = File(parent, ".gitex-rn-${System.nanoTime()}.gitex-tmp")
            if (!f.renameTo(tmp)) throw IOException("Android refused the rename")
            if (!tmp.renameTo(target)) {
                tmp.renameTo(f)
                throw IOException("Android refused the rename")
            }
        } else if (!f.renameTo(target)) {
            throw IOException("Android refused the rename")
        }
        target
    }

    /** Write-to-temp, fsync, then atomic rename over the target: a crash never leaves a half-written file. */
    fun atomicWrite(target: File, text: String) {
        val dir = target.parentFile ?: throw IOException("No parent folder")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create the folder")
        val tmp = File(dir, ".${target.name}.gitex-tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    sealed class ReadResult {
        class Ok(val text: String) : ReadResult()
        object Binary : ReadResult()
        object TooLarge : ReadResult()
        object NotUtf8 : ReadResult()
        class Err(val msg: String) : ReadResult()
    }

    /** Strict UTF-8 read: anything else is refused instead of risking corruption on save. */
    fun readText(f: File): ReadResult {
        if (!f.isFile) return ReadResult.Err("File not found")
        if (f.length() > MAX_EDIT_BYTES) return ReadResult.TooLarge
        return try {
            val bytes = f.readBytes()
            val probe = minOf(bytes.size, 8000)
            for (k in 0 until probe) if (bytes[k] == 0.toByte()) return ReadResult.Binary
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            ReadResult.Ok(dec.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: CharacterCodingException) {
            ReadResult.NotUtf8
        } catch (e: Exception) {
            ReadResult.Err(friendly(e))
        }
    }

    /** Every eligible file under [root] (iterative, symlink-safe, capped). */
    fun listAllFiles(root: File, limit: Int = 60_000, cancelled: () -> Boolean = { false }): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && out.size < limit) {
            if (cancelled()) break
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                val n = k.name
                if (n == ".git" || n == "node_modules" || n == ".gradle" || n.endsWith(".gitex-tmp")) continue
                if (k.isDirectory) {
                    if (!Files.isSymbolicLink(k.toPath())) stack.add(k)
                } else out.add(k)
            }
        }
        return out
    }

    fun treeSize(f: File): Long =
        if (f.isDirectory) (f.listFiles()?.sumOf { treeSize(it) } ?: 0L) else f.length()

    // ---- picker results -> real paths ----

    private fun fromDocId(id: String): File? {
        if (id.startsWith("raw:")) return File(id.removePrefix("raw:"))
        val i = id.indexOf(':')
        if (i < 0) return null
        val vol = id.substring(0, i)
        val rel = id.substring(i + 1)
        val base = when (vol) {
            "primary" -> Environment.getExternalStorageDirectory()
            "home" -> File(Environment.getExternalStorageDirectory(), "Documents")
            else -> File("/storage/$vol")
        }
        return if (rel.isEmpty()) base else File(base, rel)
    }

    fun pathFromTree(uri: Uri): File? = try {
        fromDocId(DocumentsContract.getTreeDocumentId(uri))?.takeIf { it.isDirectory && it.canRead() }
    } catch (_: Exception) {
        null
    }

    fun pathFromDoc(ctx: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }?.takeIf { it.isFile }
        try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                fromDocId(id)?.takeIf { it.isFile }?.let { return it }
                if (uri.authority == "com.android.providers.media.documents") {
                    val num = id.substringAfter(':').toLongOrNull()
                    if (num != null) {
                        val q = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), num)
                        queryData(ctx, q)?.let { return it }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return queryData(ctx, uri)
    }

    private fun queryData(ctx: Context, uri: Uri): File? = try {
        ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { File(it) }?.takeIf { it.isFile } else null
        }
    } catch (_: Exception) {
        null
    }
}

// ---------------------------------------------------------------------------
// Recycle Bin
// ---------------------------------------------------------------------------

class TrashItem(
    val id: String,
    val name: String,
    val originalPath: String,
    val isDir: Boolean,
    val deletedAt: Long,
    val dataFile: File,
)

/**
 * Deleted items live in <storage>/.GitexTrash/<id>/ (data under its own name + meta.json).
 * It sits on the same volume as normal projects so moving in and out is an instant rename.
 */
object Trash {
    private fun root(): File {
        val ext = File(Environment.getExternalStorageDirectory(), ".GitexTrash")
        if ((ext.isDirectory || ext.mkdirs()) && ext.canWrite()) return ext
        return File(App.instance.filesDir, "trash").also { it.mkdirs() }
    }

    fun list(): List<TrashItem> {
        val dirs = root().listFiles() ?: return emptyList()
        val out = ArrayList<TrashItem>()
        for (d in dirs) {
            try {
                val meta = JSONObject(File(d, "meta.json").readText())
                val name = meta.getString("name")
                val data = File(d, name)
                if (!data.exists()) continue
                out.add(
                    TrashItem(
                        d.name, name, meta.getString("path"), meta.optBoolean("dir"),
                        meta.optLong("at"), data
                    )
                )
            } catch (_: Exception) {
            }
        }
        return out.sortedByDescending { it.deletedAt }
    }

    fun moveToTrash(f: File): Result<TrashItem> = runCatching {
        if (!f.exists()) throw IOException("That item no longer exists")
        val id = "${System.currentTimeMillis()}-${(1000..9999).random()}"
        val dir = File(root(), id)
        if (!dir.mkdirs()) throw IOException("Couldn't create the Recycle Bin entry")
        val data = File(dir, f.name)
        val isDir = f.isDirectory
        try {
            if (!f.renameTo(data)) {
                // Different volume: copy, verify the size, and only then remove the original.
                if (!f.copyRecursively(data) || Fs.treeSize(f) != Fs.treeSize(data)) {
                    throw IOException("Couldn't move it to the Recycle Bin")
                }
                if (!f.deleteRecursively()) throw IOException("Couldn't remove the original after copying")
            }
            val meta = JSONObject().put("name", f.name).put("path", f.absolutePath)
                .put("dir", isDir).put("at", System.currentTimeMillis())
            File(dir, "meta.json").writeText(meta.toString())
        } catch (e: Exception) {
            if (!data.exists() || f.exists()) dir.deleteRecursively()
            throw e
        }
        TrashItem(id, f.name, f.absolutePath, isDir, System.currentTimeMillis(), data)
    }

    sealed class Restore {
        class Ok(val file: File) : Restore()
        class Conflict(val existing: File) : Restore()
        class Err(val msg: String) : Restore()
    }

    /** Restores to the original location (re-creating missing parent folders). Never overwrites. */
    fun restore(item: TrashItem, renameOnConflict: Boolean = false): Restore {
        val orig = File(item.originalPath)
        val parent = orig.parentFile ?: return Restore.Err("Original location is invalid")
        if (!parent.isDirectory && !parent.mkdirs()) return Restore.Err("Couldn't re-create ${parent.path}")
        var target = orig
        val clash = parent.listFiles()?.firstOrNull { it.name.equals(orig.name, ignoreCase = true) }
        if (clash != null) {
            if (!renameOnConflict) return Restore.Conflict(clash)
            target = uniqueName(parent, orig.name)
        }
        return try {
            if (!item.dataFile.renameTo(target)) {
                if (!item.dataFile.copyRecursively(target) ||
                    Fs.treeSize(item.dataFile) != Fs.treeSize(target)
                ) {
                    target.deleteRecursively()
                    return Restore.Err("Couldn't restore it")
                }
            }
            item.dataFile.parentFile?.deleteRecursively()
            Restore.Ok(target)
        } catch (e: Exception) {
            Restore.Err(Fs.friendly(e))
        }
    }

    fun deleteForever(item: TrashItem) {
        item.dataFile.parentFile?.deleteRecursively()
    }

    private fun uniqueName(parent: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 0
        while (true) {
            val suffix = if (n == 0) " (restored)" else " (restored ${n + 1})"
            val cand = File(parent, "$base$suffix$ext")
            if (parent.listFiles()?.none { it.name.equals(cand.name, true) } != false) return cand
            n++
        }
    }
}