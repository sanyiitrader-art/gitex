package app.gitex

import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

class GitHubException(val code: Int, message: String) : Exception(message)

class Run(
    val id: Long,
    val owner: String,
    val repo: String,
    val name: String,
    val number: Int,
    val branch: String,
    val sha: String,
    val message: String,
    val status: String,
    val conclusion: String?,
    val createdAt: String,
    val updatedAt: String,
    val url: String,
    val event: String,
) {
    val fullName: String get() = "$owner/$repo"
    val finished: Boolean get() = status == "completed"
    val succeeded: Boolean get() = finished && conclusion == "success"
    val failed: Boolean get() = finished && (conclusion == "failure" || conclusion == "timed_out" || conclusion == "startup_failure")
}

class ArtifactInfo(
    val id: Long,
    val owner: String,
    val repo: String,
    val name: String,
    val size: Long,
    val expired: Boolean,
    val runId: Long,
    val createdAt: String,
)

class JobStep(val number: Int, val name: String, val conclusion: String?, val startedAt: String?, val completedAt: String?)
class RunJob(val id: Long, val name: String, val conclusion: String?, val steps: List<JobStep>)

/** [exact] is false when the failed step could not be isolated and the end of the job log is shown instead. */
class FailedLog(val job: String, val step: String, val text: String, val exact: Boolean)

class History(val runs: Map<Long, Run>, val artifacts: List<ArtifactInfo>, val errors: List<String>, val more: Boolean)

object GitHub {
    private const val API = "https://api.github.com"
    private const val ARTIFACT_PAGE = 30

    private fun token(): String =
        Prefs.githubToken() ?: throw GitHubException(401, "Connect your GitHub account first (Settings → GitHub).")

    /** The token is only ever sent to api.github.com, never to the storage hosts GitHub redirects to. */
    private fun open(url: String, withAuth: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000
        c.readTimeout = 40_000
        c.setRequestProperty("User-Agent", "Gitex-Android")
        if (withAuth && url.startsWith(API)) {
            c.setRequestProperty("Authorization", "Bearer ${token()}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return c
    }

    private fun explain(c: HttpURLConnection, code: Int): GitHubException {
        val body = try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
        val api = try { JSONObject(body).optString("message") } catch (_: Exception) { "" }
        val remaining = c.getHeaderField("x-ratelimit-remaining")
        val msg = when {
            code == 401 -> "GitHub sign-in expired or was revoked. Reconnect in Settings → GitHub."
            code == 403 && remaining == "0" -> "GitHub's request limit was reached. Try again in a few minutes."
            code == 403 -> "GitHub refused access. $api".trim()
            code == 404 -> "Not found on GitHub. Check the repository name and that your account can access it."
            code == 410 -> "This item has expired on GitHub."
            else -> api.ifBlank { "GitHub error (HTTP $code)" }
        }
        return GitHubException(code, msg)
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val c = open("$API$path", true)
        try {
            val code = c.responseCode
            if (code in 200..299) JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            else throw explain(c, code)
        } catch (e: IOException) {
            throw GitHubException(0, Http.describe(e))
        } finally {
            c.disconnect()
        }
    }

    /** Follows GitHub's redirect to the storage host by hand, dropping the Authorization header on the way. */
    private fun openFollowing(url: String): HttpURLConnection {
        var current = url
        var auth = true
        for (hop in 0 until 6) {
            val c = open(current, auth)
            val code = c.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw GitHubException(code, "GitHub sent a redirect without a target")
                current = loc
                auth = false
                continue
            }
            if (code in 200..299) return c
            val e = explain(c, code)
            c.disconnect()
            throw e
        }
        throw GitHubException(0, "Too many redirects from GitHub")
    }

    private fun parseRun(o: JSONObject, owner: String, repo: String): Run {
        val msg = (o.optJSONObject("head_commit")?.optString("message") ?: o.optString("display_title"))
            .lineSequence().firstOrNull().orEmpty()
        return Run(
            id = o.getLong("id"), owner = owner, repo = repo,
            name = o.optString("name").ifBlank { o.optString("display_title") },
            number = o.optInt("run_number"),
            branch = o.optString("head_branch"), sha = o.optString("head_sha"), message = msg,
            status = o.optString("status"),
            conclusion = if (o.isNull("conclusion")) null else o.optString("conclusion"),
            createdAt = o.optString("created_at"), updatedAt = o.optString("updated_at"),
            url = o.optString("html_url"), event = o.optString("event"),
        )
    }

    suspend fun runs(
        owner: String, repo: String, page: Int = 1, perPage: Int = 20,
        headSha: String? = null, status: String? = null,
    ): List<Run> {
        val q = StringBuilder("/repos/$owner/$repo/actions/runs?per_page=$perPage&page=$page")
        if (headSha != null) q.append("&head_sha=$headSha")
        if (status != null) q.append("&status=$status")
        val arr = getJson(q.toString()).optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until arr.length()).map { parseRun(arr.getJSONObject(it), owner, repo) }
    }

