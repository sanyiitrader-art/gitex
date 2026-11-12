package app.gitex

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A request to jump to a screen, coming from a notification button. MainActivity reads and clears it. */
class NavRequest(val route: String, val repo: String?, val runId: Long)

object NavBus {
    var pending by mutableStateOf<NavRequest?>(null)
}

class ActiveBuild(val repo: String, val title: String, val status: String)

/** What the Actions screen shows about a run being followed, plus a tick that makes it reload when one finishes. */
object BuildState {
    var active by mutableStateOf<ActiveBuild?>(null)
    var refreshTick by mutableIntStateOf(0)
}

object BuildWatcher {
    /** Follows the workflow run that GitHub starts for [sha]. Does nothing if build notifications are turned off. */
    fun start(ctx: Context, owner: String, repo: String, sha: String, branch: String) {
        if (!Prefs.buildNotifications || sha.isBlank()) return
        val i = Intent(ctx, BuildService::class.java)
            .putExtra("owner", owner).putExtra("repo", repo).putExtra("sha", sha).putExtra("branch", branch)
        try {
            ContextCompat.startForegroundService(ctx, i)
        } catch (_: Exception) {
        }
    }
}

fun pendingOpen(ctx: Context, route: String, repo: String?, runId: Long): PendingIntent {
    val i = Intent(ctx, MainActivity::class.java).apply {
        // A unique data URI keeps each button's PendingIntent distinct (extras alone don't).
        data = Uri.parse("gitex://open/$route/$runId")
        putExtra("route", route)
        putExtra("repo", repo)
        putExtra("run", runId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
        ctx, route.hashCode() xor runId.toInt(), i,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Short-lived foreground service: waits for the run GitHub starts after a push, shows an ongoing "Building"
 * notification, then posts the result. Polling slows down over time to stay gentle on battery and rate limits.
 */
class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val owner = intent?.getStringExtra("owner")
        val repo = intent?.getStringExtra("repo")
        val sha = intent?.getStringExtra("sha")
        if (owner == null || repo == null || sha == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, ID_PROGRESS, progressNotif("Building", "Waiting for GitHub to start the run…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        job?.cancel()
        job = scope.launch {
            try {
                watch(owner, repo, sha)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                BuildState.active = null
                BuildState.refreshTick++
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressNotif(title: String, text: String): Notification =
        NotificationCompat.Builder(this, Notif.CH_BUILD_PROGRESS)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingOpen(this, "actions", null, 0L))
            .build()

    private fun updateProgress(text: String) {
        try { NotificationManagerCompat.from(this).notify(ID_PROGRESS, progressNotif("Building", text)) } catch (_: SecurityException) { }
    }

    private fun toast(msg: String, kind: BannerKind = BannerKind.Info) {
        Handler(Looper.getMainLooper()).post { Banners.show(msg, kind) }
    }

    private suspend fun watch(owner: String, repo: String, sha: String) {
        // 1) Wait for GitHub to create a run for this commit.
        var run: Run? = null
        val findUntil = SystemClock.elapsedRealtime() + 100_000
        var wait = 4000L
        var failures = 0
        while (run == null && SystemClock.elapsedRealtime() < findUntil) {
            try {
                run = GitHub.runs(owner, repo, perPage = 5, headSha = sha).firstOrNull()
                failures = 0
            } catch (e: GitHubException) {
                if (e.code == 401 || e.code == 404 || ++failures >= 6) { toast(e.message ?: "Couldn't check the build", BannerKind.Error); return }
            }
            if (run == null) {
                delay(wait)
                wait = (wait * 13 / 10).coerceAtMost(9000L)
            }
        }
        if (run == null) {
            toast("No GitHub Actions run started for this push")
            return
        }

        // 2) Follow it until it completes.
        val began = SystemClock.elapsedRealtime()
        var polls = 0
        failures = 0
        while (true) {
            val label = "${run!!.name} #${run.number} · ${run.fullName}"
            BuildState.active = ActiveBuild(run.fullName, "${run.name} #${run.number}", run.status)
            updateProgress(if (run.status == "queued") "Queued: $label" else label)
            if (run.finished) break
            if (SystemClock.elapsedRealtime() - began > 90 * 60_000L) { toast("Stopped following the build after 90 minutes"); return }
            delay(if (polls < 24) 5000L else 15_000L)
            polls++
            try {
                run = GitHub.run(owner, repo, run.id)
                failures = 0
            } catch (e: GitHubException) {
                if (e.code == 401 || e.code == 404 || ++failures >= 10) { toast(e.message ?: "Lost track of the build", BannerKind.Error); return }
            }
        }
        notifyResult(run!!)
    }

    private fun notifyResult(run: Run) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val title = when {
            run.succeeded -> "Build succeeded"
            run.failed -> "Build failed"
            else -> "Build ${run.conclusion ?: "finished"}"
        }
        val text = "${run.name} #${run.number} · ${run.fullName}" + if (run.message.isNotBlank()) "\n${run.message}" else ""
        val b = NotificationCompat.Builder(this, Notif.CH_BUILD_RESULT)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("${run.name} #${run.number} · ${run.fullName}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (run.succeeded) {
            val pi = pendingOpen(this, "artifacts", run.fullName, run.id)
            b.setContentIntent(pi).addAction(0, "Artifacts", pi)
        } else if (run.failed) {
            val pi = pendingOpen(this, "log", run.fullName, run.id)
            b.setContentIntent(pi).addAction(0, "See failure Log", pi)
        } else {
            b.setContentIntent(pendingOpen(this, "actions", null, 0L))
        }
        try { nm.notify(ID_RESULT_BASE + (run.id % 800).toInt(), b.build()) } catch (_: SecurityException) { }
    }

    companion object {
        private const val ID_PROGRESS = 4001
        private const val ID_RESULT_BASE = 4100
    }
}