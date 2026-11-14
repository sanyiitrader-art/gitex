package app.gitex

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

sealed class GitResult {
    class Committed(val sha: String, val files: Int, val skipped: List<String> = emptyList()) : GitResult()
    object NothingToCommit : GitResult()
    class Pushed(val sha: String, val upToDate: Boolean, val skipped: List<String> = emptyList()) : GitResult()
    class Rejected(val detail: String, val remoteAhead: Boolean) : GitResult()
    class AuthFailed(val detail: String) : GitResult()
    class NotFound(val detail: String) : GitResult()
    class Offline(val detail: String) : GitResult()
    class Failed(val detail: String) : GitResult()
}

sealed class MergeOutcome {
    object Merged : MergeOutcome()
    object UpToDate : MergeOutcome()
    object NoRemoteBranch : MergeOutcome()
    class Conflicts(val files: List<String>) : MergeOutcome()
    class Failed(val detail: String) : MergeOutcome()
}

class Measure(val files: Int, val bytes: Long)

/** Connectivity helpers: Gitex waits for the network instead of failing. */
object Net {
    private fun cm(ctx: Context) = ctx.getSystemService(ConnectivityManager::class.java)

    fun online(ctx: Context): Boolean = try {
        val m = cm(ctx)
        val n = m.activeNetwork
        val c = if (n == null) null else m.getNetworkCapabilities(n)
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        true
    }

    /** Returns at once if online, otherwise suspends until a network is available. */
    suspend fun await(ctx: Context) {
        if (online(ctx)) return
        suspendCancellableCoroutine<Unit> { cont ->
            val m = cm(ctx)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive) cont.resume(Unit)
                    try { m.unregisterNetworkCallback(this) } catch (_: Exception) { }
                }
            }
            try {
                m.registerDefaultNetworkCallback(cb)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { try { m.unregisterNetworkCallback(cb) } catch (_: Exception) { } }
        }
    }
}

/**
 * Progress listener for JGit: reports "Uploading 42%" style status and lets JGit stop quickly when the
 * coroutine is cancelled. Built on JGit's public ProgressMonitor interface through a dynamic proxy, so it
 * does not depend on which methods a given JGit version declares.
 */
private fun monitor(job: Job?, onStatus: (String) -> Unit, verb: String): ProgressMonitor {
    var total = 0
    var done = 0
    var lastPct = -1
    return Proxy.newProxyInstance(
        ProgressMonitor::class.java.classLoader, arrayOf(ProgressMonitor::class.java)
    ) { _, method, args ->
        when (method.name) {
            "beginTask" -> {
                total = (args?.getOrNull(1) as? Int) ?: 0
                done = 0
                lastPct = -1
                onStatus(if (total > 0) "$verb 0%" else "$verb…")
                null
            }
            "update" -> {
                done += (args?.getOrNull(0) as? Int) ?: 0
                if (total > 0) {
                    val p = (done.toLong() * 100 / total).toInt().coerceIn(0, 100)
                    if (p != lastPct) { lastPct = p; onStatus("$verb $p%") }
                }
                null
            }
            "isCancelled" -> job?.isActive == false
            else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
        }
    } as ProgressMonitor
}

/**
 * Real Git through JGit. Commit and Push are separate: a commit is local only and never triggers
 * GitHub Actions; a push may. A rejected push is never forced automatically.
 */
object GitOps {
    private var inited = false
    private val lock = Mutex()
    private const val MAX_FILE = 95 * 1024 * 1024

    /** True while an earlier Git operation is still finishing. */
    val busy: Boolean get() = lock.isLocked

    private fun init() {
        if (inited) return
        inited = true
        System.setProperty("user.home", App.instance.filesDir.absolutePath)
        try {
            val c = WindowCacheConfig()
            c.setPackedGitMMAP(false)
            c.setPackedGitLimit(32L * 1024 * 1024)
            c.setPackedGitWindowSize(2 * 1024 * 1024)
            c.setDeltaBaseCacheLimit(16 * 1024 * 1024)
            c.install()
        } catch (_: Throwable) {
        }
    }

    fun remoteUrl(link: RepoLink) = "https://github.com/${link.owner}/${link.repo}.git"