    suspend fun run(owner: String, repo: String, id: Long): Run =
        parseRun(getJson("/repos/$owner/$repo/actions/runs/$id"), owner, repo)

    private fun parseArtifact(a: JSONObject, owner: String, repo: String) = ArtifactInfo(
        id = a.getLong("id"), owner = owner, repo = repo, name = a.optString("name"),
        size = a.optLong("size_in_bytes"), expired = a.optBoolean("expired"),
        runId = a.optJSONObject("workflow_run")?.optLong("id") ?: 0L,
        createdAt = a.optString("created_at"),
    )

    suspend fun artifacts(owner: String, repo: String, page: Int = 1): List<ArtifactInfo> {
        val arr = getJson("/repos/$owner/$repo/actions/artifacts?per_page=$ARTIFACT_PAGE&page=$page")
            .optJSONArray("artifacts") ?: return emptyList()
        return (0 until arr.length()).map { parseArtifact(arr.getJSONObject(it), owner, repo) }
    }

    suspend fun runArtifacts(owner: String, repo: String, runId: Long): List<ArtifactInfo> {
        val arr = getJson("/repos/$owner/$repo/actions/runs/$runId/artifacts?per_page=100")
            .optJSONArray("artifacts") ?: return emptyList()
        return (0 until arr.length()).map { parseArtifact(arr.getJSONObject(it), owner, repo) }
    }

