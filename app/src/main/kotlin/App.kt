package app.gitex

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Notif.createChannels(this)
    }

    companion object {
        lateinit var instance: App
            private set

        /** Process-wide scope for work that must outlive a screen (background dispatcher). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** Notification channel ids and creation. Channels are created once; Android ignores repeats. */
object Notif {
    const val CH_BUILD_PROGRESS = "build_progress"
    const val CH_BUILD_RESULT = "build_result"
    const val CH_DUPLICATES = "duplicates"
    const val CH_DOWNLOADS = "downloads"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        fun ch(id: String, name: String, desc: String, importance: Int) {
            val c = NotificationChannel(id, name, importance)
            c.description = desc
            nm.createNotificationChannel(c)
        }
        ch(
            CH_BUILD_PROGRESS, "Build in progress",
            "Shown while a GitHub Actions run is being followed",
            NotificationManager.IMPORTANCE_LOW
        )
        ch(
            CH_BUILD_RESULT, "Build results",
            "Tells you when a build succeeded or failed",
            NotificationManager.IMPORTANCE_HIGH
        )
        ch(
            CH_DUPLICATES, "Duplicate content",
            "Warns when identical content is found in different files",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        ch(
            CH_DOWNLOADS, "Downloads",
            "Progress of artifact downloads",
            NotificationManager.IMPORTANCE_LOW
        )
    }
}