    fun hasWorkflows(root: File): Boolean =
        File(root, ".github/workflows").listFiles()?.any { it.name.endsWith(".yml") || it.name.endsWith(".yaml") } == true

    fun hasCommits(root: File): Boolean =
        File(root, ".git/packed-refs").exists() || (File(root, ".git/refs/heads").walk().any { it.isFile })

    /** Counts files and bytes (skipping .git and typical build folders) to warn before a huge first push. */
    suspend fun measure(root: File): Measure = withContext(Dispatchers.IO) {
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && files < 60_000) {
            val d = stack.removeLast()
            for (k in d.listFiles() ?: continue) {
                if (k.isDirectory) {
                    if (k.name != ".git" && k.name != "node_modules" && k.name != ".gradle" && k.name != "build") stack.add(k)
                } else { files++; bytes += k.length() }
            }
        }
        Measure(files, bytes)
    }

    private fun identity(): Pair<String, String> {
        val login = Validate.accountName(Prefs.githubUrl).ifBlank { "gitex" }
        return Prefs.gitName.ifBlank { login } to Prefs.gitEmail.ifBlank { "$login@users.noreply.github.com" }
    }

    private fun creds(link: RepoLink): UsernamePasswordCredentialsProvider {
        val t = Prefs.githubToken() ?: throw IllegalStateException("Not connected to GitHub")
        return UsernamePasswordCredentialsProvider(link.owner.ifBlank { "git" }, t)
    }

    /** Lock files left behind when the app was closed in the middle of an operation. Safe: operations are serialised. */
    private fun clearStaleLocks(gitDir: File) {
        fun walk(d: File, depth: Int) {
            if (depth > 6) return
            for (k in d.listFiles() ?: return) {
                if (k.isDirectory) { if (k.name != "objects" && k.name != "lfs") walk(k, depth + 1) }
                else if (k.name.endsWith(".lock") || k.name == "gc.pid") k.delete()
            }
        }
        if (gitDir.isDirectory) walk(gitDir, 0)
    }

    private fun ensureBranch(git: Git, branch: String) {
        val repo = git.repository
        if (repo.branch == branch) return
        if (repo.resolve("HEAD") == null) {
            repo.updateRef(Constants.HEAD).link("refs/heads/$branch")
            return
        }
        val exists = repo.findRef("refs/heads/$branch") != null
        git.checkout().setName(branch).setCreateBranch(!exists).call()
    }

    private fun openRepo(root: File, link: RepoLink): Git {
        init()
        clearStaleLocks(File(root, ".git"))
        val git = if (File(root, ".git").exists()) Git.open(root)
        else Git.init().setDirectory(root).setInitialBranch(link.branch).call()
        val repo = git.repository
        if (repo.repositoryState != RepositoryState.SAFE) {
            // An unfinished merge from an interrupted run: forget the merge state, keep every file as it is.
            try { repo.writeMergeHeads(null); repo.writeMergeCommitMsg(null) } catch (_: Throwable) { }
        }
        val cfg = repo.config
        cfg.setString("remote", "origin", "url", remoteUrl(link))
        cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        cfg.setBoolean("core", null, "filemode", false)
        cfg.setInt("core", null, "compression", 1)
        cfg.setInt("core", null, "loosecompression", 1)
        cfg.setInt("pack", null, "compression", 1)
        cfg.setInt("pack", null, "threads", 2)
        cfg.setBoolean("pack", null, "deltacompression", false)
        cfg.save()
        val ex = File(repo.directory, "info/exclude")
        ex.parentFile?.mkdirs()
        if (!ex.exists() || !ex.readText().contains("*.gitex-tmp")) ex.appendText("\n*.gitex-tmp\n")
        ensureBranch(git, link.branch)
        return git
    }

    /** Unsaved editor text is committed as it is in the editor, without touching the files on disk. */
    private fun applyBuffers(repo: Repository, root: File, buffers: Map<String, String>) {
        val dc = repo.lockDirCache()
        try {
            val ins = repo.newObjectInserter()
            try {
                val ed: DirCacheEditor = dc.editor()
                var any = false
                val prefix = root.absolutePath + File.separator
                for ((abs, text) in buffers) {
                    if (!abs.startsWith(prefix)) continue
                    val rel = abs.substring(prefix.length).replace(File.separatorChar, '/')
                    if (dc.findEntry(rel) < 0) continue
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    val id = ins.insert(Constants.OBJ_BLOB, bytes)
                    ed.add(object : DirCacheEditor.PathEdit(rel) {
                        override fun apply(ent: DirCacheEntry) {
                            ent.fileMode = FileMode.REGULAR_FILE
                            ent.setLength(bytes.size)
                            ent.setObjectId(id)
                        }
                    })
                    any = true
                }
                ins.flush()
                if (any) ed.commit()
            } finally {
                ins.close()
            }
        } finally {
            dc.unlock()
        }
    }

    /** GitHub refuses files over 100 MB: unstage them, exclude them, and report which ones. */
    private fun excludeHuge(git: Git): List<String> {
        val repo = git.repository
        val big = ArrayList<String>()
        val dc = repo.readDirCache()
        for (i in 0 until dc.entryCount) {
            val e = dc.getEntry(i)
            if (e.length < 0 || e.length > MAX_FILE) big.add(e.pathString)
        }
        if (big.isEmpty()) return big
        val ex = File(repo.directory, "info/exclude")
        for (p in big) {
            try { git.rm().setCached(true).addFilepattern(p).call() } catch (_: Throwable) { }
            try { ex.appendText("\n/$p\n") } catch (_: Throwable) { }
        }
        return big
    }

    private class Staged(val changed: Int, val skipped: List<String>)

    private fun stageAndCommit(git: Git, root: File, message: String, buffers: Map<String, String>, onStatus: (String) -> Unit): Staged? {
        onStatus("Adding files…")
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        if (buffers.isNotEmpty()) applyBuffers(git.repository, root, buffers)
        val skipped = excludeHuge(git)
        onStatus("Committing…")
        val repo = git.repository
        val head = repo.resolve("HEAD^{tree}")
        val dc = repo.readDirCache()
        val idxTree = repo.newObjectInserter().use { ins -> val id = dc.writeTree(ins); ins.flush(); id }
        if (head != null && head == idxTree) return null
        val changed = try {
            git.diff().setCached(true).setShowNameAndStatusOnly(true).call().size
        } catch (_: Throwable) {
            dc.entryCount
        }
        val (n, e) = identity()
        git.commit().setMessage(message).setAuthor(n, e).setCommitter(n, e).call()
        return Staged(changed, skipped)
    }

    private fun headSha(git: Git): String = git.repository.resolve("HEAD")?.name() ?: ""

    private fun chain(e: Throwable) = generateSequence(e) { it.cause }.take(10).toList()

    private fun detail(e: Throwable): String {
        val first = e.message.orEmpty()
        val deepest = chain(e).last().message.orEmpty()
        return if (deepest.isNotBlank() && deepest != first) "$first: $deepest" else first.ifBlank { e.javaClass.simpleName }
    }

    private fun map(e: Throwable): GitResult {
        val ch = chain(e)
        val msg = ch.mapNotNull { it.message }.joinToString(" | ")
        return when {
            ch.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is SocketException } ->
                GitResult.Offline("Couldn't reach GitHub. Check your internet connection.")
            ch.any { it is NoRemoteRepositoryException } || msg.contains("repository not found", true) ->
                GitResult.NotFound(detail(e))
            msg.contains("not authorized", true) || msg.contains("Authentication", true) ||
                msg.contains("401") || msg.contains("403") ->
                GitResult.AuthFailed("GitHub didn't accept your sign-in, or your account can't write to this repository. Reconnect in Settings → GitHub.")
            ch.any { it is CheckoutConflictException } ->
                GitResult.Failed("Switching branch would overwrite local changes. Commit them first.")
            else -> GitResult.Failed(detail(e))
        }
    }

    /** Asks GitHub whether the saved token itself is bad (then the Reconnect dialog appears) or the problem is only permissions. */
    private suspend fun checkAuth(r: GitResult.AuthFailed): GitResult = when (GitHubAuth.tokenValid()) {
        false -> {
            AuthBus.expire("GitHub rejected the saved sign-in while pushing.")
            GitResult.AuthFailed("Your GitHub sign-in is no longer valid. Reconnect to continue.")
        }
        true -> GitResult.AuthFailed(
            "GitHub accepts your sign-in but refused this repository. Your account may not have write access to it, " +
                "or a branch protection rule applies."
        )
        null -> r
    }

    /** When Git says "not found", ask GitHub's API what is really wrong so the message is exact. */
    private suspend fun diagnose(link: RepoLink, raw: String): GitResult {
        val t = Prefs.githubToken() ?: return GitResult.AuthFailed("Connect your GitHub account first.")
        val r = try {
            Http.request(
                "GET", "https://api.github.com/repos/${link.owner}/${link.repo}",
                mapOf("Authorization" to "Bearer $t", "Accept" to "application/vnd.github+json")
            )
        } catch (e: Exception) {
            return GitResult.Offline(Http.describe(e))
        }
        return when (r.code) {
            200 -> {
                val canPush = try { JSONObject(r.body).optJSONObject("permissions")?.optBoolean("push", true) ?: true } catch (_: Exception) { true }
                if (!canPush) GitResult.AuthFailed("Your account can see ${link.owner}/${link.repo} but has no write access to it.")
                else GitResult.Failed("GitHub has ${link.owner}/${link.repo}, but refused the Git connection ($raw). Try disconnecting and reconnecting GitHub in Settings → GitHub.")
            }
            404 -> GitResult.NotFound("GitHub can't find \"${link.owner}/${link.repo}\" for your account. Check the repository name in Repo Name & Branch (the owner comes from your GitHub URL in Settings).")
            401 -> {
                AuthBus.expire("GitHub rejected the saved sign-in.")
                GitResult.AuthFailed("Your GitHub sign-in is no longer valid. Reconnect to continue.")
            }
            else -> GitResult.Failed("GitHub answered HTTP ${r.code} while checking the repository.")
        }
    }

    private suspend fun finish(e: Throwable, job: Job?, link: RepoLink): GitResult {
        if (job?.isActive == false) throw CancellationException("cancelled")
        if (e is CancellationException) throw e
        return when (val r = map(e)) {
            is GitResult.NotFound -> diagnose(link, r.detail)
            is GitResult.AuthFailed -> checkAuth(r)
            else -> r
        }
    }

    // ---- commit ----

    suspend fun commit(
        root: File, link: RepoLink, message: String, buffers: Map<String, String>,
        onStatus: (String) -> Unit = {},
    ): GitResult = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        if (lock.isLocked) onStatus("Finishing the previous operation…")
        lock.withLock {
            try {
                val git = openRepo(root, link)
                try {
                    val s = stageAndCommit(git, root, message, buffers, onStatus)
                    if (s == null) GitResult.NothingToCommit else GitResult.Committed(headSha(git), s.changed, s.skipped)
                } finally {
                    git.close()
                }
            } catch (e: Throwable) {
                finish(e, job, link)
            }
        }
    }

    // ---- push ----

    private fun remoteTip(link: RepoLink, job: Job?): ObjectId? {
        val refs = Git.lsRemoteRepository().setRemote(remoteUrl(link)).setHeads(true)
            .setCredentialsProvider(creds(link)).setTimeout(30).callAsMap()
        if (job?.isActive == false) throw CancellationException("cancelled")
        return refs["refs/heads/${link.branch}"]?.objectId
    }

    private fun pushBlocking(
        root: File, link: RepoLink, message: String, buffers: Map<String, String>,
        force: Boolean, onStatus: (String) -> Unit, job: Job?,
    ): GitResult {
        val git = openRepo(root, link)
        try {
            // Fast check first: if GitHub already has commits we don't have, say so now, before any heavy work.
            onStatus("Contacting GitHub…")
            val tip = remoteTip(link, job)
            if (!force && tip != null && !git.repository.objectDatabase.has(tip)) {
                return GitResult.Rejected("GitHub has commits this phone doesn't have yet.", true)
            }
            val staged = stageAndCommit(git, root, message, buffers, onStatus)
            if (git.repository.resolve("HEAD") == null) return GitResult.Failed("This project has no files to commit yet.")
            onStatus("Uploading…")
            val b = link.branch
            val spec = RefSpec("refs/heads/$b:refs/heads/$b").setForceUpdate(force)
            val results = git.push().setRemote("origin").setRefSpecs(spec).setForce(force)
                .setCredentialsProvider(creds(link)).setTimeout(60)
                .setProgressMonitor(monitor(job, onStatus, "Uploading")).call()
            var upToDate = false
            var ok = false
            for (r in results) {
                val server = r.messages.orEmpty().trim()
                for (u in r.remoteUpdates) {
                    when (u.status) {
                        RemoteRefUpdate.Status.OK -> ok = true
                        RemoteRefUpdate.Status.UP_TO_DATE -> upToDate = true
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD,
                        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                            return GitResult.Rejected("GitHub has commits this phone doesn't have yet.", true)
                        RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                            return GitResult.Rejected(
                                (u.message ?: server).ifBlank { "GitHub rejected the push (for example a protected branch)." }, false
                            )
                        else -> return GitResult.Failed("Push didn't complete (${u.status}). ${u.message ?: server}".trim())
                    }
                }
            }
            val skipped = staged?.skipped ?: emptyList()
            return if (ok || upToDate) GitResult.Pushed(headSha(git), upToDate && !ok, skipped)
            else GitResult.Failed("GitHub didn't confirm the push.")
        } finally {
            git.close()
        }
    }

    /** Commits whatever changed, then pushes the branch (creating it on GitHub if needed). [force] only after explicit confirmation. */
    suspend fun push(
        root: File, link: RepoLink, message: String, buffers: Map<String, String>,
        force: Boolean = false, onStatus: (String) -> Unit = {},
    ): GitResult = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        if (lock.isLocked) onStatus("Finishing the previous operation…")
        lock.withLock {
            try {
                pushBlocking(root, link, message, buffers, force, onStatus, job)
            } catch (e: Throwable) {
                finish(e, job, link)
            }
        }
    }

    // ---- merge ----

    private fun mergeBlocking(
        root: File, link: RepoLink, message: String, buffers: Map<String, String>,
        onStatus: (String) -> Unit, job: Job?,
    ): MergeOutcome {
        val git = openRepo(root, link)
        try {
            // Commit local work first, so that undoing a failed merge can never lose an edit.
            stageAndCommit(git, root, message, buffers, onStatus)
            onStatus("Fetching from GitHub…")
            git.fetch().setRemote("origin").setCredentialsProvider(creds(link)).setTimeout(60)
                .setProgressMonitor(monitor(job, onStatus, "Fetching")).call()
            val repo = git.repository
            val remote = repo.resolve("refs/remotes/origin/${link.branch}") ?: return MergeOutcome.NoRemoteBranch
            val before = repo.resolve("HEAD") ?: return MergeOutcome.Failed("There is nothing local to merge into.")
            onStatus("Merging…")
            val res = git.merge().include(remote).setFastForward(MergeCommand.FastForwardMode.FF)
                .setCommit(true).setMessage("Merge remote changes from GitHub").call()
            val status = res.mergeStatus
            return when {
                status.toString() == "ALREADY_UP_TO_DATE" -> MergeOutcome.UpToDate
                status.isSuccessful -> MergeOutcome.Merged
                else -> {
                    val files = res.conflicts?.keys?.toList().orEmpty()
                    try { git.reset().setMode(ResetCommand.ResetType.HARD).setRef(before.name()).call() } catch (_: Throwable) { }
                    if (files.isNotEmpty()) MergeOutcome.Conflicts(files) else MergeOutcome.Failed("The merge couldn't be completed ($status).")
                }
            }
        } finally {
            git.close()
        }
    }

    /** Brings GitHub's commits into the local branch. On conflict everything is put back exactly as it was. */
    suspend fun fetchAndMerge(
        root: File, link: RepoLink, message: String, buffers: Map<String, String>,
        onStatus: (String) -> Unit = {},
    ): MergeOutcome = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        if (lock.isLocked) onStatus("Finishing the previous operation…")
        lock.withLock {
            try {
                mergeBlocking(root, link, message, buffers, onStatus, job)
            } catch (e: Throwable) {
                if (job?.isActive == false) throw CancellationException("cancelled")
                if (e is CancellationException) throw e
                val m0 = map(e)
                val m = if (m0 is GitResult.AuthFailed) checkAuth(m0) else m0
                MergeOutcome.Failed(
                    when (m) {
                        is GitResult.Offline -> m.detail
                        is GitResult.AuthFailed -> m.detail
                        is GitResult.NotFound -> m.detail
                        is GitResult.Failed -> m.detail
                        else -> detail(e)
                    }
                )
            }
        }
    }
}