    suspend fun jobs(owner: String, repo: String, runId: Long): List<RunJob> {
        val arr = getJson("/repos/$owner/$repo/actions/runs/$runId/jobs?per_page=100").optJSONArray("jobs")
            ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            val sa = j.optJSONArray("steps")
            val steps = if (sa == null) emptyList() else (0 until sa.length()).map { k ->
                val s = sa.getJSONObject(k)
                JobStep(
                    s.optInt("number"), s.optString("name"),
                    if (s.isNull("conclusion")) null else s.optString("conclusion"),
                    if (s.isNull("started_at")) null else s.optString("started_at"),
                    if (s.isNull("completed_at")) null else s.optString("completed_at"),
                )
            }
            RunJob(j.getLong("id"), j.optString("name"), if (j.isNull("conclusion")) null else j.optString("conclusion"), steps)
        }
    }

    // ---- aggregated history across the repositories Gitex has been used with ----

    private class Part(val a: List<ArtifactInfo>, val r: List<Run>, val err: String?)

    suspend fun history(links: List<RepoLink>, page: Int): History = coroutineScope {
        val uniq = links.filter { it.owner.isNotBlank() && it.repo.isNotBlank() }
            .distinctBy { "${it.owner}/${it.repo}".lowercase() }
        val parts = uniq.map { l ->
            async(Dispatchers.IO) {
                try {
                    Part(artifacts(l.owner, l.repo, page), runs(l.owner, l.repo, page, 30), null)
                } catch (e: GitHubException) {
                    Part(emptyList(), emptyList(), "${l.owner}/${l.repo}: ${e.message}")
                }
            }
        }.map { it.await() }

        val runMap = HashMap<Long, Run>()
        val arts = ArrayList<ArtifactInfo>()
        val errors = ArrayList<String>()
        for (p in parts) {
            for (r in p.r) runMap[r.id] = r
            arts.addAll(p.a)
            p.err?.let { errors.add(it) }
        }
        // Artifacts whose run was not in the recent-runs page: look those runs up individually (a few at most).
        val missing = arts.filter { it.runId != 0L && it.runId !in runMap }.distinctBy { it.runId }.take(8)
        for (a in missing) {
            try { runMap[a.runId] = run(a.owner, a.repo, a.runId) } catch (_: GitHubException) { }
        }
        History(runMap, arts, errors, parts.any { it.a.size >= ARTIFACT_PAGE })
    }

    class FailedRuns(val runs: List<Run>, val errors: List<String>, val more: Boolean)

    suspend fun failedRuns(links: List<RepoLink>, page: Int): FailedRuns = coroutineScope {
        val uniq = links.filter { it.owner.isNotBlank() && it.repo.isNotBlank() }
            .distinctBy { "${it.owner}/${it.repo}".lowercase() }
        val res = uniq.map { l ->
            async(Dispatchers.IO) {
                try { runs(l.owner, l.repo, page, 20, status = "failure") to null as String? }
                catch (e: GitHubException) { emptyList<Run>() to "${l.owner}/${l.repo}: ${e.message}" }
            }
        }.map { it.await() }
        FailedRuns(
            res.flatMap { it.first }.sortedByDescending { it.createdAt },
            res.mapNotNull { it.second },
            res.any { it.first.size >= 20 }
        )
    }

    // ---- failed step log ----

    private class LogLine(val ts: Long, val text: String)

    private suspend fun jobLogText(owner: String, repo: String, jobId: Long): String = withContext(Dispatchers.IO) {
        val c = try { openFollowing("$API/repos/$owner/$repo/actions/jobs/$jobId/logs") }
        catch (e: IOException) { throw GitHubException(0, Http.describe(e)) }
        try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            val cap = 12 * 1024 * 1024
            c.inputStream.use { input ->
                while (out.size() < cap) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: IOException) {
            throw GitHubException(0, Http.describe(e))
        } finally {
            c.disconnect()
        }
    }

    private fun parseLog(raw: String): List<LogLine> {
        val out = ArrayList<LogLine>()
        var last = 0L
        for (l0 in raw.lineSequence()) {
            var l = l0.removePrefix("\uFEFF").trimEnd('\r')
            var ts = last
            if (l.length > 28 && l[4] == '-' && l[10] == 'T') {
                val sp = l.indexOf(' ')
                if (sp in 20..40) {
                    try {
                        ts = Instant.parse(l.substring(0, sp)).toEpochMilli()
                        l = l.substring(sp + 1)
                    } catch (_: Exception) {
                    }
                }
            }
            last = ts
            out.add(LogLine(ts, l))
        }
        return out
    }

    private fun pretty(l: String): String? = when {
        l.startsWith("##[endgroup]") || l.startsWith("##[debug]") -> null
        l.startsWith("##[group]") -> "▶ " + l.removePrefix("##[group]")
        l.startsWith("##[error]") -> "Error: " + l.removePrefix("##[error]")
        l.startsWith("##[warning]") -> "Warning: " + l.removePrefix("##[warning]")
        else -> l
    }

    private fun millis(iso: String?): Long? = try { if (iso == null) null else Instant.parse(iso).toEpochMilli() } catch (_: Exception) { null }

    /**
     * Finds the failed job and failed step, then cuts that step's section out of the job log.
     * GitHub only serves whole-job logs, so the step is located by its start/end times and
     * its "##[group]" header; if that cannot be done reliably the end of the job log is shown instead.
     */
    suspend fun failedStepLog(owner: String, repo: String, runId: Long): FailedLog {
        val jobs = jobs(owner, repo, runId)
        val job = jobs.firstOrNull { it.conclusion == "failure" }
            ?: jobs.firstOrNull { it.conclusion == "timed_out" || it.conclusion == "startup_failure" }
            ?: throw GitHubException(0, "No failed job was found in this run")
        val step = job.steps.firstOrNull { it.conclusion == "failure" }
        val lines = parseLog(jobLogText(owner, repo, job.id))

        fun tail(stepName: String): FailedLog {
            val t = lines.takeLast(400).mapNotNull { pretty(it.text) }.joinToString("\n")
            return FailedLog(job.name, stepName, t, exact = false)
        }

        if (step == null) return tail("(job-level failure)")
        val start = millis(step.startedAt)
        val end = millis(step.completedAt)
        if (start == null || end == null) return tail(step.name)
        val selected = lines.indices.filter { lines[it].ts in start..(end + 1000) }
        if (selected.isEmpty()) return tail(step.name)

        val firstSel = selected.first()
        val lastSel = selected.last()
        var from = firstSel
        var to = lastSel
        val firstErr = (firstSel..lastSel).firstOrNull { lines[it].text.startsWith("##[error]") }
        if (firstErr != null) {
            val header = (firstErr downTo 0).firstOrNull { lines[it].text.startsWith("##[group]") }
            from = if (header != null && header >= firstSel - 40) header else firstSel
            to = (firstSel..lastSel).last { lines[it].text.startsWith("##[error]") }
        }
        var text = lines.subList(from, to + 1).mapNotNull { pretty(it.text) }
        if (text.size > 6000) text = listOf("… (earlier output omitted)") + text.takeLast(6000)
        return FailedLog(job.name, step.name, text.joinToString("\n"), exact = true)
    }

    // ---- artifact download ----

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    /**
     * Downloads the artifact zip to Download/Gitex. The file only gets its real name after it
     * was fully received and opened successfully as a zip; failed or cancelled downloads leave nothing behind.
     * [onProgress] receives (bytes done, total bytes, bytes per second).
     */
    suspend fun download(a: ArtifactInfo, onProgress: (Long, Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        if (a.expired) throw GitHubException(410, "This artifact has expired on GitHub")
        val dir = File(Environment.getExternalStorageDirectory(), "Download/Gitex")
        if (!dir.isDirectory && !dir.mkdirs()) throw GitHubException(0, "Couldn't create the Download/Gitex folder")
        val base = a.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "artifact" }
        val part = File(dir, ".$base-${a.id}.part")
        val c = try { openFollowing("$API/repos/${a.owner}/${a.repo}/actions/artifacts/${a.id}/zip") }
        catch (e: IOException) { throw GitHubException(0, Http.describe(e)) }
        try {
            val declared = c.contentLengthLong
            val total = if (declared > 0) declared else a.size
            var done = 0L
            var lastT = SystemClock.elapsedRealtime()
            var lastBytes = 0L
            var bps = 0L
            c.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastT >= 250) {
                            bps = (done - lastBytes) * 1000 / (now - lastT)
                            lastT = now; lastBytes = done
                            onProgress(done, total, bps)
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            if (done == 0L) throw IOException("GitHub sent an empty file")
            if (declared > 0 && done != declared) throw IOException("The download was incomplete")
            ZipFile(part).use { z -> if (!z.entries().hasMoreElements()) throw IOException("The downloaded zip is empty or damaged") }
            val dest = uniqueFile(dir, "$base.zip")
            if (!part.renameTo(dest)) throw IOException("Couldn't save the downloaded file")
            onProgress(done, if (total > 0) total else done, bps)
            dest
        } catch (e: IOException) {
            part.delete()
            throw GitHubException(0, if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) Http.describe(e) else (e.message ?: "Download failed"))
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }
}