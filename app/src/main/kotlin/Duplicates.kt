package app.gitex

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Warns (never acts) when different files in the project have identical content.
 * Only files whose size matches another file's size are read and hashed, hashes are cached by
 * (modified time, size), and the same set of duplicates is only announced once.
 */
object Duplicates {
    private class Cached(val mod: Long, val len: Long, val hash: String)

    private val cache = HashMap<String, Cached>()
    private var cacheRoot = ""

    private const val MIN_BYTES = 48L
    private const val MAX_BYTES = 1_500_000L
    private const val NOTIF_ID = 5001

    private val skipDirs = setOf(
        "build", "dist", "out", "node_modules", ".gradle", ".git", ".idea", "target",
        "vendor", "__pycache__", ".dart_tool", "pods", ".github"
    )
    private val skipNames = setOf(
        ".gitkeep", ".gitignore", ".gitattributes", "license", "license.md", "license.txt",
        "__init__.py", "gradlew", "gradlew.bat", "gradle-wrapper.properties"
    )

    /** Call after the project or its files changed (the caller debounces). Safe to cancel at any time. */
    suspend fun scan(ws: Workspace) {
        if (!Prefs.duplicateWarnings) return
        val root = ws.projectRoot ?: return
        if (cacheRoot != root.absolutePath) {
            cache.clear()
            cacheRoot = root.absolutePath
        }
        val files = ws.projectFiles()
        val groups = withContext(Dispatchers.IO) { find(root, files) }
        announce(root, groups)
    }

    private suspend fun find(root: File, files: List<File>): List<List<String>> {
        val rootPath = root.absolutePath + File.separator
        val bySize = HashMap<Long, MutableList<File>>()
        for (f in files) {
            val rel = f.absolutePath.removePrefix(rootPath)
            if (rel.split(File.separatorChar).dropLast(1).any { it.lowercase() in skipDirs }) continue
            if (f.name.lowercase() in skipNames) continue
            if (FileKinds.kindOf(f.name) != FileKind.TEXT) continue
            val len = f.length()
            if (len < MIN_BYTES || len > MAX_BYTES) continue
            bySize.getOrPut(len) { ArrayList() }.add(f)
        }
        val byHash = HashMap<String, MutableList<String>>()
        for ((len, list) in bySize) {
            if (list.size < 2) continue
            for (f in list) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val path = f.absolutePath
                val mod = f.lastModified()
                var c = cache[path]
                if (c == null || c.mod != mod || c.len != len) {
                    val bytes = try { f.readBytes() } catch (_: Exception) { continue }
                    val blank = String(bytes, Charsets.UTF_8).isBlank()
                    val h = if (blank) "" else MessageDigest.getInstance("SHA-1").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    c = Cached(mod, len, h)
                    cache[path] = c
                }
                if (c.hash.isNotEmpty()) byHash.getOrPut(c.hash + ":" + len) { ArrayList() }.add(path)
            }
        }
        return byHash.values.filter { it.size >= 2 }.map { g -> g.sorted() }.sortedBy { it.first() }
    }

    private fun announce(root: File, groups: List<List<String>>) {
        val ctx = App.instance
        val sp = ctx.getSharedPreferences("gitex_dups", Context.MODE_PRIVATE)
        val key = root.absolutePath
        if (groups.isEmpty()) {
            sp.edit().putString(key, "").apply()
            return
        }
        val sig = MessageDigest.getInstance("SHA-1").digest(groups.joinToString("|") { it.joinToString(",") }.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (sp.getString(key, null) == sig) return
        sp.edit().putString(key, sig).apply()

        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val total = groups.sumOf { it.size }
        val detail = groups.take(4).joinToString("\n") { g -> g.take(4).joinToString("  =  ") { File(it).name } } +
            if (groups.size > 4) "\n…and ${groups.size - 4} more" else ""
        val open = PendingIntent.getActivity(
            ctx, NOTIF_ID, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, Notif.CH_DUPLICATES)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle("Duplicate Content Detected")
            .setContentText("Identical content found in $total different files.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Identical content found in $total different files.\n$detail"))
            .setAutoCancel(true).setContentIntent(open)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) { }
    }
}