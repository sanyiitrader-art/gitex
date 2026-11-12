package app.gitex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed class GitResult {
    class Committed(val sha: String, val files: Int) : GitResult()
    object NothingToCommit : GitResult()
    class Pushed(val sha: String, val upToDate: Boolean) : GitResult()
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

/**
 * Real Git through JGit. Commit and Push are separate: a commit is local only and never triggers
 * GitHub Actions; a push may. A rejected push is never forced automatically.
 */
object GitOps {
    private var inited = false

    private fun init() {
        if (inited) return
        inited = true
        // JGit looks for a home folder and global config; point it at the app's private storage.
        System.setProperty("user.home", App.instance.filesDir.absolutePath)
        try {
            val c = WindowCacheConfig()
            c.setPackedGitMMAP(false)
            c.setPackedGitLimit(16L * 1024 * 1024)
            c.setPackedGitWindowSize(1024 * 1024)
            c.setDeltaBaseCacheLimit(8 * 1024 * 1024)
            c.install()
        } catch (_: Throwable) {
        }
    }

    fun remoteUrl(link: RepoLink) = "https://github.com/${link.owner}/${link.repo}.git"

    fun hasWorkflows(root: File): Boolean =
        File(root, ".github/workflows").listFiles()?.any { it.name.endsWith(".yml") || it.name.endsWith(".yaml") } == true

    private fun identity(): Pair<String, String> {
        val login = Validate.accountName(Prefs.githubUrl).ifBlank { "gitex" }
        return Prefs.gitName.ifBlank { login } to Prefs.gitEmail.ifBlank { "$login@users.noreply.github.com" }
    }

    private fun creds(): UsernamePasswordCredentialsProvider {
        val t = Prefs.githubToken() ?: throw IllegalStateException("Not connected to GitHub")
        return UsernamePasswordCredentialsProvider("x-access-token", t)
    }

    private fun ensureBranch(git: Git, branch: String) {
        val repo = git.repository
        if (repo.branch == branch) return
        if (repo.resolve("HEAD") == null) {
            // No commits yet: just point HEAD at the wanted branch name.
            repo.updateRef(Constants.HEAD).link("refs/heads/$branch")
            return
        }
        val exists = repo.findRef("refs/heads/$branch") != null
        git.checkout().setName(branch).setCreateBranch(!exists).call()
    }

    private fun openRepo(root: File, link: RepoLink): Git {
        init()
        val git = if (File(root, ".git").exists()) Git.open(root)
        else Git.init().setDirectory(root).setInitialBranch(link.branch).call()
        val repo = git.repository
        val cfg = repo.config
        cfg.setString("remote", "origin", "url", remoteUrl(link))
        cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        cfg.setBoolean("core", null, "filemode", false) // shared storage has no executable bit
        cfg.save()
        val ex = File(repo.directory, "info/exclude")
        ex.parentFile?.mkdirs()
        if (!ex.exists() || !ex.readText().contains("*.gitex-tmp")) ex.appendText("\n*.gitex-tmp\n")
        ensureBranch(git, link.branch)
        return git
    }

    /**
     * Content of files that are open in the editor but not saved is committed as it is in the editor,
     * without touching the files on disk.
     */
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
                    if (dc.findEntry(rel) < 0) continue // ignored or untracked: leave alone
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

    /** Returns the number of changed files that were committed, or null if there was nothing to commit. */
    private fun stageAndCommit(git: Git, root: File, message: String, buffers: Map<String, String>): Int? {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call() // also stages deletions
        if (buffers.isNotEmpty()) applyBuffers(git.repository, root, buffers)
        val st = git.status().call()
        val changed = st.added.size + st.changed.size + st.removed.size
        if (changed == 0) return null
        val (n, e) = identity()
        git.commit().setMessage(message).setAuthor(n, e).setCommitter(n, e).call()
        return changed
    }

    private fun headSha(git: Git): String = git.repository.resolve("HEAD")?.name() ?: ""

    private fun map(e: Throwable): GitResult {
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        val msg = chain.mapNotNull { it.message }.joinToString(" | ")
        return when {
            chain.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is SocketException } ->
                GitResult.Offline("Couldn't reach GitHub. Check your internet connection.")
            msg.contains("not authorized", true) || msg.contains("Authentication", true) ||
                msg.contains("401") || msg.contains("403") ->
                GitResult.AuthFailed("GitHub didn't accept your sign-in, or your account can't write to this repository. Reconnect in Settings → GitHub.")
            msg.contains("not found", true) || msg.contains("404") ->
                GitResult.NotFound("GitHub can't find this repository. Create it on github.com first, or check the name in Repo Name & Branch.")
            chain.any { it is CheckoutConflictException } ->
                GitResult.Failed("Switching branch would overwrite local changes. Commit them first.")
            else -> GitResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun commit(root: File, link: RepoLink, message: String, buffers: Map<String, String>): GitResult =
        withContext(Dispatchers.IO) {
            try {
                openRepo(root, link).use { git ->
                    val n = stageAndCommit(git, root, message, buffers)
                    if (n == null) GitResult.NothingToCommit else GitResult.Committed(headSha(git), n)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                map(e)
            }
        }

    /** Commits whatever changed, then pushes the branch. With [force] the remote branch is overwritten (only after explicit confirmation). */
    suspend fun push(root: File, link: RepoLink, message: String, buffers: Map<String, String>, force: Boolean = false): GitResult =
        withContext(Dispatchers.IO) {
            try {
                openRepo(root, link).use { git ->
                    stageAndCommit(git, root, message, buffers)
                    if (git.repository.resolve("HEAD") == null) {
                        return@use GitResult.Failed("This project has no files to commit yet.")
                    }
                    val b = link.branch
                    val spec = RefSpec("refs/heads/$b:refs/heads/$b").setForceUpdate(force)
                    val results = git.push().setRemote("origin").setRefSpecs(spec).setForce(force)
                        .setCredentialsProvider(creds()).setTimeout(45).call()
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
                                    return@use GitResult.Rejected("GitHub has commits this phone doesn't have yet.", true)
                                RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                                    return@use GitResult.Rejected(
                                        (u.message ?: server).ifBlank { "GitHub rejected the push (for example a protected branch)." }, false
                                    )
                                else -> return@use GitResult.Failed("Push didn't complete (${u.status}). ${u.message ?: server}".trim())
                            }
                        }
                    }
                    if (ok || upToDate) GitResult.Pushed(headSha(git), upToDate && !ok)
                    else GitResult.Failed("GitHub didn't confirm the push.")
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                map(e)
            }
        }

    /** Fetches and returns how many commits exist on GitHub's branch that are missing locally. */
    suspend fun remoteAheadCount(root: File, link: RepoLink): Result<Int> = withContext(Dispatchers.IO) {
        try {
            openRepo(root, link).use { git ->
                git.fetch().setRemote("origin").setCredentialsProvider(creds()).setTimeout(45).call()
                val repo = git.repository
                val remote = repo.resolve("refs/remotes/origin/${link.branch}") ?: return@use Result.success(0)
                val local = repo.resolve("refs/heads/${link.branch}")
                val cmd = git.log().add(remote).setMaxCount(500)
                if (local != null) cmd.not(local)
                Result.success(cmd.call().count())
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Brings GitHub's commits into the local branch. If they conflict with local work the merge is undone
     * and nothing is changed. Save open files first.
     */
    suspend fun fetchAndMerge(root: File, link: RepoLink): MergeOutcome = withContext(Dispatchers.IO) {
        try {
            openRepo(root, link).use { git ->
                git.fetch().setRemote("origin").setCredentialsProvider(creds()).setTimeout(45).call()
                val repo = git.repository
                val remote = repo.resolve("refs/remotes/origin/${link.branch}") ?: return@use MergeOutcome.NoRemoteBranch
                val before = repo.resolve("HEAD") ?: return@use MergeOutcome.Failed("There is nothing local to merge into.")
                val res = git.merge().include(remote).setFastForward(MergeCommand.FastForwardMode.FF)
                    .setCommit(true).setMessage("Merge remote changes from GitHub").call()
                val status = res.mergeStatus
                when {
                    status.toString() == "ALREADY_UP_TO_DATE" -> MergeOutcome.UpToDate
                    status.isSuccessful -> MergeOutcome.Merged
                    else -> {
                        val files = res.conflicts?.keys?.toList().orEmpty()
                        try { git.reset().setMode(ResetCommand.ResetType.HARD).setRef(before.name()).call() } catch (_: Throwable) { }
                        if (files.isNotEmpty()) MergeOutcome.Conflicts(files) else MergeOutcome.Failed("The merge couldn't be completed ($status).")
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            MergeOutcome.Failed((map(e) as? GitResult.Failed)?.detail ?: e.message ?: "Merge failed")
        }
    }
}